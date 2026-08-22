package com.ruoyi.tianwen.dto;
import java.math.BigDecimal;
public class AdminAdjustUserRequest {
    private Long userId;
    private BigDecimal balanceDelta;
    private Integer freeViewsDelta;
    private String remark;
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getBalanceDelta() { return balanceDelta; }
    public void setBalanceDelta(BigDecimal balanceDelta) { this.balanceDelta = balanceDelta; }
    public Integer getFreeViewsDelta() { return freeViewsDelta; }
    public void setFreeViewsDelta(Integer freeViewsDelta) { this.freeViewsDelta = freeViewsDelta; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
