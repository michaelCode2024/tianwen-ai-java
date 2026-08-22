package com.ruoyi.tianwen.service;

import com.ruoyi.tianwen.domain.TianwenH5User;
import java.math.BigDecimal;
import java.util.Map;

public interface ITianwenUserService {
    Map<String,Object> register(String phone, String password);
    Map<String,Object> login(String phone, String password);
    TianwenH5User currentUser(String bearerToken);
    Map<String,Object> me(String bearerToken);
    Map<String,Object> checkin(String bearerToken);
    Map<String,Object> unlock(String bearerToken, String recommendationId, String unlockType);
    Map<String,Object> adminUsers(String q, int page, int pageSize);
    Map<String,Object> adminAdjust(Long userId, BigDecimal balanceDelta, Integer freeViewsDelta, String remark, String adminUsername);
}
