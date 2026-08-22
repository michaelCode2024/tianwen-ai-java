package com.ruoyi.tianwen.service;

import java.util.Map;

public interface ITianwenAnalyticsService {

    /**
     * 战绩 V2：
     * overview + daily + bestMarket + bestRateBucket + bestModel
     */
    Map<String,Object> performance(
            Map<String,String> query
    );

    /**
     * 数据中心总览：
     * overview + rateDistribution + resultDistribution
     */
    Map<String,Object> dataOverview(
            Map<String,String> query
    );

    /**
     * 数据中心趋势分析。
     */
    Map<String,Object> dataTrend(
            Map<String,String> query
    );

    /**
     * 数据中心模型对比。
     */
    Map<String,Object> dataModels(
            Map<String,String> query
    );
}
