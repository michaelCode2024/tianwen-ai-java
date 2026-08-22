package com.ruoyi.tianwen.service.impl;

import com.ruoyi.tianwen.domain.*;
import com.ruoyi.tianwen.service.ITianwenAccessPolicyService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TianwenAccessPolicyServiceImpl implements ITianwenAccessPolicyService {
  private static final Set<String> SETTLED = Set.of("1", "2", "3", "4", "5");

  public TianwenAccessResult check(TianwenH5User user, Map<String, Object> rec, boolean unlocked) {
    String status = text(rec.get("result_status")), latest = def(rec.get("is_latest"), "1");
    BigDecimal rate = decimal(rec.get("rate"));
    TianwenVipLevel raw = TianwenVipLevel.of(user == null ? null : user.getVipLevel());
    boolean active = isVipActive(user);
    TianwenVipLevel level = active ? raw : TianwenVipLevel.NORMAL;
    if (SETTLED.contains(status))
      return allow("settled_public", "已结算推荐公开查看", user, level, active, null, rate);
    if ("9".equals(status) || "0".equals(latest))
      return allow("history_public", "历史推荐公开查看", user, level, active, null, rate);
    if (unlocked) return allow("manual_unlock", "本场已单独解锁", user, level, active, null, rate);
    if (level == TianwenVipLevel.SUPREME)
      return allow("supreme_vip", "至尊VIP全部推荐可见", user, level, true, null, rate);
    if (level != TianwenVipLevel.NORMAL
        && level.getMaxFreeRate() != null
        && rate != null
        && rate.compareTo(level.getMaxFreeRate()) <= 0)
      return allow(
          level == TianwenVipLevel.VIP ? "vip" : "svip",
          level.getLabel() + "权限内直接查看",
          user,
          level,
          true,
          null,
          rate);
    return new TianwenAccessResult(
        false,
        "unlock_required",
        "当前推荐需要解锁或升级会员",
        level,
        active,
        TianwenVipLevel.requiredForRate(rate),
        rate,
        user == null ? null : user.getVipExpireAt());
  }

  private boolean isVipActive(TianwenH5User u) {
    if (u == null) return false;
    if (TianwenVipLevel.of(u.getVipLevel()) == TianwenVipLevel.NORMAL) return false;
    LocalDateTime e = u.getVipExpireAt();
    return e == null || e.isAfter(LocalDateTime.now());
  }

  private TianwenAccessResult allow(
      String t,
      String r,
      TianwenH5User u,
      TianwenVipLevel l,
      boolean a,
      TianwenVipLevel req,
      BigDecimal rate) {
    return new TianwenAccessResult(
        true, t, r, l, a, req, rate, u == null ? null : u.getVipExpireAt());
  }

  private BigDecimal decimal(Object v) {
    try {
      return v == null ? null : new BigDecimal(String.valueOf(v));
    } catch (Exception e) {
      return null;
    }
  }

  private String text(Object v) {
    return v == null ? "" : String.valueOf(v);
  }

  private String def(Object v, String d) {
    String s = text(v);
    return s.isEmpty() ? d : s;
  }
}
