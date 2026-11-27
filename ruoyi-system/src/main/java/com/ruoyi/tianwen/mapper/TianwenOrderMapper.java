package com.ruoyi.tianwen.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface TianwenOrderMapper {

  void ensureTable();

  int insert(
      @Param("orderNo") String orderNo,
      @Param("userId") Long userId,
      @Param("productType") String productType,
      @Param("recommendationId") String recommendationId,
      @Param("vipLevel") Integer vipLevel,
      @Param("title") String title,
      @Param("amount") BigDecimal amount);

  Map<String, Object> selectByNo(@Param("orderNo") String orderNo);

  List<Map<String, Object>> selectByUser(@Param("userId") Long userId);

  int markPaid(@Param("orderNo") String orderNo, @Param("payChannel") String payChannel);
}
