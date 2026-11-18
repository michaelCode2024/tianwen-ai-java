package com.ruoyi.tianwen.service.impl;

import com.ruoyi.tianwen.mapper.TianwenAnalyticsMapper;
import com.ruoyi.tianwen.service.ITianwenAnalyticsService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 天问AI 战绩 V2 + 数据中心 V2。
 *
 * 注意：
 * 默认只统计 is_latest=1。
 *
 * V22统一命中口径：
 * result_status 1=全赢、3=走水、4=半赢 都计入命中。
 * 2=全输、5=半输 计入未命中。
 * 历史推荐是详情时间轴，不重复计入主战绩。
 */
@Service
public class TianwenAnalyticsServiceImpl
        implements ITianwenAnalyticsService {

    private static final long ANALYTICS_CACHE_MILLIS = 30_000L;
    private static final int MAX_ANALYTICS_CACHE_SIZE = 100;
    private final Map<String, CacheEntry> analyticsCache = new ConcurrentHashMap<>();

    private final TianwenAnalyticsMapper analyticsMapper;

    public TianwenAnalyticsServiceImpl(
            TianwenAnalyticsMapper analyticsMapper) {
        this.analyticsMapper = analyticsMapper;
    }

    @Override
    public Map<String,Object> performance(
            Map<String,String> query) {

        QuerySpec spec = parse(query);
        return getOrCompute("performance", spec.db, () -> {
            Map<String,Object> overview =
                    safeMap(
                            analyticsMapper.selectOverview(
                                    spec.db
                            )
                    );

            List<Map<String,Object>> daily =
                    safeList(
                            analyticsMapper.selectDailyTrend(
                                    spec.db
                            )
                    );

            List<Map<String,Object>> marketStats =
                    safeList(
                            analyticsMapper.selectMarketStats(
                                    spec.db
                            )
                    );

            List<Map<String,Object>> rateStats =
                    safeList(
                            analyticsMapper.selectRateBucketStats(
                                    spec.db
                            )
                    );

            List<Map<String,Object>> modelStats =
                    safeList(
                            analyticsMapper.selectModelStats(
                                    spec.db
                            )
                    );

            Map<String,Object> out =
                    new LinkedHashMap<>();

            out.put("code", 200);
            out.put("range", spec.range);
            out.put("days", spec.days);
            out.put("filters", spec.filters);

            out.put("overview", overview);
            out.put("daily", daily);

            out.put(
                    "bestMarket",
                    best(marketStats)
            );

            out.put(
                    "bestRateBucket",
                    best(rateStats)
            );

            out.put(
                    "bestModel",
                    best(modelStats)
            );

            out.put("marketStats", marketStats);
            out.put("rateStats", rateStats);
            out.put("modelStats", modelStats);

            return out;
        });
    }

    @Override
    public Map<String,Object> dataOverview(
            Map<String,String> query) {

        QuerySpec spec = parse(query);
        return getOrCompute("dataOverview", spec.db, () -> {
            Map<String,Object> overview =
                    safeMap(
                            analyticsMapper.selectOverview(
                                    spec.db
                            )
                    );

            List<Map<String,Object>> rateDistribution =
                    safeList(
                            analyticsMapper.selectRateDistribution(
                                    spec.db
                            )
                    );

            List<Map<String,Object>> resultDistribution =
                    safeList(
                            analyticsMapper.selectResultDistribution(
                                    spec.db
                            )
                    );

            List<Map<String,Object>> sportStats =
                    safeList(
                            analyticsMapper.selectSportStats(
                                    spec.db
                            )
                    );

            Map<String,Object> out =
                    new LinkedHashMap<>();

            out.put("code", 200);
            out.put("range", spec.range);
            out.put("days", spec.days);
            out.put("filters", spec.filters);
            out.put("overview", overview);
            out.put("rateDistribution", rateDistribution);
            out.put("resultDistribution", resultDistribution);
            out.put("sportStats", sportStats);

            return out;
        });
    }

    @Override
    public Map<String,Object> dataTrend(
            Map<String,String> query) {

        QuerySpec spec = parse(query);
        return getOrCompute("dataTrend", spec.db, () -> {
            Map<String,Object> out =
                    new LinkedHashMap<>();

            out.put("code", 200);
            out.put("range", spec.range);
            out.put("days", spec.days);
            out.put("filters", spec.filters);

            out.put(
                    "daily",
                    safeList(
                            analyticsMapper.selectDailyTrend(
                                    spec.db
                            )
                    )
            );

            return out;
        });
    }

    @Override
    public Map<String,Object> dataModels(
            Map<String,String> query) {

        QuerySpec spec = parse(query);
        return getOrCompute("dataModels", spec.db, () -> {
            List<Map<String,Object>> modelStats =
                    safeList(
                            analyticsMapper.selectModelStats(
                                    spec.db
                            )
                    );

            Map<String,Object> out =
                    new LinkedHashMap<>();

            out.put("code", 200);
            out.put("range", spec.range);
            out.put("days", spec.days);
            out.put("filters", spec.filters);
            out.put("items", modelStats);
            out.put("bestModel", best(modelStats));

            return out;
        });
    }

    /**
     * 最佳维度：
     * SQL已经按 hit_rate DESC / settled DESC 排序。
     * 为避免“1场1中=100%”误导，优先选择已结算样本>=3。
     * 如果全部不到3场，再退回第一项。
     */
    private Map<String,Object> best(
            List<Map<String,Object>> rows) {

        if (rows == null || rows.isEmpty()) {
            return null;
        }

        for (Map<String,Object> row : rows) {
            if (number(row.get("settled")) >= 3) {
                return row;
            }
        }

        return rows.get(0);
    }

    private QuerySpec parse(
            Map<String,String> query) {

        Map<String,String> q =
                query == null
                        ? Collections.emptyMap()
                        : query;

        int days =
                positiveInt(
                        q.get("days"),
                        7,
                        1,
                        365
                );

        LocalDate endDate =
                LocalDate.now()
                        .plusDays(1);

        LocalDate startDate =
                LocalDate.now()
                        .minusDays(days - 1L);

        Map<String,Object> db =
                new LinkedHashMap<>();

        db.put(
                "startTime",
                startDate.atStartOfDay()
        );

        db.put(
                "endTime",
                endDate.atStartOfDay()
        );

        db.put(
                "sport",
                normalizeSport(
                        q.get("sport")
                )
        );

        db.put(
                "model",
                normalizeModel(
                        q.get("model")
                )
        );

        db.put(
                "market",
                trim(
                        q.get("market")
                )
        );

        db.put(
                "minRate",
                decimal(
                        q.get("min_rate")
                )
        );

        db.put(
                "maxRate",
                decimal(
                        q.get("max_rate")
                )
        );

        Map<String,Object> filters =
                new LinkedHashMap<>();

        filters.put(
                "sport",
                db.get("sport")
        );

        filters.put(
                "model",
                db.get("model")
        );

        filters.put(
                "market",
                db.get("market")
        );

        filters.put(
                "minRate",
                db.get("minRate")
        );

        filters.put(
                "maxRate",
                db.get("maxRate")
        );

        QuerySpec spec =
                new QuerySpec();

        spec.days = days;
        spec.db = db;
        spec.filters = filters;
        spec.range =
                startDate.toString()
                        + " ~ "
                        + LocalDate.now();

        return spec;
    }

    private String normalizeSport(
            String value) {

        String v = trim(value).toLowerCase();

        if ("basketball".equals(v)
                || "football".equals(v)) {
            return v;
        }

        return "all";
    }

    private String normalizeModel(
            String value) {

        String v = trim(value).toLowerCase();

        if ("jiebao".equals(v)
                || "nami".equals(v)) {
            return v;
        }

        return "all";
    }

    private String trim(String value) {
        return value == null
                ? ""
                : value.trim();
    }

    private Double decimal(String value) {
        try {
            if (value == null
                    || value.trim().isEmpty()) {
                return null;
            }

            return Double.valueOf(
                    value.trim()
            );
        } catch (Exception e) {
            return null;
        }
    }

    private int positiveInt(
            String value,
            int defaultValue,
            int min,
            int max) {

        int n;

        try {
            n = Integer.parseInt(value);
        } catch (Exception e) {
            n = defaultValue;
        }

        return Math.max(
                min,
                Math.min(max, n)
        );
    }

    private long number(Object value) {
        if (value instanceof Number) {
            return ((Number)value)
                    .longValue();
        }

        try {
            return Long.parseLong(
                    String.valueOf(value)
            );
        } catch (Exception e) {
            return 0L;
        }
    }

    private Map<String,Object> safeMap(
            Map<String,Object> value) {

        return value == null
                ? new LinkedHashMap<>()
                : value;
    }

    private List<Map<String,Object>> safeList(
            List<Map<String,Object>> value) {

        return value == null
                ? new ArrayList<>()
                : value;
    }

    private Map<String,Object> getOrCompute(
            String method,
            Map<String,Object> db,
            Supplier<Map<String,Object>> loader) {

        String key = buildCacheKey(method, db);
        long now = System.currentTimeMillis();
        CacheEntry cached = analyticsCache.get(key);
        if (cached != null && cached.expireAt > now) {
            return new LinkedHashMap<>(cached.data);
        }

        Map<String,Object> fresh = loader.get();
        if (analyticsCache.size() >= MAX_ANALYTICS_CACHE_SIZE) {
            analyticsCache.entrySet().removeIf(e -> e.getValue().expireAt <= now);
            if (analyticsCache.size() >= MAX_ANALYTICS_CACHE_SIZE) {
                analyticsCache.clear();
            }
        }
        analyticsCache.put(key, new CacheEntry(now + ANALYTICS_CACHE_MILLIS, fresh));
        return new LinkedHashMap<>(fresh);
    }

    private String buildCacheKey(String method, Map<String,Object> db) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (db != null) {
            for (Map.Entry<String, Object> e : db.entrySet()) {
                sorted.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return method + ":" + sorted;
    }

    private static class CacheEntry {
        final long expireAt;
        final Map<String,Object> data;

        CacheEntry(long expireAt, Map<String,Object> data) {
            this.expireAt = expireAt;
            this.data = data;
        }
    }

    private static class QuerySpec {
        int days;
        String range;
        Map<String,Object> db;
        Map<String,Object> filters;
    }
}
