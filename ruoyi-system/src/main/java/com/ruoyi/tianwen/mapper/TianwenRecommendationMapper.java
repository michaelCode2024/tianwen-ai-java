package com.ruoyi.tianwen.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface TianwenRecommendationMapper {

    List<Map<String,Object>> selectFastList(@Param("q") Map<String,Object> q);

    long countFast(@Param("q") Map<String,Object> q);

    Map<String,Object> selectFastCounts(@Param("q") Map<String,Object> q);

    List<Map<String,Object>> selectHomeLatest(@Param("q") Map<String,Object> q);

    /**
     * 推荐详情主键查询。
     */
    Map<String,Object> selectById(@Param("id") Long id);

    /**
     * 同一比赛历史推荐。
     *
     * currentId:
     *  排除当前正在看的推荐。
     *
     * 不强制 is_latest=0：
     * 这样即使数据历史标识还未完全修正，也能按同一match_id看到所有其它推荐。
     */
    List<Map<String,Object>> selectMatchHistory(
            @Param("matchId") String matchId,
            @Param("currentId") Long currentId,
            @Param("limit") Integer limit
    );

    List<Map<String,Object>> selectMatchCandidates(
            @Param("matchId") String matchId,
            @Param("model") String model
    );

    Map<String, Object> selectReplayPrediction(
            @Param("platform") String platform,
            @Param("matchId") String matchId
    );

    List<Map<String, Object>> selectPredictionsByDate(
            @Param("platform") String platform,
            @Param("dayStart") String dayStart,
            @Param("dayEnd") String dayEnd
    );

    List<Map<String, Object>> selectLatestRecommendationsByMatchIds(
            @Param("model") String model,
            @Param("matchIds") List<String> matchIds
    );

    List<Map<String, Object>> selectPredictionDays(@Param("platform") String platform);

    List<Map<String, Object>> selectUpcomingRecommendationsByRange(
            @Param("model") String model,
            @Param("dayStart") String dayStart,
            @Param("dayEnd") String dayEnd
    );

    List<Map<String, Object>> selectRecommendationDays(@Param("model") String model);

    List<Map<String, Object>> selectUpcomingBestRecommendations(@Param("q") Map<String, Object> q);

    List<Map<String, Object>> selectUpcomingPredictionsForFeed(
            @Param("platforms") List<String> platforms,
            @Param("limit") Integer limit
    );
}
