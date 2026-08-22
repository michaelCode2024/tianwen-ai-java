package com.ruoyi.tianwen.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 战绩 V2 + 数据中心 V2 聚合查询。
 *
 * 默认全部使用最新推荐 is_latest=1，防止同一场历史推荐重复统计。
 */
public interface TianwenAnalyticsMapper {

    Map<String,Object> selectOverview(
            @Param("q") Map<String,Object> q
    );

    List<Map<String,Object>> selectDailyTrend(
            @Param("q") Map<String,Object> q
    );

    List<Map<String,Object>> selectMarketStats(
            @Param("q") Map<String,Object> q
    );

    List<Map<String,Object>> selectRateBucketStats(
            @Param("q") Map<String,Object> q
    );

    List<Map<String,Object>> selectModelStats(
            @Param("q") Map<String,Object> q
    );

    List<Map<String,Object>> selectResultDistribution(
            @Param("q") Map<String,Object> q
    );

    List<Map<String,Object>> selectRateDistribution(
            @Param("q") Map<String,Object> q
    );

    List<Map<String,Object>> selectSportStats(
            @Param("q") Map<String,Object> q
    );
}
