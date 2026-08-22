package com.ruoyi.tianwen.mapper;
import com.ruoyi.tianwen.domain.TianwenCheckin;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
public interface TianwenCheckinMapper {
    TianwenCheckin selectByDate(@Param("userId") Long userId, @Param("date") LocalDate date);
    TianwenCheckin selectLatest(Long userId);
    TianwenCheckin selectLatestBefore(@Param("userId") Long userId, @Param("date") LocalDate date);
    int insert(TianwenCheckin checkin);
}
