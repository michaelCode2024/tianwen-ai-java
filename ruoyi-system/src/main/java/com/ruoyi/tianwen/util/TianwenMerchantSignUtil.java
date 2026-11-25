package com.ruoyi.tianwen.util;

import com.alibaba.fastjson2.JSON;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * NexaVault 商户网关 HMAC-SHA256 签名与验签工具（与 nexavault-wallet MerchantSignUtil 保持一致）。
 */
public final class TianwenMerchantSignUtil {

    private static final Pattern HEX_SHA256 = Pattern.compile("^[0-9a-f]{64}$", Pattern.CASE_INSENSITIVE);

    private TianwenMerchantSignUtil() {}

    public static String buildSignString(Map<String, ?> params, String timestamp, String nonce) {
        List<String> pairs = new ArrayList<>();
        if (params != null && !params.isEmpty()) {
            TreeSet<String> sortedKeys = new TreeSet<>(params.keySet());
            for (String key : sortedKeys) {
                if ("sign".equals(key) || "signature".equals(key)) {
                    continue;
                }
                Object val = params.get(key);
                if (val == null) {
                    continue;
                }
                String strVal;
                if (val instanceof Map<?, ?> || val instanceof Collection<?> || val.getClass().isArray()) {
                    strVal = JSON.toJSONString(val);
                } else {
                    strVal = String.valueOf(val);
                }
                if (strVal.trim().isEmpty()) {
                    continue;
                }
                pairs.add(key + "=" + strVal);
            }
        }
        if (timestamp != null && !timestamp.trim().isEmpty()) {
            pairs.add("timestamp=" + timestamp.trim());
        }
        if (nonce != null && !nonce.trim().isEmpty()) {
            pairs.add("nonce=" + nonce.trim());
        }
        return String.join("&", pairs);
    }

    public static String sign(String payloadString, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] raw = mac.doFinal(payloadString.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 签名计算失败", e);
        }
    }

    public static boolean verify(String payloadString, String secret, String expectedSign) {
        if (payloadString == null || payloadString.isEmpty() || secret == null || secret.isEmpty() || expectedSign == null) {
            return false;
        }
        String cleanExpected = expectedSign.trim().toLowerCase();
        if (!HEX_SHA256.matcher(cleanExpected).matches()) {
            return false;
        }
        String calculated = sign(payloadString, secret);
        return MessageDigest.isEqual(
                calculated.getBytes(StandardCharsets.UTF_8),
                cleanExpected.getBytes(StandardCharsets.UTF_8)
        );
    }
}
