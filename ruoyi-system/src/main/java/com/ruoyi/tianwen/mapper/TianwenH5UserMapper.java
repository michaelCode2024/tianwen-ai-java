package com.ruoyi.tianwen.mapper;

import com.ruoyi.tianwen.domain.TianwenH5User;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface TianwenH5UserMapper {

    TianwenH5User selectById(Long id);

    TianwenH5User selectByPhone(String phone);

    int insert(TianwenH5User user);

    int updatePassword(
            @Param("id") Long id,
            @Param("passwordHash") String passwordHash,
            @Param("salt") String salt
    );

    int updateAsset(
            @Param("id") Long id,
            @Param("balance") BigDecimal balance,
            @Param("freeViewsRemaining") Integer freeViewsRemaining
    );

    int addFreeViews(
            @Param("id") Long id,
            @Param("delta") Integer delta
    );

    int decrementFreeView(Long id);

    /**
     * 为第三阶段管理后台预留。
     * 当前也可以直接从SQL测试会员。
     */
    int updateVip(
            @Param("id") Long id,
            @Param("vipLevel") Integer vipLevel,
            @Param("vipStartAt") LocalDateTime vipStartAt,
            @Param("vipExpireAt") LocalDateTime vipExpireAt
    );

    List<Map<String, Object>> selectAdminList(
            @Param("q") String q,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countAdminList(
            @Param("q") String q
    );

    int insertAccountLog(
            @Param("userId") Long userId,
            @Param("balanceDelta") BigDecimal balanceDelta,
            @Param("freeViewsDelta") Integer freeViewsDelta,
            @Param("balanceAfter") BigDecimal balanceAfter,
            @Param("freeViewsAfter") Integer freeViewsAfter,
            @Param("remark") String remark,
            @Param("adminUsername") String adminUsername
    );
}
