package com.ruoyi.web.controller.tianwen;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.tianwen.config.TianwenAiProperties;
import com.ruoyi.tianwen.domain.TianwenH5User;
import com.ruoyi.tianwen.domain.TianwenVipLevel;
import com.ruoyi.tianwen.dto.AuthRequest;
import com.ruoyi.tianwen.dto.UnlockRequest;
import com.ruoyi.tianwen.service.ITianwenRecommendationService;
import com.ruoyi.tianwen.service.ITianwenUserService;
import com.ruoyi.tianwen.service.impl.TianwenOrderService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@Anonymous
@RestController
@RequestMapping("/api/h5")
public class TianwenH5Controller {

  private final ITianwenUserService userService;
  private final ITianwenRecommendationService recommendationService;
  private final TianwenOrderService orderService;
  private final TianwenAiProperties properties;

  public TianwenH5Controller(
      ITianwenUserService userService,
      ITianwenRecommendationService recommendationService,
      TianwenOrderService orderService,
      TianwenAiProperties properties) {
    this.userService = userService;
    this.recommendationService = recommendationService;
    this.orderService = orderService;
    this.properties = properties;
  }

  @GetMapping("/me")
  public Map<String, Object> me(HttpServletRequest request) {
    return userService.me(readToken(request));
  }

  /**
   * 首页极速接口： 以前首页要请求： 1. /me 2. /recommendations?model=jiebao 3. /recommendations?model=nami
   *
   * <p>现在首页只请求本接口一次。
   */
  @GetMapping("/home/bootstrap")
  public Map<String, Object> homeBootstrap(
      @RequestParam(defaultValue = "90") Double minRate,
      @RequestParam(defaultValue = "24") Integer limit,
      HttpServletRequest request) {

    long start = System.currentTimeMillis();

    String token = readToken(request);
    TianwenH5User user = userService.currentUser(token);

    Map<String, String> q = new LinkedHashMap<>();
    q.put("min_rate", String.valueOf(minRate == null ? 90D : minRate));
    q.put("limit", String.valueOf(limit == null ? 24 : Math.min(Math.max(limit, 1), 50)));

    Map<String, Object> rec = recommendationService.homeLatest(q, user);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("user", user == null ? null : publicUser(user));

    // 首页首屏不强制查询签到详情，避免 /me 的额外两次 checkin SQL。
    // 用户进入“我的/签到”页面时再调用 /me。
    out.put("items", rec.get("items"));
    out.put("count", rec.get("count"));
    out.put("auth", rec.get("auth"));
    out.put("source", rec.get("source"));
    out.put("costMs", System.currentTimeMillis() - start);
    return out;
  }

  @PostMapping("/auth/register")
  public Map<String, Object> register(@RequestBody AuthRequest body) {
    return userService.register(body.getPhone(), body.getPassword());
  }

  @PostMapping("/auth/login")
  public Map<String, Object> login(@RequestBody AuthRequest body) {
    return userService.login(body.getPhone(), body.getPassword());
  }

  @GetMapping("/recommendations/stats")
  public Map<String, Object> recommendationStats(@RequestParam Map<String, String> query) {
    return recommendationService.stats(query);
  }

  @GetMapping("/recommendations")
  public Map<String, Object> recommendations(
      @RequestParam Map<String, String> query, HttpServletRequest request) {

    TianwenH5User user = userService.currentUser(readToken(request));
    return recommendationService.list(query, user);
  }

  @GetMapping("/home/recommendations")
  public Map<String, Object> homeRecommendations(
      @RequestParam Map<String, String> query, HttpServletRequest request) {

    TianwenH5User user = userService.currentUser(readToken(request));
    return recommendationService.homeLatest(query, user);
  }

  @GetMapping("/predictions")
  public Map<String, Object> predictions(
      @RequestParam(required = false) String date,
      @RequestParam(defaultValue = "jiebao") String model,
      HttpServletRequest request) {
    TianwenH5User user = userService.currentUser(readToken(request));
    return recommendationService.predictions(date, model, user);
  }

  @GetMapping("/recommendations/{id}")
  public Map<String, Object> recommendationDetail(
      @PathVariable String id,
      @RequestParam Map<String, String> query,
      HttpServletRequest request) {

    TianwenH5User user = userService.currentUser(readToken(request));
    return recommendationService.detail(id, query, user);
  }

