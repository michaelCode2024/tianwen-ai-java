package com.ruoyi.tianwen.util;

import java.security.SecureRandom;
import java.util.Base64;

public final class TianwenTokenUtil {
    private static final SecureRandom RANDOM = new SecureRandom();
    private TianwenTokenUtil() {}
    public static String createToken() {
        byte[] bytes = new byte[40];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
