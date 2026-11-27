package com.ruoyi.tianwen.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.tianwen.config.TianwenPayProperties;
import com.ruoyi.tianwen.domain.TianwenH5User;
import com.ruoyi.tianwen.domain.TianwenPayOrder;
import com.ruoyi.tianwen.domain.TianwenVipLevel;
import com.ruoyi.tianwen.dto.CreatePayOrderRequest;
import com.ruoyi.tianwen.mapper.TianwenH5UserMapper;
import com.ruoyi.tianwen.mapper.TianwenPayOrderMapper;
import com.ruoyi.tianwen.mapper.TianwenUnlockMapper;
import com.ruoyi.tianwen.service.ITianwenPayService;
import com.ruoyi.tianwen.service.ITianwenUserService;
import com.ruoyi.tianwen.util.TianwenMerchantSignUtil;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TianwenPayServiceImpl implements ITianwenPayService {

    private static final Logger log = LoggerFactory.getLogger(TianwenPayServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter ORDER_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Set<String> ALLOWED_CHAINS = Set.of(
            "auto", "tron", "arb", "bsc", "base", "polygon", "solana", "eth", "op"
    );
    private static final Set<String> ALLOWED_COINS = Set.of("USDT", "USDC");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final TianwenPayProperties payProperties;
    private final TianwenPayOrderMapper payOrderMapper;
    private final TianwenH5UserMapper userMapper;
    private final TianwenUnlockMapper unlockMapper;
    private final ITianwenUserService userService;

    public TianwenPayServiceImpl(
            TianwenPayProperties payProperties,
            TianwenPayOrderMapper payOrderMapper,
            TianwenH5UserMapper userMapper,
            TianwenUnlockMapper unlockMapper,
            ITianwenUserService userService) {
        this.payProperties = payProperties;
        this.payOrderMapper = payOrderMapper;
        this.userMapper = userMapper;
        this.unlockMapper = unlockMapper;
        this.userService = userService;
    }

    @PostConstruct
    public void initTable() {
        try {
            payOrderMapper.ensureTable();
        } catch (Exception e) {
            log.warn("自动检查或创建 tw_h5_pay_order 表失败，稍后可在数据库手动执行 SQL: {}", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getPayConfig() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("gatewayReady", isMerchantConfigured());
        out.put("defaultChain", normalizeChain(payProperties.getDefaultChain()));
        out.put("defaultCoin", normalizeCoin(payProperties.getDefaultCoin()));
        out.put("coins", List.of("USDT", "USDC"));

        List<Map<String, Object>> chains = new ArrayList<>();
        chains.add(chainItem("auto", "收银台自选网络", "Select on Checkout", "推荐 · 多链支持", "Recommended · Multi-Chain"));
        chains.add(chainItem("tron", "TRON (TRC-20)", "TRON (TRC-20)", "主流 USDT 网络", "Global USDT Standard"));
        chains.add(chainItem("arb", "Arbitrum One", "Arbitrum One", "极速到账 · 低矿工费", "Fast · ~$0.02 Gas"));
        chains.add(chainItem("bsc", "BNB Smart Chain (BEP-20)", "BNB Smart Chain (BEP-20)", "低手续费 · 广泛支持", "Low Gas · BEP-20"));
        chains.add(chainItem("base", "Base", "Base", "L2 极速确认", "L2 Fast Finality"));
        chains.add(chainItem("polygon", "Polygon", "Polygon", "低手续费 EVM", "Low Fee EVM"));
        chains.add(chainItem("solana", "Solana (SPL)", "Solana (SPL)", "秒级确认", "<1s Finality"));
        out.put("chains", chains);

        Map<String, Object> plans = new LinkedHashMap<>();
        plans.put("unlock", planItem("unlock", "unlock", 0, 0,
                payProperties.getUnlockUsdAmount(), payProperties.getUnlockCnyAmount(),
                "单场AI推荐解锁", "Single Match AI Unlock"));
        plans.put("vip", planItem("vip", "member", 1, payProperties.getVipDays(),
                payProperties.getVipUsdAmount(), payProperties.getVipCnyAmount(),
                "VIP会员（月卡）", "VIP Membership (30 Days)"));
        plans.put("svip", planItem("svip", "member", 2, payProperties.getSvipDays(),
                payProperties.getSvipUsdAmount(), payProperties.getSvipCnyAmount(),
                "SVIP会员（月卡）", "SVIP Membership (30 Days)"));
        plans.put("king", planItem("king", "member", 3, payProperties.getSupremeDays(),
                payProperties.getSupremeUsdAmount(), payProperties.getSupremeCnyAmount(),
                "至尊VIP（月卡）", "Supreme VIP (30 Days)"));
        out.put("plans", plans);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> createOrder(String bearerToken, CreatePayOrderRequest req, String requestOrigin) {
        TianwenH5User user = userService.currentUser(bearerToken);
        if (user == null) {
            throw new ServiceException("请先登录 / Please sign in first");
        }
        if (!isMerchantConfigured()) {
            String msg = "en".equals(normalizeLang(req == null ? null : req.getLang()))
                    ? "Crypto checkout is temporarily under maintenance, please try again later"
                    : "U钱包收银通道维护升级中，请稍后再试或使用免费解锁次数";
            throw new ServiceException(msg);
        }

        String lang = normalizeLang(req == null ? null : req.getLang());
        String planCode = req == null || req.getPlanCode() == null
                ? ""
                : req.getPlanCode().trim().toLowerCase(Locale.ROOT);
        String chainKey = normalizeChain(req == null ? null : req.getChainKey());
        String coin = normalizeCoin(req == null ? null : req.getCoin());

        PlanSpec spec = resolvePlanSpec(planCode, req == null ? null : req.getRecommendationId(), user, lang);
        String mchOrderNo = generateMchOrderNo();

        TianwenPayOrder order = new TianwenPayOrder();
        order.setMchOrderNo(mchOrderNo);
        order.setUserId(user.getId());
        order.setOrderType(spec.orderType);
        order.setPlanCode(spec.planCode);
        order.setTitle(spec.title);
        order.setRecommendationId(spec.recommendationId);
        order.setVipLevel(spec.vipLevel);
        order.setVipDays(spec.vipDays);
        order.setUsdAmount(spec.usdAmount);
        order.setCnyAmount(spec.cnyAmount);
        order.setChainKey(chainKey);
        order.setCoin(coin);
        order.setExpectedAmount(formatDecimal(spec.usdAmount));
        order.setStatus("PENDING");
        order.setFulfilled(0);
        order.setExpireAt(System.currentTimeMillis() + Math.max(300, payProperties.getExpireSeconds()) * 1000L);

        payOrderMapper.insert(order);

        String notifyUrl = resolveNotifyUrl(requestOrigin);
        String returnUrl = resolveReturnUrl(requestOrigin, mchOrderNo, lang);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mchOrderNo", mchOrderNo);
        payload.put("userId", String.valueOf(user.getId()));
        payload.put("chainKey", chainKey);
        payload.put("coin", coin);
        payload.put("amount", formatDecimal(spec.usdAmount));
        payload.put("cnyAmount", formatDecimal(spec.cnyAmount));
        payload.put("title", spec.title);
        payload.put("allowChainSwitch", Boolean.TRUE);
        payload.put("expireSeconds", Math.max(300, payProperties.getExpireSeconds()));
        if (!notifyUrl.isEmpty()) {
            payload.put("notifyUrl", notifyUrl);
        }
        if (!returnUrl.isEmpty()) {
            payload.put("returnUrl", returnUrl);
        }

        JSONObject gwData = callGatewayPost("/api/v1/merchant/deposit/create", payload);

        order.setGatewayOrderNo(trimToNull(gwData.getString("orderNo")));
        order.setChainKey(nonEmptyOrDefault(gwData.getString("chainKey"), chainKey));
        order.setCoin(nonEmptyOrDefault(gwData.getString("coin"), coin));
        order.setExpectedAmount(nonEmptyOrDefault(gwData.getString("amount"), formatDecimal(spec.usdAmount)));
        order.setPayAddress(trimToNull(gwData.getString("payAddress")));
        order.setQrContent(trimToNull(gwData.getString("qrContent")));
        order.setDeepLink(trimToNull(gwData.getString("deepLink")));

        String rawCheckoutUrl = trimToEmpty(gwData.getString("checkoutUrl"));
        if (rawCheckoutUrl.startsWith("/")) {
            rawCheckoutUrl = trimSlash(payProperties.getGatewayBaseUrl()) + rawCheckoutUrl;
        }
        order.setCheckoutUrl(rawCheckoutUrl.isEmpty() ? null : rawCheckoutUrl);
        Long gwExpire = gwData.getLong("expireAt");
        if (gwExpire != null && gwExpire > 0) {
            order.setExpireAt(gwExpire);
        }
        String gwStatus = trimToEmpty(gwData.getString("status"));
        if (!gwStatus.isEmpty()) {
            order.setStatus(gwStatus);
        }

        payOrderMapper.updateGatewayInfo(order);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("msg", "en".equals(lang) ? "Order created" : "订单创建成功");
        out.put("order", formatOrderDto(order, lang));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> getOrder(String bearerToken, String mchOrderNo, boolean sync, String lang) {
        TianwenH5User user = userService.currentUser(bearerToken);
        if (user == null) {
            throw new ServiceException("请先登录 / Please sign in first");
        }
        String cleanNo = trimToEmpty(mchOrderNo);
        if (cleanNo.isEmpty()) {
            throw new ServiceException("订单号不能为空 / Missing order number");
        }

        TianwenPayOrder order = payOrderMapper.selectByMchOrderNo(cleanNo);
        if (order == null || !user.getId().equals(order.getUserId())) {
            throw new ServiceException("订单不存在 / Order not found");
        }

        if (sync && "PENDING".equalsIgnoreCase(order.getStatus()) && isMerchantConfigured()) {
            syncOrderWithGateway(order);
            order = payOrderMapper.selectByMchOrderNo(cleanNo);
        } else if ("PENDING".equalsIgnoreCase(order.getStatus())
                && order.getExpireAt() != null
                && System.currentTimeMillis() > order.getExpireAt()) {
            payOrderMapper.updateStatus(cleanNo, "EXPIRED");
            order.setStatus("EXPIRED");
        }

        TianwenH5User freshUser = userMapper.selectById(user.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("order", formatOrderDto(order, normalizeLang(lang)));
        out.put("user", userService.buildPublicUser(freshUser != null ? freshUser : user));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> listOrders(String bearerToken, String tab) {
        TianwenH5User user = userService.currentUser(bearerToken);
        if (user == null) {
            throw new ServiceException("请先登录 / Please sign in first");
        }

        payOrderMapper.expireOverdueOrders(user.getId(), System.currentTimeMillis());

        String group = "all";
        if ("pending".equalsIgnoreCase(tab)) {
            group = "pending";
        } else if ("done".equalsIgnoreCase(tab)) {
            group = "done";
        }

        List<TianwenPayOrder> rows = payOrderMapper.selectByUser(user.getId(), group, 60);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (TianwenPayOrder row : rows) {
            items.add(formatOrderDto(row, "zh"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("items", items);
        return out;
    }

    @Override
    @Transactional
    public String handleNexaVaultWebhook(Map<String, Object> payload) {
        if (!isMerchantConfigured()) {
            throw new ServiceException("商户密钥未配置");
        }
        if (payload == null || payload.isEmpty()) {
            throw new ServiceException("空回调负载");
        }

        String sign = trimToEmpty(payload.get("sign") == null ? null : String.valueOf(payload.get("sign")));
        String signStr = TianwenMerchantSignUtil.buildSignString(payload, null, null);
        if (!TianwenMerchantSignUtil.verify(signStr, payProperties.getMerchantSecret().trim(), sign)) {
            log.warn("星汇支付 Webhook 签名校验失败: mchOrderNo={}", payload.get("mchOrderNo"));
            throw new ServiceException("签名校验失败");
        }

        String event = trimToEmpty(payload.get("event") == null ? null : String.valueOf(payload.get("event")));
        String mchOrderNo = trimToEmpty(payload.get("mchOrderNo") == null ? null : String.valueOf(payload.get("mchOrderNo")));
        if (mchOrderNo.isEmpty()) {
            throw new ServiceException("缺少 mchOrderNo");
        }

        if ("DEPOSIT_CONFIRMED".equalsIgnoreCase(event)) {
            TianwenPayOrder order = payOrderMapper.selectByMchOrderNo(mchOrderNo);
            if (order == null) {
                log.warn("收到未知订单号的支付回调: mchOrderNo={}", mchOrderNo);
                return "SUCCESS";
            }
            fulfillOrder(
                    order,
                    strVal(payload.get("orderNo")),
                    strVal(payload.get("chainKey")),
                    strVal(payload.get("coin")),
                    strVal(payload.get("actualAmount")),
                    strVal(payload.get("txHash"))
            );
        }
        return "SUCCESS";
    }

    private void syncOrderWithGateway(TianwenPayOrder order) {
        try {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("mchOrderNo", order.getMchOrderNo());
            JSONObject gw = callGatewayGet("/api/v1/merchant/deposit/query", query);
            String remoteStatus = trimToEmpty(gw.getString("status")).toUpperCase(Locale.ROOT);
            if ("PAID".equals(remoteStatus)) {
                fulfillOrder(
                        order,
                        gw.getString("orderNo"),
                        gw.getString("chainKey"),
                        gw.getString("coin"),
                        gw.getString("actualAmount"),
                        gw.getString("txHash")
                );
            } else if ("EXPIRED".equals(remoteStatus) || "FAILED".equals(remoteStatus)) {
                payOrderMapper.updateStatus(order.getMchOrderNo(), remoteStatus);
            }
        } catch (Exception e) {
            log.debug("主动同步星汇订单状态暂未就绪: mchOrderNo={}, reason={}", order.getMchOrderNo(), e.getMessage());
        }
    }

    private void fulfillOrder(
            TianwenPayOrder order,
            String gatewayOrderNo,
            String chainKey,
            String coin,
            String actualAmount,
            String txHash) {

        int updated = payOrderMapper.markPaidAndFulfill(
                order.getMchOrderNo(),
                trimToNull(gatewayOrderNo),
                trimToNull(chainKey),
                trimToNull(coin),
                trimToNull(actualAmount),
                trimToNull(txHash)
        );
        if (updated <= 0) {
            return;
        }

        Long userId = order.getUserId();
        if ("unlock".equalsIgnoreCase(order.getOrderType())) {
            String recId = trimToEmpty(order.getRecommendationId());
            if (!recId.isEmpty()) {
                BigDecimal amt = order.getCnyAmount() != null ? order.getCnyAmount() : order.getUsdAmount();
                unlockMapper.insertIgnore(userId, recId, "paid", amt == null ? BigDecimal.ZERO : amt);
            }
        } else if ("member".equalsIgnoreCase(order.getOrderType())) {
            TianwenH5User user = userMapper.selectById(userId);
            if (user != null) {
                LocalDateTime now = LocalDateTime.now();
                TianwenVipLevel curLevel = TianwenVipLevel.of(user.getVipLevel());
                boolean curActive = curLevel != TianwenVipLevel.NORMAL
                        && (user.getVipExpireAt() == null || user.getVipExpireAt().isAfter(now));

                int buyLevel = order.getVipLevel() == null ? 1 : Math.max(1, order.getVipLevel());
                int targetLevel = curActive ? Math.max(curLevel.getLevel(), buyLevel) : buyLevel;
                int addDays = order.getVipDays() != null && order.getVipDays() > 0 ? order.getVipDays() : 30;

                LocalDateTime startAt = (curActive && user.getVipStartAt() != null) ? user.getVipStartAt() : now;
                LocalDateTime baseExpire = (curActive && user.getVipExpireAt() != null && user.getVipExpireAt().isAfter(now))
                        ? user.getVipExpireAt()
                        : now;
                LocalDateTime newExpire = (curActive && user.getVipExpireAt() == null)
                        ? null
                        : baseExpire.plusDays(addDays);

                userMapper.updateVip(userId, targetLevel, startAt, newExpire);

                try {
                    BigDecimal bal = user.getBalance() == null ? BigDecimal.ZERO : user.getBalance();
                    Integer fv = user.getFreeViewsRemaining() == null ? 0 : user.getFreeViewsRemaining();
                    userMapper.insertAccountLog(
                            userId,
                            BigDecimal.ZERO,
                            0,
                            bal,
                            fv,
                            "NexaVault链上开通会员:" + order.getPlanCode() + "(" + order.getMchOrderNo() + ")",
                            "system_pay"
                    );
                } catch (Exception logErr) {
                    log.debug("写入会员账户日志跳过: {}", logErr.getMessage());
                }
            }
        }

        userService.evictUserCacheById(userId);
        log.info("星汇链上订单履约成功: mchOrderNo={}, userId={}, type={}, plan={}",
                order.getMchOrderNo(), userId, order.getOrderType(), order.getPlanCode());
    }

    private JSONObject callGatewayPost(String path, Map<String, Object> body) {
        String baseUrl = trimSlash(payProperties.getGatewayBaseUrl());
        String mchKey = payProperties.getMerchantKey().trim();
        String secret = payProperties.getMerchantSecret().trim();
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = randomHex(16);
        String signStr = TianwenMerchantSignUtil.buildSignString(body, ts, nonce);
        String signature = TianwenMerchantSignUtil.sign(signStr, secret);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("X-Merchant-Key", mchKey)
                    .header("X-Timestamp", ts)
                    .header("X-Nonce", nonce)
                    .header("X-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JSONObject json = JSON.parseObject(res.body());
            if (res.statusCode() < 200 || res.statusCode() >= 300 || json == null || json.getIntValue("code", -1) != 0) {
                String msg = json != null
                        ? nonEmptyOrDefault(json.getString("message"), json.getString("msg"))
                        : ("HTTP " + res.statusCode());
                throw new ServiceException("创建收银台订单失败: " + (msg.isEmpty() ? res.body() : msg));
            }
            JSONObject data = json.getJSONObject("data");
            if (data == null) {
                throw new ServiceException("收银台返回数据为空");
            }
            return data;
        } catch (ServiceException se) {
            throw se;
        } catch (Exception e) {
            throw new ServiceException("连接星汇收银台异常，请稍后重试: " + e.getMessage());
        }
    }

    private JSONObject callGatewayGet(String path, Map<String, Object> query) {
        String baseUrl = trimSlash(payProperties.getGatewayBaseUrl());
        String mchKey = payProperties.getMerchantKey().trim();
        String secret = payProperties.getMerchantSecret().trim();
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = randomHex(16);
        String signStr = TianwenMerchantSignUtil.buildSignString(query, ts, nonce);
        String signature = TianwenMerchantSignUtil.sign(signStr, secret);

        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            if (entry.getValue() == null) continue;
            if (qs.length() > 0) qs.append('&');
            qs.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        String fullUrl = baseUrl + path + (qs.length() > 0 ? "?" + qs : "");

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/json")
                    .header("X-Merchant-Key", mchKey)
                    .header("X-Timestamp", ts)
                    .header("X-Nonce", nonce)
                    .header("X-Signature", signature)
                    .GET()
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JSONObject json = JSON.parseObject(res.body());
            if (res.statusCode() < 200 || res.statusCode() >= 300 || json == null || json.getIntValue("code", -1) != 0) {
                throw new ServiceException("查询收银台订单状态未成功");
            }
            JSONObject data = json.getJSONObject("data");
            return data != null ? data : new JSONObject();
        } catch (ServiceException se) {
            throw se;
        } catch (Exception e) {
            throw new ServiceException("查询收银台订单异常: " + e.getMessage());
        }
    }

    private PlanSpec resolvePlanSpec(String planCode, String recommendationId, TianwenH5User user, String lang) {
        boolean en = "en".equals(lang);
        switch (planCode) {
            case "unlock": {
                String recId = trimToEmpty(recommendationId);
                if (recId.isEmpty()) {
                    throw new ServiceException(en ? "Missing recommendationId" : "缺少待解锁的推荐ID");
                }
                try {
                    Long.parseLong(recId);
                } catch (NumberFormatException e) {
                    throw new ServiceException(en ? "Invalid recommendationId" : "推荐ID格式错误");
                }
                if (unlockMapper.selectOne(user.getId(), recId) != null) {
                    throw new ServiceException(en ? "This match is already unlocked" : "本场推荐已解锁，无需重复购买");
                }
                return new PlanSpec(
                        "unlock", "unlock",
                        en ? ("Single Match AI Unlock #" + recId) : ("单场AI推荐解锁 #" + recId),
                        recId, 0, 0,
                        parsePositiveDecimal(payProperties.getUnlockUsdAmount(), "1.50"),
                        parsePositiveDecimal(payProperties.getUnlockCnyAmount(), "9.90")
                );
            }
            case "vip":
                return new PlanSpec(
                        "member", "vip",
                        en ? "Tianwen VIP Membership (30 Days)" : "天问 VIP会员（30天）",
                        null, 1, Math.max(1, payProperties.getVipDays()),
                        parsePositiveDecimal(payProperties.getVipUsdAmount(), "13.90"),
                        parsePositiveDecimal(payProperties.getVipCnyAmount(), "98.00")
                );
            case "svip":
                return new PlanSpec(
                        "member", "svip",
                        en ? "Tianwen SVIP Membership (30 Days)" : "天问 SVIP会员（30天）",
                        null, 2, Math.max(1, payProperties.getSvipDays()),
                        parsePositiveDecimal(payProperties.getSvipUsdAmount(), "41.90"),
                        parsePositiveDecimal(payProperties.getSvipCnyAmount(), "298.00")
                );
            case "king":
            case "supreme":
                return new PlanSpec(
                        "member", "king",
                        en ? "Tianwen Supreme VIP (30 Days)" : "天问 至尊VIP（30天）",
                        null, 3, Math.max(1, payProperties.getSupremeDays()),
                        parsePositiveDecimal(payProperties.getSupremeUsdAmount(), "139.00"),
                        parsePositiveDecimal(payProperties.getSupremeCnyAmount(), "998.00")
                );
            default:
                throw new ServiceException(en ? "Unsupported planCode" : "不支持的套餐类型");
        }
    }

    private Map<String, Object> formatOrderDto(TianwenPayOrder o, String lang) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mchOrderNo", o.getMchOrderNo());
        m.put("gatewayOrderNo", o.getGatewayOrderNo());
        m.put("orderType", o.getOrderType());
        m.put("planCode", o.getPlanCode());
        m.put("title", o.getTitle());
        m.put("recommendationId", o.getRecommendationId());
        m.put("vipLevel", o.getVipLevel());
        m.put("vipDays", o.getVipDays());
        m.put("usdAmount", o.getUsdAmount() == null ? null : formatDecimal(o.getUsdAmount()));
        m.put("cnyAmount", o.getCnyAmount() == null ? null : formatDecimal(o.getCnyAmount()));
        m.put("chainKey", o.getChainKey());
        m.put("coin", o.getCoin());
        m.put("expectedAmount", o.getExpectedAmount());
        m.put("actualAmount", o.getActualAmount());
        m.put("payAddress", o.getPayAddress());
        m.put("qrContent", o.getQrContent());
        m.put("deepLink", o.getDeepLink());

        String checkout = trimToEmpty(o.getCheckoutUrl());
        if (!checkout.isEmpty()) {
            String sep = checkout.contains("?") ? "&" : "?";
            checkout = checkout + sep + "lang=" + normalizeLang(lang);
        }
        m.put("checkoutUrl", checkout.isEmpty() ? null : checkout);
        m.put("txHash", o.getTxHash());
        m.put("status", o.getStatus());
        m.put("fulfilled", Integer.valueOf(1).equals(o.getFulfilled()));
        m.put("expireAt", o.getExpireAt());
        m.put("paidAt", o.getPaidAt() == null ? null : o.getPaidAt().format(ISO_FMT));
        m.put("createdAt", o.getCreatedAt() == null ? null : o.getCreatedAt().format(ISO_FMT));
        return m;
    }

    private String resolveNotifyUrl(String requestOrigin) {
        String configured = trimToEmpty(payProperties.getNotifyUrl());
        if (!configured.isEmpty()) {
            return configured;
        }
        String origin = trimSlash(requestOrigin);
        if (origin.isEmpty() || isLocalOrPrivateOrigin(origin)) {
            return "";
        }
        return origin + "/api/h5/pay/webhook/nexavault";
    }

    private String resolveReturnUrl(String requestOrigin, String mchOrderNo, String lang) {
        String base = trimSlash(payProperties.getReturnUrl());
        if (base.isEmpty()) {
            base = trimSlash(requestOrigin);
        }
        if (base.isEmpty()) {
            return "";
        }
        return base + "/#/pages/payment/index?mchOrderNo="
                + URLEncoder.encode(mchOrderNo, StandardCharsets.UTF_8)
                + "&lang=" + normalizeLang(lang);
    }

    private boolean isLocalOrPrivateOrigin(String urlStr) {
        try {
            URI u = URI.create(urlStr);
            String h = u.getHost();
            if (h == null) return true;
            String lower = h.toLowerCase(Locale.ROOT);
            return "localhost".equals(lower)
                    || "127.0.0.1".equals(lower)
                    || lower.startsWith("192.168.")
                    || lower.startsWith("10.")
                    || lower.endsWith(".local");
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isMerchantConfigured() {
        return payProperties.getMerchantKey() != null
                && !payProperties.getMerchantKey().trim().isEmpty()
                && payProperties.getMerchantSecret() != null
                && !payProperties.getMerchantSecret().trim().isEmpty();
    }

    private String normalizeChain(String raw) {
        String c = trimToEmpty(raw).toLowerCase(Locale.ROOT);
        return ALLOWED_CHAINS.contains(c) ? c : "auto";
    }

    private String normalizeCoin(String raw) {
        String c = trimToEmpty(raw).toUpperCase(Locale.ROOT);
        return ALLOWED_COINS.contains(c) ? c : "USDT";
    }

    private String normalizeLang(String raw) {
        String l = trimToEmpty(raw).toLowerCase(Locale.ROOT);
        return l.startsWith("en") ? "en" : "zh";
    }

    private static BigDecimal parsePositiveDecimal(String raw, String fallback) {
        try {
            BigDecimal bd = new BigDecimal(raw == null || raw.trim().isEmpty() ? fallback : raw.trim());
            if (bd.compareTo(BigDecimal.ZERO) > 0) {
                return bd;
            }
        } catch (Exception ignored) {}
        return new BigDecimal(fallback);
    }

    private static String formatDecimal(BigDecimal bd) {
        if (bd == null) return "0";
        BigDecimal stripped = bd.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0, RoundingMode.UNNECESSARY);
        }
        return stripped.toPlainString();
    }

    private static String generateMchOrderNo() {
        return "TW" + LocalDateTime.now().format(ORDER_TS) + randomHex(3).toUpperCase(Locale.ROOT);
    }

    private static String randomHex(int byteLen) {
        byte[] buf = new byte[byteLen];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder(byteLen * 2);
        for (byte b : buf) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static Map<String, Object> chainItem(
            String key, String nameZh, String nameEn, String badgeZh, String badgeEn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("nameZh", nameZh);
        m.put("nameEn", nameEn);
        m.put("badgeZh", badgeZh);
        m.put("badgeEn", badgeEn);
        return m;
    }

    private static Map<String, Object> planItem(
            String code, String type, int vipLevel, int days,
            String usdAmount, String cnyAmount, String nameZh, String nameEn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("type", type);
        m.put("vipLevel", vipLevel);
        m.put("days", days);
        m.put("usdAmount", formatDecimal(parsePositiveDecimal(usdAmount, "9.9")));
        m.put("cnyAmount", formatDecimal(parsePositiveDecimal(cnyAmount, "9.9")));
        m.put("nameZh", nameZh);
        m.put("nameEn", nameEn);
        return m;
    }

    private static String trimSlash(String raw) {
        String s = trimToEmpty(raw);
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String strVal(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String trimToNull(String s) {
        String t = trimToEmpty(s);
        return t.isEmpty() ? null : t;
    }

    private static String nonEmptyOrDefault(String s, String def) {
        String t = trimToEmpty(s);
        return t.isEmpty() ? def : t;
    }

    private static final class PlanSpec {
        final String orderType;
        final String planCode;
        final String title;
        final String recommendationId;
        final int vipLevel;
        final int vipDays;
        final BigDecimal usdAmount;
        final BigDecimal cnyAmount;

        PlanSpec(
                String orderType, String planCode, String title, String recommendationId,
                int vipLevel, int vipDays, BigDecimal usdAmount, BigDecimal cnyAmount) {
            this.orderType = orderType;
            this.planCode = planCode;
            this.title = title;
            this.recommendationId = recommendationId;
            this.vipLevel = vipLevel;
            this.vipDays = vipDays;
            this.usdAmount = usdAmount;
            this.cnyAmount = cnyAmount;
        }
    }
}
