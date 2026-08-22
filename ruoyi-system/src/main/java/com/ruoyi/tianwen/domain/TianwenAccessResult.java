package com.ruoyi.tianwen.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

public class TianwenAccessResult {
  private final boolean allowed;
  private final String accessType, reason;
  private final TianwenVipLevel vipLevel, requiredVipLevel;
  private final boolean vipActive;
  private final BigDecimal rate;
  private final LocalDateTime vipExpireAt;

  public TianwenAccessResult(
      boolean a,
      String t,
      String r,
      TianwenVipLevel v,
      boolean active,
      TianwenVipLevel req,
      BigDecimal rate,
      LocalDateTime exp) {
    this.allowed = a;
    this.accessType = t;
    this.reason = r;
    this.vipLevel = v == null ? TianwenVipLevel.NORMAL : v;
    this.vipActive = active;
    this.requiredVipLevel = req;
    this.rate = rate;
    this.vipExpireAt = exp;
  }

  public boolean isAllowed() {
    return allowed;
  }

  public String getAccessType() {
    return accessType;
  }

  public String getReason() {
    return reason;
  }

  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("allowed", allowed);
    m.put("accessType", accessType);
    m.put("reason", reason);
    m.put("vipLevel", vipLevel.getLevel());
    m.put("vipName", vipLevel.getLabel());
    m.put("vipActive", vipActive);
    m.put("requiredVipLevel", requiredVipLevel == null ? null : requiredVipLevel.getLevel());
    m.put("requiredVipName", requiredVipLevel == null ? null : requiredVipLevel.getLabel());
    m.put("rate", rate);
    m.put("vipExpireAt", vipExpireAt);
    return m;
  }
}
