package com.ruoyi.tianwen.domain;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getSalt() { return salt; }
    public void setSalt(String salt) { this.salt = salt; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public Integer getFreeViewsRemaining() { return freeViewsRemaining; }
    public void setFreeViewsRemaining(Integer freeViewsRemaining) { this.freeViewsRemaining = freeViewsRemaining; }
    public Integer getVipLevel() { return vipLevel; }
    public void setVipLevel(Integer vipLevel) { this.vipLevel = vipLevel; }
    public LocalDateTime getVipStartAt() { return vipStartAt; }
    public void setVipStartAt(LocalDateTime vipStartAt) { this.vipStartAt = vipStartAt; }
    public LocalDateTime getVipExpireAt() { return vipExpireAt; }
    public void setVipExpireAt(LocalDateTime vipExpireAt) { this.vipExpireAt = vipExpireAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
