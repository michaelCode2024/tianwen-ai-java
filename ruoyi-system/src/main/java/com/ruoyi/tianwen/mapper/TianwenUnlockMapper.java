package com.ruoyi.tianwen.mapper;

import com.ruoyi.tianwen.domain.TianwenUnlock;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TianwenUnlockMapper {

  TianwenUnlock selectOne(
      @Param("userId") Long userId, @Param("recommendationId") String recommendationId);

  List<String> selectUnlockedIds(@Param("userId") Long userId);

  int insert(TianwenUnlock unlock);

  /** 唯一索引 + INSERT IGNORE。 返回1：本次插入；返回0：已存在。 */
  int insertIgnore(
      @Param("userId") Long userId,
      @Param("recommendationId") String recommendationId,
      @Param("unlockType") String unlockType,
      @Param("amount") BigDecimal amount);
}
