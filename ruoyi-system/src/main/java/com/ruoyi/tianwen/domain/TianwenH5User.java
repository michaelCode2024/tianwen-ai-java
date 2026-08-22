package com.ruoyi.tianwen.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * H5用户。
 *
 * V17新增：
 * vipLevel    0普通 1VIP 2SVIP 3至尊VIP
 * vipStartAt  会员开始时间
 * vipExpireAt 会员到期时间；level>0且NULL时视为永久会员
 */
@Data
public class TianwenH5User {

    private Long id;
    private String phone;
    private String passwordHash;
    private String salt;
    private BigDecimal balance;
    private Integer freeViewsRemaining;

    private Integer vipLevel;
    private LocalDateTime vipStartAt;
    private LocalDateTime vipExpireAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
