package com.ruoyi.tianwen.dto;

import lombok.Data;

@Data
public class CreatePayOrderRequest {
    private String planCode;
    private String recommendationId;
    private String chainKey;
    private String coin;
    private String lang;
}
