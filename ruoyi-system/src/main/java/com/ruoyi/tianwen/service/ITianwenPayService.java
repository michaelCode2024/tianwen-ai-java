package com.ruoyi.tianwen.service;

import com.ruoyi.tianwen.dto.CreatePayOrderRequest;
import java.util.Map;

public interface ITianwenPayService {

    Map<String, Object> getPayConfig();

    Map<String, Object> createOrder(String bearerToken, CreatePayOrderRequest request, String requestOrigin);

    Map<String, Object> getOrder(String bearerToken, String mchOrderNo, boolean sync, String lang);

    Map<String, Object> listOrders(String bearerToken, String tab);

    String handleNexaVaultWebhook(Map<String, Object> payload);
}
