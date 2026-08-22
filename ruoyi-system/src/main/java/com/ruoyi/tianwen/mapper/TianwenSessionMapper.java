package com.ruoyi.tianwen.mapper;

import com.ruoyi.tianwen.domain.TianwenH5Session;
import com.ruoyi.tianwen.domain.TianwenH5User;
import org.apache.ibatis.annotations.Param;

public interface TianwenSessionMapper {
    int insert(TianwenH5Session session);
    TianwenH5User selectUserByToken(@Param("token") String token, @Param("now") long now);
    int deleteByToken(String token);
    int deleteExpired(@Param("now") long now);
}
