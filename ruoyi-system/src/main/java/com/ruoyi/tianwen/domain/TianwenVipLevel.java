package com.ruoyi.tianwen.domain;

import java.math.BigDecimal;

public enum TianwenVipLevel {
  NORMAL(0, "普通用户", null, false),

  VIP(1, "VIP会员", new BigDecimal("93.00"), false),

  SVIP(2, "SVIP会员", new BigDecimal("95.00"), false),

  SUPREME(3, "至尊VIP", null, true);

  /** 会员等级 0 普通 1 VIP 2 SVIP 3 至尊VIP */
  private final int level;

  /** 显示名称 */
  private final String label;

  /**
   * 当前等级可免费看到的最高推荐度
   *
   * <p>VIP <= 93 SVIP <= 95 至尊VIP不需要这个字段
   */
  private final BigDecimal maxFreeRate;

  /**
   * 是否拥有全部推荐查看权限
   *
   * <p>至尊VIP = true
   */
  private final boolean allAccess;

  TianwenVipLevel(int level, String label, BigDecimal maxFreeRate, boolean allAccess) {

    this.level = level;
    this.label = label;
    this.maxFreeRate = maxFreeRate;
    this.allAccess = allAccess;
  }

  /**
   * 数据库 vip_level 转枚举
   *
   * <p>null统一当普通用户，防止自动拆箱NPE
   */
  public static TianwenVipLevel of(Integer level) {

    int value = level == null ? 0 : level;

    for (TianwenVipLevel item : values()) {

      if (item.level == value) {
        return item;
      }
    }

    return NORMAL;
  }

  /** 根据推荐度判断至少需要什么会员等级 */
  public static TianwenVipLevel requiredForRate(BigDecimal rate) {

    if (rate == null) {
      return SUPREME;
    }

    if (rate.compareTo(new BigDecimal("93.00")) <= 0) {

      return VIP;
    }

    if (rate.compareTo(new BigDecimal("95.00")) <= 0) {

      return SVIP;
    }

    return SUPREME;
  }

  public int getLevel() {
    return level;
  }

  public String getLabel() {
    return label;
  }

  public BigDecimal getMaxFreeRate() {
    return maxFreeRate;
  }

  /** 你截图里缺的就是这个方法 */
  public boolean isAllAccess() {
    return allAccess;
  }
}