  @PostMapping("/checkin")
  public Map<String, Object> checkin(HttpServletRequest request) {
    return userService.checkin(readToken(request));
  }

  @PostMapping("/unlock")
  public Map<String, Object> unlock(@RequestBody UnlockRequest body, HttpServletRequest request) {

    return userService.unlock(readToken(request), body.getRecommendationId(), body.getUnlockType());
  }

  @PostMapping("/orders")
  public Map<String, Object> createOrder(
      @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return orderService.create(userService.currentUser(readToken(request)), body);
  }

  @GetMapping("/orders")
  public Map<String, Object> orders(HttpServletRequest request) {
    return orderService.list(userService.currentUser(readToken(request)));
  }

  @GetMapping("/orders/{orderNo}")
  public Map<String, Object> order(@PathVariable String orderNo, HttpServletRequest request) {
    return orderService.detail(userService.currentUser(readToken(request)), orderNo);
  }

  @PostMapping("/orders/{orderNo}/notify")
  public Map<String, Object> confirmOrder(
      @PathVariable String orderNo,
      @RequestHeader(value = "X-Tianwen-Notify-Secret", required = false) String secret,
      @RequestBody(required = false) Map<String, Object> body) {
    String channel = body == null ? "callback" : String.valueOf(body.getOrDefault("channel", "callback"));
    return orderService.confirmPaid(orderNo, secret, channel);
  }

  @GetMapping("/export/predictions")
  public Map<String, Object> exportPredictions(
      @RequestParam(required = false) String date,
      @RequestParam(defaultValue = "jiebao") String model,
      @RequestHeader(value = "X-Tianwen-Export-Key", required = false) String key) {
    String expected = properties.getExportKey();
    if (expected == null || expected.isBlank()) {
      throw new ServiceException("导出密钥未配置");
    }
    if (!sameSecret(expected, key)) {
      throw new ServiceException("导出密钥不正确");
    }
    return recommendationService.exportPredictions(date, model);
  }

  private static boolean sameSecret(String expected, String actual) {
    String left = expected == null ? "" : expected.trim();
    String right = actual == null ? "" : actual.trim();
    if (left.isEmpty()) {
      return false;
    }
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private String readToken(HttpServletRequest request) {
    String token = request.getHeader("X-Tianwen-Token");
    if (token != null && !token.trim().isEmpty()) {
      return token.trim();
    }

    String authorization = request.getHeader("Authorization");
    if (authorization == null || authorization.trim().isEmpty()) {
      return "";
    }

    authorization = authorization.trim();
    if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return authorization.substring(7).trim();
    }

    return authorization;
  }

  private Map<String, Object> publicUser(TianwenH5User user) {
    if (user == null) {
      return null;
    }

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", user.getId());
    m.put("phone", user.getPhone());
    m.put("balance", user.getBalance());
    m.put(
        "freeViewsRemaining",
        user.getFreeViewsRemaining() == null ? 0 : user.getFreeViewsRemaining());

    TianwenVipLevel rawLevel = TianwenVipLevel.of(user.getVipLevel());

    boolean vipActive = isVipActive(user, rawLevel);
    TianwenVipLevel effective = vipActive ? rawLevel : TianwenVipLevel.NORMAL;

    m.put("vipLevel", effective.getLevel());
    m.put("vipName", effective.getLabel());
    m.put("vipActive", vipActive);
    m.put("vipStartAt", user.getVipStartAt());
    m.put("vipExpireAt", user.getVipExpireAt());
    m.put("vipMaxFreeRate", effective.getMaxFreeRate());
    m.put("vipAllAccess", effective.isAllAccess());

    // 便于排查数据库映射/缓存问题
    m.put("rawVipLevel", rawLevel.getLevel());
    m.put("rawVipName", rawLevel.getLabel());

    return m;
  }

  private boolean isVipActive(TianwenH5User user, TianwenVipLevel level) {

    if (user == null || level == null || level == TianwenVipLevel.NORMAL) {
      return false;
    }

    LocalDateTime expireAt = user.getVipExpireAt();
    return expireAt == null || expireAt.isAfter(LocalDateTime.now());
  }
}
