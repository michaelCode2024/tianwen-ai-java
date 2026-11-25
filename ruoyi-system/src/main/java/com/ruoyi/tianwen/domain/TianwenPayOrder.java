package com.ruoyi.tianwen.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class TianwenPayOrder {
    private Long id;
    private String mchOrderNo;
    private String gatewayOrderNo;
    private Long userId;
    private String orderType;
    private String planCode;
    private String title;
    private String recommendationId;
    private Integer vipLevel;
    private Integer vipDays;
    private BigDecimal usdAmount;
    private BigDecimal cnyAmount;
    private String chainKey;
    private String coin;
    private String expectedAmount;
    private String actualAmount;
    private String payAddress;
    private String qrContent;
    private String deepLink;
    private String checkoutUrl;
    private String txHash;
    private String status;
    private Integer fulfilled;
    private Long expireAt;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
