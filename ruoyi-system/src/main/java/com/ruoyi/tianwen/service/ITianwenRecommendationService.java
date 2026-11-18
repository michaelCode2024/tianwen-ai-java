package com.ruoyi.tianwen.service;

import com.ruoyi.tianwen.domain.TianwenH5User;

import java.util.Map;

public interface ITianwenRecommendationService {

    Map<String,Object> list(
            Map<String,String> query,
            TianwenH5User user
    );

    Map<String,Object> stats(
            Map<String,String> query
    );

    Map<String,Object> homeLatest(
            Map<String,String> query,
            TianwenH5User user
    );

    /**
     * V18推荐详情：
     * item    当前推荐
     * history 同场其它推荐
     * match   比赛/结算摘要
     */
    Map<String,Object> detail(
            String recommendationId,
            Map<String,String> query,
            TianwenH5User user
    );

    Map<String, Object> predictions(String date, String model, TianwenH5User user);

    Map<String, Object> exportPredictions(String date, String model);

    Map<String,Object> adminList(
            Map<String,String> query
    );

    Map<String,Object> updateOverride(
            String recommendationId,
            Map<String,Object> fields,
            String adminUsername
    );

    Map<String,Object> resetOverride(
            String recommendationId
    );

    Map<String,Object> legacyRecommendations(
            Map<String,String> query
    );

    Map<String,Object> summary(
            Map<String,String> query
    );

    Map<String,Object> review(
            Map<String,String> query
    );
}
