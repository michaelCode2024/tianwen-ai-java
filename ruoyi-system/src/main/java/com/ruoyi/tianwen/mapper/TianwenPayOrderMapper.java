package com.ruoyi.tianwen.mapper;

import com.ruoyi.tianwen.domain.TianwenPayOrder;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TianwenPayOrderMapper {

    void ensureTable();

    int insert(TianwenPayOrder order);

    int updateGatewayInfo(TianwenPayOrder order);

    TianwenPayOrder selectByMchOrderNo(@Param("mchOrderNo") String mchOrderNo);

    List<TianwenPayOrder> selectByUser(
            @Param("userId") Long userId,
            @Param("statusGroup") String statusGroup,
            @Param("limit") int limit
    );

    int expireOverdueOrders(@Param("userId") Long userId, @Param("nowMs") long nowMs);

    int updateStatus(
            @Param("mchOrderNo") String mchOrderNo,
            @Param("status") String status
    );

    int markPaidAndFulfill(
            @Param("mchOrderNo") String mchOrderNo,
            @Param("gatewayOrderNo") String gatewayOrderNo,
            @Param("chainKey") String chainKey,
            @Param("coin") String coin,
            @Param("actualAmount") String actualAmount,
            @Param("txHash") String txHash
    );
}
