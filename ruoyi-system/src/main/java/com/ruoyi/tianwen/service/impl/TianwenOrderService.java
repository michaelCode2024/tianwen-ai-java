package com.ruoyi.tianwen.service.impl;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.tianwen.config.TianwenAiProperties;
import com.ruoyi.tianwen.domain.TianwenH5User;
import com.ruoyi.tianwen.mapper.TianwenH5UserMapper;
import com.ruoyi.tianwen.mapper.TianwenOrderMapper;
import com.ruoyi.tianwen.mapper.TianwenUnlockMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TianwenOrderService {
  private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private final TianwenAiProperties properties;
  private final TianwenOrderMapper orderMapper;
  private final TianwenUnlockMapper unlockMapper;
  private final TianwenH5UserMapper userMapper;

  public TianwenOrderService(
      TianwenAiProperties properties,
      TianwenOrderMapper orderMapper,
      TianwenUnlockMapper unlockMapper,
      TianwenH5UserMapper userMapper) {
    this.properties = properties;
    this.orderMapper = orderMapper;
    this.unlockMapper = unlockMapper;
    this.userMapper = userMapper;
  }

  @Transactional
  public Map<String, Object> create(TianwenH5User user, Map<String, Object> body) {
    if (user == null || user.getId() == null) {
      throw new ServiceException("请先登录");
    }
    orderMapper.ensureTable();
    String productType = text(body == null ? null : body.get("productType"));
    String orderNo = newOrderNo();
    if ("unlock".equals(productType)) {
      String recommendationId = text(body.get("recommendationId"));
      if (!recommendationId.matches("\\d+")) {
        throw new ServiceException("recommendationId格式错误");
      }
      orderMapper.insert(
          orderNo,
          user.getId(),
          "unlock",
          recommendationId,
          null,
          "单场AI推荐解锁",
          new BigDecimal(properties.getPaidUnlockAmount()));
    } else if ("vip".equals(productType)) {
      int level = intValue(body.get("vipLevel"));
      VipPlan plan = VipPlan.of(level);
      orderMapper.insert(orderNo, user.getId(), "vip", null, plan.level, plan.title, plan.amount);
    } else {
      throw new ServiceException("productType只支持 unlock 或 vip");
    }
    return orderView(orderMapper.selectByNo(orderNo));
  }

  public Map<String, Object> list(TianwenH5User user) {
    if (user == null || user.getId() == null) {
      throw new ServiceException("请先登录");
    }
    orderMapper.ensureTable();
    List<Map<String, Object>> items = orderMapper.selectByUser(user.getId());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("items", items == null ? List.of() : items);
    return out;
  }

  public Map<String, Object> detail(TianwenH5User user, String orderNo) {
    if (user == null || user.getId() == null) {
      throw new ServiceException("请先登录");
    }
    orderMapper.ensureTable();
    Map<String, Object> order = requireOwnOrder(user, orderNo);
    return orderView(order);
  }

  @Transactional
  public Map<String, Object> confirmPaid(String orderNo, String secret, String channel) {
    String expected = properties.getPaymentNotifySecret();
    if (expected == null || expected.isBlank()) {
      throw new ServiceException("支付回调密钥未配置");
    }
    if (!sameSecret(expected, secret)) {
      throw new ServiceException("支付回调密钥不正确");
    }
    orderMapper.ensureTable();
    Map<String, Object> order = orderMapper.selectByNo(text(orderNo));
    if (order == null) {
      throw new ServiceException("订单不存在");
    }
    if (!"paid".equals(text(order.get("status")))) {
      int updated = orderMapper.markPaid(text(order.get("order_no")), channelText(channel));
      if (updated == 1) {
        grant(order);
      }
      order = orderMapper.selectByNo(text(order.get("order_no")));
    }
    return orderView(order);
  }

  private void grant(Map<String, Object> order) {
    if ("unlock".equals(text(order.get("product_type")))) {
      unlockMapper.insertIgnore(
          longValue(order.get("user_id")),
          text(order.get("recommendation_id")),
          "paid",
          decimal(order.get("amount")));
      return;
    }
    if ("vip".equals(text(order.get("product_type")))) {
      long userId = longValue(order.get("user_id"));
      TianwenH5User user = userMapper.selectById(userId);
      LocalDateTime now = LocalDateTime.now();
      LocalDateTime start = now;
      if (user != null && user.getVipExpireAt() != null && user.getVipExpireAt().isAfter(now)) {
        start = user.getVipExpireAt();
      }
      userMapper.updateVip(userId, intValue(order.get("vip_level")), now, start.plusDays(30));
    }
  }

  private Map<String, Object> requireOwnOrder(TianwenH5User user, String orderNo) {
    Map<String, Object> order = orderMapper.selectByNo(text(orderNo));
    if (order == null || longValue(order.get("user_id")) != user.getId()) {
      throw new ServiceException("订单不存在");
    }
    return order;
  }

  private Map<String, Object> orderView(Map<String, Object> order) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("orderNo", order.get("order_no"));
    view.put("productType", order.get("product_type"));
    view.put("recommendationId", order.get("recommendation_id"));
    view.put("vipLevel", order.get("vip_level"));
    view.put("title", order.get("title"));
    view.put("amount", order.get("amount"));
    view.put("status", order.get("status"));
    view.put("payChannel", order.get("pay_channel"));
    view.put("createdAt", order.get("created_at"));
    view.put("paidAt", order.get("paid_at"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("order", view);
    return out;
  }

  private String newOrderNo() {
    int suffix = ThreadLocalRandom.current().nextInt(10000);
    return LocalDateTime.now().format(ORDER_TIME) + String.format("%04d", suffix);
  }

  private static boolean sameSecret(String expected, String actual) {
    String left = expected == null ? "" : expected.trim();
    String right = actual == null ? "" : actual.trim();
    if (left.isEmpty()) {
      return false;
    }
    return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private static String channelText(String channel) {
    String value = text(channel);
    if (value.isEmpty() || "null".equals(value)) {
      return "callback";
    }
    return value.length() > 20 ? value.substring(0, 20) : value;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static int intValue(Object value) {
    try {
      return Integer.parseInt(text(value));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static long longValue(Object value) {
    try {
      return Long.parseLong(text(value));
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static BigDecimal decimal(Object value) {
    try {
      return new BigDecimal(text(value));
    } catch (Exception e) {
      return BigDecimal.ZERO;
    }
  }

  private enum VipPlan {
    VIP(1, "VIP会员", "98.00"),
    SVIP(2, "SVIP会员", "298.00"),
    SUPREME(3, "至尊VIP", "998.00");

    final int level;
    final String title;
    final BigDecimal amount;

    VipPlan(int level, String title, String amount) {
      this.level = level;
      this.title = title;
      this.amount = new BigDecimal(amount);
    }

    static VipPlan of(int level) {
      for (VipPlan plan : values()) {
        if (plan.level == level) {
          return plan;
        }
      }
      throw new ServiceException("vipLevel只支持 1、2、3");
    }
  }
}
