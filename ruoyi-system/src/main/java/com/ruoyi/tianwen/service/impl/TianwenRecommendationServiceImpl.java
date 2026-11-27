package com.ruoyi.tianwen.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.tianwen.config.TianwenAiProperties;
import com.ruoyi.tianwen.domain.TianwenAccessResult;
import com.ruoyi.tianwen.domain.TianwenH5User;
import com.ruoyi.tianwen.domain.TianwenRecommendOverride;
import com.ruoyi.tianwen.domain.TianwenVipLevel;
import com.ruoyi.tianwen.mapper.TianwenRecommendOverrideMapper;
import com.ruoyi.tianwen.mapper.TianwenRecommendationMapper;
import com.ruoyi.tianwen.mapper.TianwenUnlockMapper;
import com.ruoyi.tianwen.service.ITianwenAccessPolicyService;
import com.ruoyi.tianwen.service.ITianwenRecommendationService;
import com.ruoyi.tianwen.util.TianwenPredictionView;
import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 天问 AI 推荐服务 V3。
 *
 * <p>重要：本实现不再请求 https://ai.lookfunny.xyz，也不依赖 server.py。 推荐列表/详情/后台推荐管理全部直接查询
 * MySQL：football_ai_recommend_record， 赛事即时状态关联 t_leisu_schedule。
 */
@Service
public class TianwenRecommendationServiceImpl implements ITianwenRecommendationService {
  private static final Logger log = LoggerFactory.getLogger(TianwenRecommendationServiceImpl.class);
  private static final Set<String> HIT = Set.of("1", "3", "4");
  private static final Set<String> LOST = Set.of("2", "5");
  private static final Set<String> FINISHED = Set.of("1", "2", "3", "4", "5");
  private static final Pattern SYNTHETIC_REC_ID =
      Pattern.compile("^pm_(jiebao|nami)_([A-Za-z0-9_-]{1,48})$");
  private static final DateTimeFormatter MATCH_TIME_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

  // 推荐统计与公共列表采用 SWR：30 秒内直接命中，30 秒~5 分钟先秒返旧缓存并后台单飞刷新，避免用户撞冷查询。
  private static final long STATS_FRESH_MILLIS = 30_000L;
  private static final long STATS_STALE_MILLIS = 300_000L;
  private static final long LIST_FRESH_MILLIS = 30_000L;
  private static final long LIST_STALE_MILLIS = 300_000L;
  private static final int MAX_LIST_CACHE_SIZE = 200;
  private final AtomicLong cacheEpoch = new AtomicLong(0L);
  private final Map<String, StatsCacheValue> statsCache = new ConcurrentHashMap<>();
  private final Map<String, ListCacheValue> listCache = new ConcurrentHashMap<>();
  private final Map<String, ListCacheValue> homeLatestCache = new ConcurrentHashMap<>();
  private final Map<String, ListCacheValue> predictionsCache = new ConcurrentHashMap<>();
  private final Map<String, ListCacheValue> predictionDaysCache = new ConcurrentHashMap<>();
  private final Set<String> refreshingStatsKeys = ConcurrentHashMap.newKeySet();
  private final Set<String> refreshingListKeys = ConcurrentHashMap.newKeySet();
  private final Set<String> refreshingHomeKeys = ConcurrentHashMap.newKeySet();
  private final Set<String> refreshingPredictionKeys = ConcurrentHashMap.newKeySet();
  private final Set<String> refreshingPredictionDaysKeys = ConcurrentHashMap.newKeySet();

  private final TianwenAiProperties properties;
  private final TianwenRecommendationMapper recommendationMapper;
  private final TianwenUnlockMapper unlockMapper;
  private final TianwenRecommendOverrideMapper overrideMapper;
  private final ITianwenAccessPolicyService accessPolicyService;
  private final ObjectMapper objectMapper;

  public TianwenRecommendationServiceImpl(
      TianwenAiProperties properties,
      TianwenRecommendationMapper recommendationMapper,
      TianwenUnlockMapper unlockMapper,
      TianwenRecommendOverrideMapper overrideMapper,
      ITianwenAccessPolicyService accessPolicyService,
      ObjectMapper objectMapper) {
    this.properties = properties;
    this.recommendationMapper = recommendationMapper;
    this.unlockMapper = unlockMapper;
    this.overrideMapper = overrideMapper;
    this.accessPolicyService = accessPolicyService;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  public void warmupHomeAndPredictions() {
    CompletableFuture.runAsync(() -> {
      try {
        homeLatest(Map.of("min_rate", "80", "limit", "30"), null);
        list(Map.of("model", "jiebao", "quick", "upcoming", "min_rate", "80", "page", "1", "page_size", "24"), null);
        list(Map.of("model", "nami", "quick", "upcoming", "min_rate", "80", "page", "1", "page_size", "24"), null);
        String today = LocalDate.now(SHANGHAI_ZONE).toString();
        predictions(today, "jiebao", 3, null);
        predictions(today, "nami", 3, null);
      } catch (Exception ignored) {
        // 预热失败不影响应用启动
      }
    });
  }

  private static int positiveInt(String v, int def, int min, Integer max) {
    try {
      int n = Integer.parseInt(trim(v));
      if (n < min) return def;
      if (max != null && n > max) return max;
      return n;
    } catch (Exception e) {
      return def;
    }
  }

  private static Integer nullableInt(String v) {
    try {
      return trim(v).isEmpty() ? null : Integer.valueOf(trim(v));
    } catch (Exception e) {
      return null;
    }
  }

  private static Double toDouble(Object v) {
    try {
      if (v == null || trim(String.valueOf(v)).isEmpty()) return null;
      return Double.valueOf(String.valueOf(v));
    } catch (Exception e) {
      return null;
    }
  }

  private static String trim(String v) {
    return v == null ? "" : v.trim();
  }

  private static String str(Object v) {
    return v == null ? "" : String.valueOf(v);
  }

  private static String blankDefault(String v, String d) {
    return trim(v).isEmpty() ? d : trim(v);
  }

  // ---------------------------------------------------------
  // 2. 新增：结果状态装饰
  // ---------------------------------------------------------

  /**
   * H5 推荐列表 V10： 1. 列表接口只负责列表，不再每一页都 COUNT + 聚合统计。 2. SQL 一次取 pageSize + 1 条，通过多出来的一条判断 hasMore。 3.
   * 默认不返回 counts；页面需要统计时单独调用 stats()。 4. 这样 page=2/page=3 不会重复跑昂贵 COUNT。
   */
  @Override
  public Map<String, Object> list(Map<String, String> query, TianwenH5User user) {
    QuerySpec spec = parseQuery(query, false);

    List<Map<String, Object>> items;
    boolean hasMore;

    if ("upcoming".equals(spec.quick)) {
      List<Map<String, Object>> merged =
          loadMergedUpcoming(
              List.of(spec.model),
              spec.sport,
              str(spec.db.get("market")),
              toDouble(spec.db.get("minRate")),
              toDouble(spec.db.get("maxRate")),
              str(spec.db.get("q")),
              str(spec.db.get("sortBy")));
      int offset = (spec.page - 1) * spec.pageSize;
      if (offset >= merged.size()) {
        items = List.of();
        hasMore = false;
      } else {
        int end = Math.min(merged.size(), offset + spec.pageSize);
        items = merged.subList(offset, end);
        hasMore = end < merged.size();
      }
    } else {
      // 多取 1 条用于判断是否还有下一页，避免 COUNT(*)。
      spec.db.put("limit", spec.pageSize + 1);

      boolean cacheable = trim(query == null ? null : query.get("q")).isEmpty() && spec.page <= 5;
      String cacheKey = cacheable ? buildListCacheKey(spec.db) : null;

      ListCacheValue listValue =
          cacheKey != null
              ? getOrRefreshListCache(
                  listCache,
                  refreshingListKeys,
                  cacheKey,
                  () -> computeListValue(spec))
              : computeListValue(spec);

      items = listValue.items;
      hasMore = listValue.hasMore;

      if (spec.quick.isEmpty() && spec.page == 1 && str(spec.db.get("q")).isEmpty()) {
        List<Map<String, Object>> upcomingTop =
            loadMergedUpcoming(
                List.of(spec.model),
                spec.sport,
                str(spec.db.get("market")),
                toDouble(spec.db.get("minRate")),
                toDouble(spec.db.get("maxRate")),
                "",
                str(spec.db.get("sortBy")));
        if (!upcomingTop.isEmpty()) {
          int cap = Math.min(10, Math.max(4, spec.pageSize / 2));
          List<Map<String, Object>> combined = new ArrayList<>(spec.pageSize);
          Set<String> seen = new HashSet<>();
          for (Map<String, Object> up : upcomingTop) {
            if (combined.size() >= cap) break;
            String key = modelMatchKey(up);
            if (seen.add(key)) {
              combined.add(up);
            }
          }
          for (Map<String, Object> row : items) {
            if (combined.size() >= spec.pageSize) break;
            String key = modelMatchKey(row);
            if (seen.add(key)) {
              combined.add(row);
            }
          }
          items = combined;
          if (!hasMore && upcomingTop.size() > cap) {
            hasMore = true;
          }
        }
      }
    }

    Set<String> unlocked =
        user == null
            ? Collections.emptySet()
            : new HashSet<>(unlockMapper.selectUnlockedIds(user.getId()));

    List<Map<String, Object>> viewItems =
        items.stream()
            .map(i -> mask(i, user, isUnlockedForRow(unlocked, i)))
            .collect(Collectors.toList());

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("items", viewItems);
    out.put("count", -1); // 极速分页不再强制计算总数
    out.put("counts", Collections.emptyMap());

    Map<String, Object> pager = new LinkedHashMap<>();
    pager.put("page", spec.page);
    pager.put("pageSize", spec.pageSize);
    pager.put("total", -1);
    pager.put("hasMore", hasMore);
    out.put("pagination", pager);

    out.put("auth", auth(user, spec.model));
    out.put("source", source(spec.model));
    return out;
  }

  // ---------------------------------------------------------
  // 3. 历史推荐变化描述
  // ---------------------------------------------------------

  /** 推荐统计单独查询并做 15 秒缓存。 前端首屏先展示列表，统计可以异步补上。 */
  @Override
  public Map<String, Object> stats(Map<String, String> query) {
    Map<String, String> q = new LinkedHashMap<>(query == null ? Collections.emptyMap() : query);

    // tab 统计不应该受当前 quick/status 影响。
    q.remove("quick");
    q.remove("status");
    q.remove("page");
    q.remove("page_size");

    QuerySpec spec = parseQuery(q, false);
    String cacheKey = buildStatsCacheKey(spec.db);

    long now = System.currentTimeMillis();
    StatsCacheValue cached = statsCache.get(cacheKey);
    if (cached != null && cached.staleAt > now) {
      if (cached.freshUntil <= now && refreshingStatsKeys.add(cacheKey)) {
        long epoch = cacheEpoch.get();
        CompletableFuture.runAsync(
            () -> {
              try {
                Map<String, Object> fresh = computeStats(spec);
                if (cacheEpoch.get() == epoch) {
                  long ts = System.currentTimeMillis();
                  statsCache.put(
                      cacheKey,
                      new StatsCacheValue(
                          ts + STATS_FRESH_MILLIS, ts + STATS_STALE_MILLIS, deepCopyMap(fresh)));
                }
              } finally {
                refreshingStatsKeys.remove(cacheKey);
              }
            });
      }
      return deepCopyMap(cached.data);
    }

    long epoch = cacheEpoch.get();
    Map<String, Object> out = computeStats(spec);
    if (cacheEpoch.get() == epoch) {
      long ts = System.currentTimeMillis();
      statsCache.put(
          cacheKey,
          new StatsCacheValue(ts + STATS_FRESH_MILLIS, ts + STATS_STALE_MILLIS, deepCopyMap(out)));
    }

    return out;
  }

  private Map<String, Object> computeStats(QuerySpec spec) {
    Map<String, Object> countRow = recommendationMapper.selectFastCounts(spec.db);

    int dbUpcoming = intValue(countRow, "upcoming_count");
    int mergedUpcoming =
        loadMergedUpcoming(
                List.of(spec.model),
                spec.sport,
                str(spec.db.get("market")),
                toDouble(spec.db.get("minRate")),
                toDouble(spec.db.get("maxRate")),
                str(spec.db.get("q")),
                "match_time")
            .size();
    int dbAll = intValue(countRow, "all_count");
    int adjustedAll = dbAll + Math.max(0, mergedUpcoming - dbUpcoming);

    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("all", adjustedAll);
    counts.put("upcoming", mergedUpcoming);
    counts.put("pending", intValue(countRow, "pending_count"));
    counts.put("hit", intValue(countRow, "hit_count"));
    counts.put("finished", intValue(countRow, "finished_count"));
    counts.put("obsolete", intValue(countRow, "obsolete_count"));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("counts", counts);
    out.put("count", counts.get("all"));
    return out;
  }

  // ---------------------------------------------------------
  // 4. 比赛结果摘要
  // ---------------------------------------------------------

  @Override
  public Map<String, Object> homeLatest(Map<String, String> query, TianwenH5User user) {
    Map<String, String> q = new LinkedHashMap<>(query == null ? Collections.emptyMap() : query);

    Map<String, Object> db = new LinkedHashMap<>();
    Double minRate = toDouble(q.get("min_rate"));
    if (minRate == null) minRate = 80D;

    int limit = positiveInt(q.get("limit"), 30, 1, 60);

    db.put("minRate", Math.max(90D, minRate));
    db.put("limit", limit);
    db.put("status", trim(q.get("status")));

    List<Map<String, Object>> upcomingAll =
        loadMergedUpcoming(
            List.of("jiebao", "nami"),
            "all",
            "",
            Math.min(minRate, 80D),
            null,
            "",
            "match_time");

    String cacheKey = "home_v2|" + buildListCacheKey(db);
    List<Map<String, Object>> rows =
        getOrRefreshListCache(
                homeLatestCache,
                refreshingHomeKeys,
                cacheKey,
                () -> {
                  int upcomingTarget = Math.min(upcomingAll.size(), Math.max(12, Math.min(18, limit * 2 / 3)));
                  List<Map<String, Object>> upcomingPick = pickBalancedUpcoming(upcomingAll, upcomingTarget);
                  Set<String> seen = new HashSet<>();
                  List<Map<String, Object>> merged = new ArrayList<>(limit);
                  for (Map<String, Object> up : upcomingPick) {
                    if (seen.add(modelMatchKey(up))) {
                      merged.add(up);
                    }
                  }

                  List<Map<String, Object>> raw = recommendationMapper.selectHomeLatest(db);
                  List<Map<String, Object>> computed = applyOverrides(raw);
                  computed.forEach(this::decorateAndScript);
                  for (Map<String, Object> hist : computed) {
                    if (merged.size() >= limit) break;
                    if (seen.add(modelMatchKey(hist))) {
                      merged.add(hist);
                    }
                  }
                  long ts = System.currentTimeMillis();
                  return new ListCacheValue(
                      ts + LIST_FRESH_MILLIS, ts + LIST_STALE_MILLIS, merged, false);
                })
            .items;

    Set<String> unlocked =
        user == null
            ? Collections.emptySet()
            : new HashSet<>(unlockMapper.selectUnlockedIds(user.getId()));

    List<Map<String, Object>> items =
        rows.stream()
            .map(i -> mask(i, user, isUnlockedForRow(unlocked, i)))
            .collect(Collectors.toList());

    int hitCount =
        (int) items.stream().filter(i -> HIT.contains(str(i.get("result_status")))).count();

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("items", items);
    out.put("count", items.size());
    out.put("upcomingCount", upcomingAll.size());
    out.put("hitCount", hitCount);
    out.put("auth", auth(user, "all"));
    out.put(
        "source",
        Map.of(
            "mode", "mysql-fast",
            "table", "football_ai_recommend_record",
            "latestOnly", true));
    return out;
  }

  private List<Map<String, Object>> loadMergedUpcoming(
      List<String> models,
      String sport,
      String market,
      Double minRate,
      Double maxRate,
      String keyword,
      String sortBy) {
    List<String> normModels =
        (models == null || models.isEmpty())
            ? List.of("jiebao", "nami")
            : models.stream().map(this::normalizeModel).distinct().collect(Collectors.toList());

    String normalizedSport = trim(sport).toLowerCase(Locale.ROOT);
    String normalizedMarket = trim(market);
    String kw = trim(keyword).toLowerCase(Locale.ROOT);

    Map<String, Object> db = new LinkedHashMap<>();
    db.put("models", normModels);
    db.put("sport", normalizedSport);
    db.put("market", normalizedMarket);
    db.put("minRate", minRate);
    db.put("maxRate", maxRate);
    db.put("q", kw);

    List<Map<String, Object>> recRows =
        getOrRefreshListCache(
                listCache,
                refreshingListKeys,
                "up_rec|" + buildListCacheKey(db),
                () -> {
                  List<Map<String, Object>> raw;
                  try {
                    raw = recommendationMapper.selectUpcomingBestRecommendations(db);
                  } catch (DataAccessException e) {
                    raw = List.of();
                  }
                  if (raw == null) raw = List.of();
                  List<Map<String, Object>> computed = applyOverrides(raw);
                  computed.forEach(this::decorateAndScript);
                  computed.forEach(this::decorateResult);
                  long ts = System.currentTimeMillis();
                  return new ListCacheValue(
                      ts + LIST_FRESH_MILLIS, ts + LIST_STALE_MILLIS, computed, false);
                })
            .items;

    List<Map<String, Object>> result = new ArrayList<>(recRows.size() + 64);
    Set<String> seen = new HashSet<>();
    for (Map<String, Object> row : recRows) {
      String key = modelMatchKey(row);
      if (seen.add(key)) {
        Map<String, Object> copy = deepCopy(row);
        copy.putIfAbsent("coveredMarkets", List.of(blankDefault(str(copy.get("market")), "亚盘")));
        copy.putIfAbsent("hasMultiMarkets", Boolean.FALSE);
        copy.putIfAbsent("hasPoissonScore", Boolean.FALSE);
        result.add(copy);
      }
    }

    if (!"basketball".equals(normalizedSport)) {
      List<String> platforms =
          normModels.stream()
              .map(TianwenPredictionView::platformOf)
              .distinct()
              .collect(Collectors.toList());
      String predKey = "up_pred_feed|" + String.join(",", platforms);
      List<Map<String, Object>> predRows =
          getOrRefreshListCache(
                  predictionsCache,
                  refreshingPredictionKeys,
                  predKey,
                  () -> {
                    List<Map<String, Object>> raw;
                    try {
                      raw = recommendationMapper.selectUpcomingPredictionsForFeed(platforms, 450);
                    } catch (DataAccessException e) {
                      raw = List.of();
                    }
                    if (raw == null) raw = List.of();
                    long ts = System.currentTimeMillis();
                    return new ListCacheValue(
                        ts + LIST_FRESH_MILLIS, ts + LIST_STALE_MILLIS, raw, false);
                  })
              .items;

      for (Map<String, Object> pred : predRows) {
        Map<String, Object> synth = toSyntheticUpcomingRec(pred);
        if (synth == null) continue;
        String key = modelMatchKey(synth);
        if (!seen.add(key)) continue;
        if (!matchesUpcomingFilters(synth, normalizedMarket, minRate, maxRate, kw)) continue;
        decorateAndScript(synth);
        decorateResult(synth);
        result.add(synth);
      }
    }

    Comparator<Map<String, Object>> cmp =
        "rate".equals(sortBy)
            ? Comparator.<Map<String, Object>>comparingDouble(
                    r -> -Optional.ofNullable(toDouble(r.get("rate"))).orElse(0D))
                .thenComparing(r -> str(r.get("match_time")))
            : Comparator.<Map<String, Object>, String>comparing(r -> str(r.get("match_time")))
                .thenComparingDouble(
                    r -> -Optional.ofNullable(toDouble(r.get("rate"))).orElse(0D));
    result.sort(cmp);
    return result;
  }

  private List<Map<String, Object>> pickBalancedUpcoming(
      List<Map<String, Object>> upcomingAll, int target) {
    if (upcomingAll.isEmpty() || target <= 0) return List.of();
    List<Map<String, Object>> jiebaoList = new ArrayList<>();
    List<Map<String, Object>> namiList = new ArrayList<>();
    for (Map<String, Object> row : upcomingAll) {
      if ("nami".equals(normalizeModel(str(row.get("model"))))) {
        namiList.add(row);
      } else {
        jiebaoList.add(row);
      }
    }
    List<Map<String, Object>> out = new ArrayList<>(target);
    int i = 0;
    int j = 0;
    while (out.size() < target && (i < jiebaoList.size() || j < namiList.size())) {
      if (i < jiebaoList.size()) out.add(jiebaoList.get(i++));
      if (out.size() < target && j < namiList.size()) out.add(namiList.get(j++));
    }
    out.sort(
        Comparator.<Map<String, Object>, String>comparing(r -> str(r.get("match_time")))
            .thenComparingDouble(
                r -> -Optional.ofNullable(toDouble(r.get("rate"))).orElse(0D)));
    return out;
  }

  private Map<String, Object> toSyntheticUpcomingRec(Map<String, Object> pred) {
    if (pred == null || pred.isEmpty()) return null;
    String matchId = str(pred.get("match_id"));
    if (matchId.isEmpty()) return null;
    String platform = str(pred.get("model_platform"));
    String model = "antscore".equalsIgnoreCase(platform) ? "nami" : "jiebao";

    List<String> coveredMarkets = new ArrayList<>(3);
    if (!str(pred.get("euro_direction")).isBlank()) coveredMarkets.add("欧赔");
    if (!str(pred.get("ou_direction")).isBlank()) coveredMarkets.add("大小球");
    if (!str(pred.get("asia_direction")).isBlank()) coveredMarkets.add("亚盘");

    CandidateMarket chosen = chooseBestUpcomingMarket(pred);
    if (chosen == null) return null;
    if (!coveredMarkets.contains(chosen.market)) {
      coveredMarkets.add(chosen.market);
    }

    double ev = chosen.prob * chosen.decOdds - 1.0;
    double probPart = Math.max(0.0, Math.min(0.35, chosen.prob - 0.45)) * 36.0;
    double evPart = Math.max(-0.05, Math.min(0.25, ev)) * 28.0;
    double rate = Math.round(Math.max(76.0, Math.min(96.8, 78.0 + probPart + evPart)) * 100.0) / 100.0;

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", "pm_" + model + "_" + matchId);
    row.put("model", model);
    row.put("sport", "football");
    row.put("match_id", matchId);
    row.put("league_name", pred.get("league_name"));
    row.put("match_time", pred.get("match_time"));
    row.put("home_team", pred.get("home_team"));
    row.put("guest_team", pred.get("away_team"));
    row.put("stage", blankDefault(str(pred.get("snapshot_stage")), "赛前"));
    row.put("market", chosen.market);
    row.put("direction", chosen.direction);
    row.put("handicap", chosen.handicap);
    row.put("display_handicap", chosen.handicap);
    row.put("odds", chosen.displayOdds);
    row.put("rate", rate);
    row.put("recommend_role", "primary");
    row.put("result_status", 0);
    row.put("is_latest", 1);
    row.put("score_home", pred.get("score_home"));
    row.put("score_away", pred.get("score_away"));
    row.put("coveredMarkets", coveredMarkets);
    row.put("hasMultiMarkets", coveredMarkets.size() >= 2);
    row.put("hasPoissonScore", pred.get("score_home") != null && pred.get("score_away") != null);
    return row;
  }

  private CandidateMarket chooseBestUpcomingMarket(Map<String, Object> pred) {
    List<CandidateMarket> all = new ArrayList<>(4);
    addCandidate(
        all,
        "亚盘",
        str(pred.get("asia_direction")),
        toDouble(pred.get("asia_line")),
        toDouble(pred.get("asia_odds")),
        toDouble(pred.get("asia_prob")),
        true);
    addCandidate(
        all,
        "大小球",
        str(pred.get("ou_direction")),
        toDouble(pred.get("ou_line")),
        toDouble(pred.get("ou_odds")),
        toDouble(pred.get("ou_prob")),
        true);
    addCandidate(
        all,
        "欧赔",
        str(pred.get("euro_direction")),
        null,
        toDouble(pred.get("euro_odds")),
        toDouble(pred.get("euro_prob")),
        false);

    CandidateMarket best =
        all.stream()
            .filter(CandidateMarket::isTradeable)
            .max(Comparator.comparingDouble(CandidateMarket::score))
            .orElse(null);
    if (best != null) return best;

    String pickMarket = str(pred.get("pick_market"));
    if ("让分".equals(pickMarket)) pickMarket = "亚盘";
    String pickDir = str(pred.get("pick_direction"));
    if (!pickDir.isBlank()) {
      boolean hk = "亚盘".equals(pickMarket) || "大小球".equals(pickMarket);
      Double rawOdds = toDouble(pred.get("pick_odds"));
      double decOdds =
          rawOdds == null || rawOdds <= 0
              ? 1.90
              : (hk && rawOdds < 1.35 ? 1.0 + rawOdds : rawOdds);
      double displayOdds = rawOdds == null || rawOdds <= 0 ? 0.90 : rawOdds;
      double prob = 0.56;
      if ("欧赔".equals(pickMarket) && toDouble(pred.get("euro_prob")) != null) {
        prob = toDouble(pred.get("euro_prob"));
      } else if ("大小球".equals(pickMarket) && toDouble(pred.get("ou_prob")) != null) {
        prob = toDouble(pred.get("ou_prob"));
      } else if ("亚盘".equals(pickMarket) && toDouble(pred.get("asia_prob")) != null) {
        prob = toDouble(pred.get("asia_prob"));
      }
      return new CandidateMarket(
          blankDefault(pickMarket, "欧赔"),
          pickDir,
          toDouble(pred.get("pick_handicap")),
          displayOdds,
          decOdds,
          prob);
    }

    return all.stream().max(Comparator.comparingDouble(CandidateMarket::score)).orElse(null);
  }

  private void addCandidate(
      List<CandidateMarket> list,
      String market,
      String direction,
      Double handicap,
      Double rawOdds,
      Double prob,
      boolean hkOdds) {
    if (direction == null || direction.isBlank() || rawOdds == null || rawOdds <= 0 || prob == null) {
      return;
    }
    if ("亚盘".equals(market) && handicap != null && Math.abs(handicap) > 2.25) {
      return;
    }
    double decOdds = hkOdds && rawOdds < 1.35 ? 1.0 + rawOdds : rawOdds;
    list.add(new CandidateMarket(market, direction, handicap, rawOdds, decOdds, prob));
  }

  private boolean matchesUpcomingFilters(
      Map<String, Object> row, String market, Double minRate, Double maxRate, String keyword) {
    if (market != null && !market.isEmpty()) {
      String rowMarket = str(row.get("market"));
      if (!rowMarket.equals(market)) {
        return false;
      }
    }
    double rate = Optional.ofNullable(toDouble(row.get("rate"))).orElse(0D);
    if (minRate != null && rate < minRate) return false;
    if (maxRate != null && rate > maxRate) return false;
    if (keyword != null && !keyword.isEmpty()) {
      String hay =
          (str(row.get("id"))
                  + " "
                  + str(row.get("match_id"))
                  + " "
                  + str(row.get("league_name"))
                  + " "
                  + str(row.get("home_team"))
                  + " "
                  + str(row.get("guest_team")))
              .toLowerCase(Locale.ROOT);
      if (!hay.contains(keyword)) return false;
    }
    return true;
  }

  private boolean isUnlockedForRow(Set<String> unlockedIds, Map<String, Object> row) {
    if (unlockedIds == null || unlockedIds.isEmpty() || row == null) return false;
    String id = recId(row);
    if (!id.isEmpty() && unlockedIds.contains(id)) return true;
    String matchId = str(row.get("match_id"));
    if (matchId.isEmpty()) return false;
    String model = normalizeModel(str(row.get("model")));
    return unlockedIds.contains("pm_" + model + "_" + matchId);
  }

  private String modelMatchKey(Map<String, Object> row) {
    return normalizeModel(str(row.get("model"))) + "|" + str(row.get("match_id"));
  }

  private boolean isFutureMatchTime(String matchTime) {
    if (matchTime == null || matchTime.isBlank()) return false;
    try {
      LocalDateTime dt = LocalDateTime.parse(matchTime.trim(), MATCH_TIME_FMT);
      return dt.isAfter(LocalDateTime.now(SHANGHAI_ZONE));
    } catch (Exception ignored) {
      return false;
    }
  }

  private static class CandidateMarket {
    final String market;
    final String direction;
    final Double handicap;
    final double displayOdds;
    final double decOdds;
    final double prob;

    CandidateMarket(
        String market,
        String direction,
        Double handicap,
        double displayOdds,
        double decOdds,
        double prob) {
      this.market = market;
      this.direction = direction;
      this.handicap = handicap;
      this.displayOdds = displayOdds;
      this.decOdds = decOdds;
      this.prob = prob;
    }

    boolean isTradeable() {
      return decOdds >= 1.32 && decOdds <= 4.20 && prob >= 0.42;
    }

    double score() {
      double ev = prob * decOdds - 1.0;
      return prob * 0.65 + Math.max(-0.15, Math.min(0.30, ev)) * 1.4;
    }
  }

  // ---------------------------------------------------------
  // 5. 工具方法
  // ---------------------------------------------------------

  private QuerySpec parseQuery(Map<String, String> query, boolean admin) {
    Map<String, String> q = new LinkedHashMap<>(query == null ? Collections.emptyMap() : query);

    QuerySpec s = new QuerySpec();

    s.page = positiveInt(q.get("page"), 1, 1, null);
    s.pageSize = positiveInt(q.get("page_size"), admin ? 20 : 12, 1, admin ? 100 : 30);

    s.quick = trim(q.get("quick"));
    s.sport = trim(q.get("sport"));
    s.model = normalizeModel(blankDefault(q.get("model"), properties.getDefaultModel()));

    Map<String, Object> db = new LinkedHashMap<>();

    db.put("q", trim(q.get("q")));
    db.put("model", s.model);
    db.put("sport", s.sport);
    db.put("matchId", trim(q.get("match_id")));
    db.put("stage", trim(q.get("stage")));
    db.put("market", trim(q.get("market")));
    String role = trim(q.get("recommend_role"));
    if (!admin && role.isEmpty()) {
      role = "primary";
    } else if ("all".equalsIgnoreCase(role)) {
      role = "";
    }
    db.put("recommendRole", role);
    db.put("dateFrom", trim(q.get("date_from")));
    db.put("dateTo", trim(q.get("date_to")));
    db.put("status", trim(q.get("status")));
    db.put("quick", s.quick);

    /*
     * H5 默认只看 is_latest=1。
     * 后台 admin 如果没传则不过滤，便于查看历史版本。
     */
    Integer latest = nullableInt(q.get("is_latest"));
    if (!admin && latest == null) {
      latest = 1;
    }
    db.put("isLatest", latest);

    Double min = toDouble(q.get("min_rate"));
    if (min == null && !admin) {
      min = 80.0;
    }

    db.put("minRate", min);
    db.put("maxRate", toDouble(q.get("max_rate")));

    /*
     * 旧前端仍然传 sort_by=match_time。
     * Mapper V5 已将 match_time 改成 DESC，确保展示最新比赛。
     */
    db.put("sortBy", blankDefault(q.get("sort_by"), "match_time"));

    int offset = (s.page - 1) * s.pageSize;
    db.put("offset", offset);
    db.put("limit", s.pageSize);

    s.db = db;
    return s;
  }

  private int intValue(Map<String, Object> row, String key) {
    if (row == null || row.get(key) == null) return 0;
    Object value = row.get(key);
    if (value instanceof Number) return ((Number) value).intValue();
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception e) {
      return 0;
    }
  }

  @Override
  public Map<String, Object> detail(
      String recommendationId, Map<String, String> query, TianwenH5User user) {

    String idText = trim(recommendationId);

    if (idText.isEmpty()) {
      throw new ServiceException("recommendationId不能为空");
    }

    Matcher syntheticMatcher = SYNTHETIC_REC_ID.matcher(idText);
    Map<String, Object> row = null;
    Map<String, Object> preloadedPred = null;
    Long id = null;

    if (syntheticMatcher.matches()) {
      String targetModel = normalizeModel(syntheticMatcher.group(1));
      String targetMatchId = syntheticMatcher.group(2);
      List<Map<String, Object>> candidates =
          recommendationMapper.selectMatchCandidates(targetMatchId, targetModel);
      if (candidates != null && !candidates.isEmpty()) {
        row = applyOverride(candidates.get(0));
        decorateAndScript(row);
        decorateResult(row);
        try {
          id = Long.valueOf(recId(row));
        } catch (Exception ignored) {
          id = null;
        }
      } else {
        try {
          preloadedPred =
              recommendationMapper.selectReplayPrediction(
                  TianwenPredictionView.platformOf(targetModel), targetMatchId);
        } catch (DataAccessException ignored) {
          preloadedPred = null;
        }
        if (preloadedPred == null || preloadedPred.isEmpty()) {
          throw new ServiceException("推荐不存在");
        }
        row = toSyntheticUpcomingRec(preloadedPred);
        if (row == null) {
          throw new ServiceException("推荐不存在");
        }
        decorateAndScript(row);
        decorateResult(row);
      }
    } else {
      try {
        id = Long.valueOf(idText);
      } catch (Exception e) {
        throw new ServiceException("recommendationId格式错误");
      }

      // 1. 当前推荐
      row = recommendationMapper.selectById(id);

      if (row == null) {
        throw new ServiceException("推荐不存在");
      }

      row = applyOverride(row);
      decorateAndScript(row);
      decorateResult(row);
    }

    // 2. 当前推荐是否已经单独解锁（同时兼容数字ID与合成赛前ID）
    boolean manuallyUnlocked = false;
    if (user != null) {
      Set<String> unlockedIds = new HashSet<>(unlockMapper.selectUnlockedIds(user.getId()));
      manuallyUnlocked = unlockedIds.contains(idText) || isUnlockedForRow(unlockedIds, row);
    }

    // 3. 使用V17统一VIP权限
    Map<String, Object> current = mask(row, user, manuallyUnlocked);

    // 4. 同场历史推荐
    String matchId = str(row.get("match_id"));

    List<Map<String, Object>> history = new ArrayList<>();

    if (!matchId.isEmpty() && id != null) {

      List<Map<String, Object>> historyRows =
          recommendationMapper.selectMatchHistory(matchId, id, 20);

      if (historyRows != null) {

        Set<String> unlockedHistoryIds = Collections.emptySet();

        if (user != null) {
          unlockedHistoryIds = new HashSet<>(unlockMapper.selectUnlockedIds(user.getId()));
        }

        final Set<String> unlockedSet = unlockedHistoryIds;

          Map<String, Object> finalRow = row;
          history =
            historyRows.stream()
                .map(this::applyOverride)
                .peek(this::decorateAndScript)
                .peek(this::decorateResult)
                .map(
                    item -> {
                      boolean unlocked = isUnlockedForRow(unlockedSet, item);
                      Map<String, Object> masked = mask(item, user, unlocked);
                      decorateHistoryChange(masked, finalRow);
                      return masked;
                    })
                .collect(Collectors.toList());
      }
    }

    // 5. 比赛/结算摘要
    Map<String, Object> match = buildMatchSummary(row);
    Map<String, Object> out = new LinkedHashMap<>();

    boolean canView = Boolean.TRUE.equals(current.get("canView"));
    Map<String, Object> predView =
        preloadedPred != null
            ? TianwenPredictionView.fromRow(preloadedPred, canView)
            : predictionFor(row, canView);

    out.put("code", 200);
    out.put("item", current);
    out.put("history", history);
    out.put("historyCount", history.size());
    out.put("match", match);
    out.put("auth", auth(user, normalizeModel(str(row.get("model")))));
    out.put("prediction", predView);

    return out;
  }

  @Override
  public Map<String, Object> predictions(String date, String model, TianwenH5User user) {
    return predictions(date, model, 1, user);
  }

  @Override
  public Map<String, Object> predictions(
      String date, String model, Integer days, TianwenH5User user) {
    LocalDate day = predictionDay(date);
    int span = days == null ? 1 : Math.min(Math.max(days, 1), 7);
    String normalizedModel = normalizeModel(model);
    String platform = TianwenPredictionView.platformOf(normalizedModel);
    String startStr = dayStart(day);
    String endStr = day.plusDays(span).toString() + " 00:00:00";
    String cacheKey = platform + "|" + normalizedModel + "|" + startStr + "|" + endStr;

    CompletableFuture<List<Map<String, Object>>> snapshotFuture =
        CompletableFuture.supplyAsync(
            () ->
                getOrRefreshListCache(
                        predictionsCache,
                        refreshingPredictionKeys,
                        cacheKey,
                        () -> {
                          List<Map<String, Object>> raw =
                              loadPredictionRows(platform, normalizedModel, startStr, endStr);
                          long ts = System.currentTimeMillis();
                          return new ListCacheValue(
                              ts + LIST_FRESH_MILLIS, ts + LIST_STALE_MILLIS, raw, false);
                        })
                    .items);

    String recCacheKey = "rec_upcoming|" + normalizedModel + "|" + startStr + "|" + endStr;
    CompletableFuture<List<Map<String, Object>>> upcomingRecFuture =
        CompletableFuture.supplyAsync(
            () ->
                getOrRefreshListCache(
                        predictionsCache,
                        refreshingPredictionKeys,
                        recCacheKey,
                        () -> {
                          List<Map<String, Object>> recRows;
                          try {
                            recRows =
                                recommendationMapper.selectUpcomingRecommendationsByRange(
                                    normalizedModel, startStr, endStr);
                          } catch (DataAccessException e) {
                            recRows = List.of();
                          }
                          if (recRows == null) recRows = List.of();
                          long ts = System.currentTimeMillis();
                          return new ListCacheValue(
                              ts + LIST_FRESH_MILLIS, ts + LIST_STALE_MILLIS, recRows, false);
                        })
                    .items);

    List<Map<String, Object>> rows = snapshotFuture.join();
    List<Map<String, Object>> upcomingRecs = upcomingRecFuture.join();

    Set<String> unlockedMatches = unlockedMatches(user, rows);
    Set<String> unlockedRecIds =
        user == null || upcomingRecs.isEmpty()
            ? Collections.emptySet()
            : new HashSet<>(unlockMapper.selectUnlockedIds(user.getId()));

    List<Map<String, Object>> items = new ArrayList<>(rows.size() + upcomingRecs.size());
    Set<String> seenMatchIds = new HashSet<>();
    for (Map<String, Object> row : rows) {
      String mid = str(row.get("match_id"));
      if (!mid.isEmpty()) seenMatchIds.add(mid);
      boolean allowed = predictionAllowed(user, row, unlockedMatches);
      Map<String, Object> view = TianwenPredictionView.fromRow(row, allowed);
      view.putIfAbsent("sport", "football");
      items.add(view);
    }

    for (Map<String, Object> rec : upcomingRecs) {
      String mid = str(rec.get("match_id"));
      if (!mid.isEmpty() && seenMatchIds.contains(mid)) {
        continue;
      }
      if (!mid.isEmpty()) seenMatchIds.add(mid);
      items.add(predictionFromRecommendation(rec, user, unlockedRecIds));
    }

    items.sort(
        Comparator.comparing(
            item -> str(item.get("match_time")),
            Comparator.nullsLast(String::compareTo)));

    List<Map<String, Object>> availableDays = cachedPredictionDays(platform, normalizedModel);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("date", day.toString());
    out.put("days", span);
    out.put("dateEnd", day.plusDays(span - 1L).toString());
    out.put("model", normalizedModel);
    out.put("platform", platform);
    out.put("items", items);
    out.put("count", items.size());
    out.put("availableDays", availableDays);
    return out;
  }

  private Map<String, Object> predictionFromRecommendation(
      Map<String, Object> rec, TianwenH5User user, Set<String> unlockedRecIds) {
    String id = recId(rec);
    boolean manuallyUnlocked = user != null && unlockedRecIds.contains(id);
    boolean allowed = false;
    try {
      TianwenAccessResult access = accessPolicyService.check(user, rec, manuallyUnlocked);
      allowed = access != null && access.isAllowed();
    } catch (Exception ignored) {
      allowed = false;
    }

    Map<String, Object> market = new LinkedHashMap<>();
    market.put("label", blankDefault(str(rec.get("market")), "亚盘"));
    market.put("locked", !allowed);
    if (allowed) {
      market.put("direction", rec.get("direction"));
      market.put("available", rec.get("direction") != null && !str(rec.get("direction")).isBlank());
      market.put("handicap", rec.get("display_handicap") != null ? rec.get("display_handicap") : rec.get("handicap"));
      market.put("odds", rec.get("odds"));
    }

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("prediction_id", "rec_" + id);
    view.put("model_platform", TianwenPredictionView.platformOf(str(rec.get("model"))));
    view.put("match_id", str(rec.get("match_id")));
    view.put("recommendation_id", rec.get("id"));
    view.put("league_name", rec.get("league_name"));
    view.put("match_time", rec.get("match_time"));
    view.put("home_team", rec.get("home_team"));
    view.put("guest_team", rec.get("guest_team"));
    view.put("sport", blankDefault(str(rec.get("sport")), "football"));
    view.put("stage", rec.get("stage"));
    view.put("rate", rec.get("rate"));
    view.put("locked", !allowed);
    view.put("markets", List.of(market));
    return view;
  }

  private List<Map<String, Object>> cachedPredictionDays(String platform, String normalizedModel) {
    return getOrRefreshListCache(
            predictionDaysCache,
            refreshingPredictionDaysKeys,
            "days|" + platform + "|" + normalizedModel,
            () -> {
              Map<String, Integer> merged = new TreeMap<>(Comparator.reverseOrder());
              for (Map<String, Object> row : predictionDays(platform)) {
                String d = str(row.get("day"));
                if (!d.isEmpty()) merged.put(d, intValue(row.get("matches"), 0));
              }
              try {
                List<Map<String, Object>> recDays =
                    recommendationMapper.selectRecommendationDays(normalizedModel);
                if (recDays != null) {
                  for (Map<String, Object> row : recDays) {
                    String d = str(row.get("day"));
                    if (!d.isEmpty()) {
                      int cnt = intValue(row.get("matches"), 0);
                      merged.merge(d, cnt, Math::max);
                    }
                  }
                }
              } catch (DataAccessException ignored) {}
              List<Map<String, Object>> days = new ArrayList<>();
              int count = 0;
              for (Map.Entry<String, Integer> e : merged.entrySet()) {
                if (count++ >= 90) break;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("day", e.getKey());
                item.put("matches", e.getValue());
                days.add(item);
              }
              long ts = System.currentTimeMillis();
              return new ListCacheValue(ts + 60_000L, ts + LIST_STALE_MILLIS, days, false);
            })
        .items;
  }

  private List<Map<String, Object>> loadPredictionRows(
      String platform, String normalizedModel, String startStr, String endStr) {
    List<Map<String, Object>> rows;
    try {
      rows = recommendationMapper.selectPredictionsByDate(platform, startStr, endStr);
    } catch (DataAccessException e) {
      throw new ServiceException("赛前预测还没有写入当前数据库");
    }
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<String> matchIds =
        rows.stream()
            .map(r -> str(r.get("match_id")))
            .filter(id -> !id.isEmpty())
            .distinct()
            .collect(Collectors.toList());
    Map<String, Map<String, Object>> recByMatch = new HashMap<>();
    if (!matchIds.isEmpty()) {
      try {
        List<Map<String, Object>> recRows =
            recommendationMapper.selectLatestRecommendationsByMatchIds(normalizedModel, matchIds);
        if (recRows != null) {
          for (Map<String, Object> rec : recRows) {
            String mid = str(rec.get("match_id"));
            if (!mid.isEmpty()) {
              recByMatch.put(mid, rec);
            }
          }
        }
      } catch (DataAccessException ignored) {
        // 批量补充推荐关联失败时不阻断赛前预测展示
      }
    }
    List<Map<String, Object>> enriched = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      Map<String, Object> copy = new LinkedHashMap<>(row);
      Map<String, Object> rec = recByMatch.get(str(row.get("match_id")));
      if (rec != null) {
        copy.put("recommendation_id", rec.get("recommendation_id"));
        copy.put("ranking_status", rec.get("ranking_status"));
        copy.put("ranking_market", rec.get("ranking_market"));
        copy.put("ranking_direction", rec.get("ranking_direction"));
        copy.put("ranking_handicap", rec.get("ranking_handicap"));
      }
      enriched.add(copy);
    }
    return enriched;
  }

  @Override
  public Map<String, Object> exportPredictions(String date, String model) {
    LocalDate day = predictionDay(date);
    String normalizedModel = normalizeModel(model);
    String platform = TianwenPredictionView.platformOf(normalizedModel);
    List<Map<String, Object>> rows =
        loadPredictionRows(platform, normalizedModel, dayStart(day), dayEnd(day));
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      items.add(TianwenPredictionView.fromRow(row, true));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("date", day.toString());
    out.put("model", normalizedModel);
    out.put("platform", platform);
    out.put("items", items);
    out.put("count", items.size());
    out.put("note", "这是赛前快照算出的方向，结算状态只描述已经结束的比赛，不代表之后的命中率。");
    return out;
  }

  @Override
  public Map<String, Object> adminPredictions(String date, String model) {
    String platform = TianwenPredictionView.platformOf(model);
    List<Map<String, Object>> days = predictionDays(platform);
    String resolved = date;
    if (resolved == null || resolved.isBlank()) {
      resolved = latestPredictionDay(days);
    }
    Map<String, Object> out = exportPredictions(resolved, model);
    out.put("days", days);
    out.put("note", "管理后台完整分析，不受 H5 解锁限制。综合分推荐和赛前三个方向是两套结果。");
    if (intValue(out.get("count"), 0) == 0 && !days.isEmpty()) {
      out.put("emptyHint", "这一天没有赛前快照。点上面有场次的日期看历史。");
    }
    return out;
  }

  private List<Map<String, Object>> predictionDays(String platform) {
    List<Map<String, Object>> rows;
    try {
      rows = recommendationMapper.selectPredictionDays(platform);
    } catch (DataAccessException e) {
      return List.of();
    }
    if (rows == null) {
      return List.of();
    }
    List<Map<String, Object>> days = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      String day = str(row.get("day"));
      if (day.isEmpty()) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("day", day);
      item.put("matches", intValue(row.get("matches"), 0));
      days.add(item);
    }
    return days;
  }

  private String latestPredictionDay(List<Map<String, Object>> days) {
    if (days == null || days.isEmpty()) {
      return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    }
    return str(days.get(0).get("day"));
  }

  @Override
  public Map<String, Object> adminPredictionDetail(String model, String matchId) {
    if (matchId == null || matchId.isBlank()) {
      throw new ServiceException("matchId 不能为空");
    }
    String normalized = normalizeModel(model);
    String id = matchId.trim();
    Map<String, Object> row = null;
    try {
      row =
          recommendationMapper.selectReplayPrediction(
              TianwenPredictionView.platformOf(normalized), id);
      if (row != null && !row.isEmpty()) {
        List<Map<String, Object>> recRows =
            recommendationMapper.selectLatestRecommendationsByMatchIds(normalized, List.of(id));
        if (recRows != null && !recRows.isEmpty()) {
          Map<String, Object> copy = new LinkedHashMap<>(row);
          Map<String, Object> rec = recRows.get(0);
          copy.put("recommendation_id", rec.get("recommendation_id"));
          copy.put("ranking_status", rec.get("ranking_status"));
          copy.put("ranking_market", rec.get("ranking_market"));
          copy.put("ranking_direction", rec.get("ranking_direction"));
          copy.put("ranking_handicap", rec.get("ranking_handicap"));
          row = copy;
        }
      }
    } catch (DataAccessException e) {
      throw new ServiceException("赛前预测还没有写入当前数据库");
    }
    List<Map<String, Object>> rankingRows;
    try {
      rankingRows = recommendationMapper.selectMatchCandidates(id, normalized);
    } catch (DataAccessException e) {
      rankingRows = List.of();
    }
    if (rankingRows == null) {
      rankingRows = List.of();
    }
    if ((row == null || row.isEmpty()) && rankingRows.isEmpty()) {
      throw new ServiceException("这场还没有赛前分析快照");
    }
    List<Map<String, Object>> ranking = new ArrayList<>();
    for (Map<String, Object> rec : rankingRows) {
      ranking.add(adminRankingView(rec));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("model", normalized);
    out.put("platform", TianwenPredictionView.platformOf(normalized));
    out.put("item", row == null || row.isEmpty() ? null : TianwenPredictionView.fromRow(row, true));
    out.put("ranking", ranking);
    out.put(
        "note",
        ranking.isEmpty()
            ? "这场没有过综合分门槛，所以没有入库推荐。下面是赛前快照的泊松分析。"
            : "综合分推荐和泊松三个方向是两套结果，不要混成一条命中率。");
    return out;
  }

  private Map<String, Object> adminRankingView(Map<String, Object> row) {
    decorateAndScript(row);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("recommendation_id", row.get("id"));
    view.put("stage", row.get("stage"));
    view.put("market", row.get("market"));
    view.put("direction", row.get("direction"));
    view.put(
        "handicap",
        row.get("display_handicap") != null ? row.get("display_handicap") : row.get("handicap"));
    view.put("odds", row.get("odds"));
    view.put("rate", row.get("rate"));
    view.put("final_score", row.get("final_score"));
    view.put("recommend_role", row.get("recommend_role"));
    view.put("resultText", row.get("resultText"));
    view.put("resultType", row.get("resultType"));
    view.put("hit", row.get("hit"));
    view.put("settled", row.get("settled"));
    view.put("actualScore", row.get("actualScore"));
    view.put("script", row.get("script"));
    view.put("create_time", row.get("create_time"));
    return view;
  }

  private Map<String, Object> predictionFor(Map<String, Object> recommendation, boolean allowed) {
    String matchId = str(recommendation.get("match_id"));
    if (matchId.isEmpty()) {
      return null;
    }
    try {
      Map<String, Object> row =
          recommendationMapper.selectReplayPrediction(
              TianwenPredictionView.platformOf(str(recommendation.get("model"))), matchId);
      if (row == null || row.isEmpty()) {
        return null;
      }
      return TianwenPredictionView.fromRow(row, allowed);
    } catch (DataAccessException e) {
      return null;
    }
  }

  private boolean predictionAllowed(
      TianwenH5User user, Map<String, Object> row, Set<String> unlockedMatches) {
    Map<String, Object> gate = new LinkedHashMap<>();
    gate.put("result_status", TianwenPredictionView.settled(row) ? "1" : "0");
    gate.put("is_latest", "1");
    gate.put("rate", null);
    boolean unlocked = unlockedMatches.contains(str(row.get("match_id")));
    TianwenAccessResult access = accessPolicyService.check(user, gate, unlocked);
    return access != null && access.isAllowed();
  }

  private Set<String> unlockedMatches(TianwenH5User user, List<Map<String, Object>> rows) {
    if (user == null || user.getId() == null || rows.isEmpty()) {
      return Set.of();
    }
    List<String> matchIds = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      String matchId = str(row.get("match_id"));
      if (!matchId.isEmpty()) {
        matchIds.add(matchId);
      }
    }
    if (matchIds.isEmpty()) {
      return Set.of();
    }
    List<String> unlocked = unlockMapper.selectUnlockedMatchIds(user.getId(), matchIds);
    Set<String> result = unlocked == null ? new HashSet<>() : new HashSet<>(unlocked);
    List<String> unlockedIds = unlockMapper.selectUnlockedIds(user.getId());
    if (unlockedIds != null) {
      Set<String> targetSet = new HashSet<>(matchIds);
      for (String uid : unlockedIds) {
        if (uid == null) continue;
        Matcher m = SYNTHETIC_REC_ID.matcher(uid.trim());
        if (m.matches() && targetSet.contains(m.group(2))) {
          result.add(m.group(2));
        }
      }
    }
    return result;
  }

  private LocalDate predictionDay(String date) {
    if (date == null || date.isBlank()) {
      return LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }
    try {
      return LocalDate.parse(date.trim());
    } catch (DateTimeParseException e) {
      throw new ServiceException("date格式应为yyyy-MM-dd");
    }
  }

  private String dayStart(LocalDate day) {
    return day.toString() + " 00:00:00";
  }

  private String dayEnd(LocalDate day) {
    return day.plusDays(1).toString() + " 00:00:00";
  }

  private void decorateResult(Map<String, Object> row) {

    int status = intValue(row.get("result_status"), 0);

    String resultText;
    String resultType;
    boolean settled = false;
    boolean hit = false;

    switch (status) {
      case 1:
        resultText = "全赢";
        resultType = "win";
        settled = true;
        hit = true;
        break;

      case 2:
        resultText = "全输";
        resultType = "loss";
        settled = true;
        break;

      case 3:
        resultText = "走水";
        resultType = "push";
        settled = true;
        // V22统一口径：走水也计入命中，但前端仍单独显示“走水”
        hit = true;
        break;

      case 4:
        resultText = "半赢";
        resultType = "half_win";
        settled = true;
        hit = true;
        break;

      case 5:
        resultText = "半输";
        resultType = "half_loss";
        settled = true;
        break;

      case 9:
        resultText = "已作废";
        resultType = "obsolete";
        break;

      default:
        resultText = "待结算";
        resultType = "pending";
        break;
    }

    row.put("resultText", resultText);

    row.put("resultType", resultType);

    row.put("settled", settled);

    row.put("hit", hit);

    // 最终比分
    Object home = row.get("final_home_score");

    Object guest = row.get("final_guest_score");

    if (home != null
        && guest != null
        && !"".equals(String.valueOf(home))
        && !"".equals(String.valueOf(guest))) {

      row.put("actualScore", String.valueOf(home) + "-" + String.valueOf(guest));

    } else {
      row.put("actualScore", "");
    }

    // 推荐时比分
    Object rh = row.get("recommend_home_score");

    Object rg = row.get("recommend_guest_score");

    if (rh != null
        && rg != null
        && !"".equals(String.valueOf(rh))
        && !"".equals(String.valueOf(rg))) {

      row.put("recommendScore", String.valueOf(rh) + "-" + String.valueOf(rg));

    } else {
      row.put("recommendScore", "");
    }
  }

  private void decorateHistoryChange(Map<String, Object> history, Map<String, Object> current) {

    String oldDirection = str(history.get("direction"));

    String newDirection = str(current.get("direction"));

    String oldHandicap = str(history.get("display_handicap"));

    String newHandicap = str(current.get("display_handicap"));

    String oldOdds = str(history.get("odds"));

    String newOdds = str(current.get("odds"));

    boolean directionChanged =
        !oldDirection.isEmpty() && !newDirection.isEmpty() && !oldDirection.equals(newDirection);

    boolean handicapChanged = !oldHandicap.equals(newHandicap);

    boolean oddsChanged = !oldOdds.equals(newOdds);

    Map<String, Object> change = new LinkedHashMap<>();

    change.put("directionChanged", directionChanged);

    change.put("handicapChanged", handicapChanged);

    change.put("oddsChanged", oddsChanged);

    if (directionChanged) {
      change.put("directionText", oldDirection + " → " + newDirection);
    }

    if (handicapChanged) {
      change.put("handicapText", emptyDash(oldHandicap) + " → " + emptyDash(newHandicap));
    }

    if (oddsChanged) {
      change.put("oddsText", emptyDash(oldOdds) + " → " + emptyDash(newOdds));
    }

    history.put("change", change);
  }

  private Map<String, Object> buildMatchSummary(Map<String, Object> row) {

    Map<String, Object> m = new LinkedHashMap<>();

    m.put("matchId", row.get("match_id"));

    m.put("leagueName", row.get("league_name"));

    m.put("matchTime", row.get("match_time"));

    m.put("homeTeam", row.get("home_team"));

    m.put("guestTeam", row.get("guest_team"));

    m.put("finalHomeScore", row.get("final_home_score"));

    m.put("finalGuestScore", row.get("final_guest_score"));

    m.put("actualScore", row.get("actualScore"));

    m.put("resultStatus", row.get("result_status"));

    m.put("resultText", row.get("resultText"));

    m.put("resultType", row.get("resultType"));

    m.put("settled", row.get("settled"));

    m.put("hit", row.get("hit"));

    m.put("settleTime", row.get("settle_time"));

    return m;
  }

  private String emptyDash(String value) {
    return value == null || value.trim().isEmpty() ? "-" : value;
  }

  private int intValue(Object value, int defaultValue) {

    if (value == null) {
      return defaultValue;
    }

    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception e) {
      return defaultValue;
    }
  }

  @Override
  public Map<String, Object> adminList(Map<String, String> query) {
    QuerySpec spec = parseQuery(query, true);

    // 后台同样改成数据库直接分页，避免一次把全部推荐加载进 JVM。
    List<Map<String, Object>> rows = recommendationMapper.selectFastList(spec.db);
    rows = applyOverrides(rows);
    rows.forEach(this::decorateAndScript);

    long total = recommendationMapper.countFast(spec.db);

    Map<String, Object> countRow = recommendationMapper.selectFastCounts(spec.db);
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("all", intValue(countRow, "all_count"));
    counts.put("upcoming", intValue(countRow, "upcoming_count"));
    counts.put("pending", intValue(countRow, "pending_count"));
    counts.put("hit", intValue(countRow, "hit_count"));
    counts.put("finished", intValue(countRow, "finished_count"));
    counts.put("obsolete", intValue(countRow, "obsolete_count"));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("items", rows);
    out.put("count", total);
    out.put("counts", counts);
    out.put(
        "pagination",
        pagination(
            spec.page,
            spec.pageSize,
            (int) Math.min(Integer.MAX_VALUE, total),
            ((long) spec.page * spec.pageSize) < total));
    out.put("source", source(spec.model));
    return out;
  }

  /** 兼容原 /api/recommendations，但直接查询 MySQL。 */
  @Override
  public Map<String, Object> legacyRecommendations(Map<String, String> query) {
    return list(query, null);
  }

  @Override
  public Map<String, Object> summary(Map<String, String> query) {
    QuerySpec spec = parseQuery(query, true);

    // Summary 只做聚合，不再读取整张推荐表。
    Map<String, Object> countRow = recommendationMapper.selectFastCounts(spec.db);

    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("all", intValue(countRow, "all_count"));
    counts.put("upcoming", intValue(countRow, "upcoming_count"));
    counts.put("pending", intValue(countRow, "pending_count"));
    counts.put("hit", intValue(countRow, "hit_count"));
    counts.put("finished", intValue(countRow, "finished_count"));
    counts.put("obsolete", intValue(countRow, "obsolete_count"));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("count", counts.get("all"));
    out.put("counts", counts);
    // 旧前端如果读取 dailyStats，保留字段，极速版不再为它扫描全表。
    out.put("dailyStats", Collections.emptyMap());
    out.put("source", source(spec.model));
    return out;
  }

  @Override
  public Map<String, Object> review(Map<String, String> query) {
    Map<String, String> q = new LinkedHashMap<>(query == null ? Collections.emptyMap() : query);
    q.putIfAbsent("status", "finished");
    return legacyRecommendations(q);
  }

  @Override
  public Map<String, Object> updateOverride(
      String recommendationId, Map<String, Object> fields, String adminUsername) {
    String idText = trim(recommendationId);
    if (idText.isEmpty()) {
      throw new ServiceException("recommendationId不能为空");
    }

    final Long recommendationPk;
    try {
      recommendationPk = Long.valueOf(idText);
    } catch (Exception e) {
      throw new ServiceException("recommendationId格式错误");
    }

    if (recommendationMapper.selectById(recommendationPk) == null) {
      throw new ServiceException("推荐不存在");
    }
    Map<String, Object> clean = cleanOverride(fields);
    if (clean.isEmpty()) throw new ServiceException("没有可保存的修改字段");
    TianwenRecommendOverride old = overrideMapper.selectById(recommendationId);
    Map<String, Object> merged =
        old == null ? new LinkedHashMap<>() : parseJsonMap(old.getOverrideJson());
    deepMerge(merged, clean);
    TianwenRecommendOverride entity = new TianwenRecommendOverride();
    entity.setRecommendationId(recommendationId);
    entity.setOverrideJson(toJson(merged));
    entity.setAdminUsername(adminUsername);
    overrideMapper.upsert(entity);
    clearStatsCache();

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("msg", "推荐修改已保存");
    out.put("recommendationId", recommendationId);
    out.put("override", merged);
    out.put("updatedAt", LocalDateTime.now().toString());
    return out;
  }

  @Override
  public Map<String, Object> resetOverride(String recommendationId) {
    if (trim(recommendationId).isEmpty()) throw new ServiceException("recommendationId不能为空");
    overrideMapper.deleteById(recommendationId);
    clearStatsCache();
    return new LinkedHashMap<>(Map.of("code", 200, "msg", "已恢复数据库原始推荐"));
  }

  private void decorateAndScript(Map<String, Object> m) {
    decorate(m);
    m.putIfAbsent("display_handicap", m.get("handicap"));
    m.put("script", inferScript(m));
  }

  /**
   * server.py 原页面依赖 script。旧推荐表没有 script_json，故在 Java 端按同场推荐数据重建基础剧本。 后续建议 worker 入库
   * script_json，这里即可直接读取精确剧本。
   */
  private Map<String, Object> inferScript(Map<String, Object> row) {
    String direction = str(row.get("direction"));
    String market = str(row.get("market"));
    Double line = toDouble(row.get("handicap"));
    String winner = "中性";
    if (direction.contains("主胜")) winner = "主队占优";
    else if (direction.contains("客胜") || direction.contains("主负")) winner = "客队占优";
    else if (direction.contains("平局")) winner = "均势";

    String goalBias = "中等进球";
    if (direction.contains("大")) goalBias = "偏大";
    else if (direction.contains("小")) goalBias = "偏小";
    Double totalGoal = null;
    if ("大小球".equals(market) && line != null) {
      totalGoal = direction.contains("大") ? line + 0.25 : Math.max(0.5, line - 0.25);
    }
    List<String> scores = scoreRange(direction, totalGoal, line);
    if (row.get("score_home") != null && row.get("score_away") != null) {
      String poissonScore = str(row.get("score_home")) + "-" + str(row.get("score_away"));
      List<String> mergedScores = new ArrayList<>(3);
      mergedScores.add(poissonScore);
      for (String s : scores) {
        if (!poissonScore.equals(s) && mergedScores.size() < 3) {
          mergedScores.add(s);
        }
      }
      scores = mergedScores;
    }
    Map<String, Object> script = new LinkedHashMap<>();
    script.put("winner", winner);
    script.put("goal_bias", goalBias);
    script.put("total_goal_est", totalGoal);
    script.put("line", line);
    script.put("score_range", scores);
    return script;
  }

  private List<String> scoreRange(String direction, Double totalGoal, Double line) {
    List<String> base;
    if (direction.contains("主胜"))
      base = new ArrayList<>(List.of("2-0", "2-1", "1-0", "3-1", "1-1"));
    else if (direction.contains("客胜") || direction.contains("主负"))
      base = new ArrayList<>(List.of("0-2", "1-2", "0-1", "1-3", "1-1"));
    else if (direction.contains("平局")) base = new ArrayList<>(List.of("1-1", "0-0", "2-2"));
    else base = new ArrayList<>(List.of("2-1", "1-1", "1-0", "1-2", "2-2"));
    if (direction.contains("小") && line != null) {
      base.sort(Comparator.comparingInt(this::scoreTotal));
      base =
          base.stream().filter(x -> scoreTotal(x) < Math.ceil(line)).collect(Collectors.toList());
    } else if (direction.contains("大") && line != null) {
      base.sort((a, b) -> Integer.compare(scoreTotal(b), scoreTotal(a)));
      base =
          base.stream().filter(x -> scoreTotal(x) > Math.floor(line)).collect(Collectors.toList());
    }
    return base.stream().limit(3).collect(Collectors.toList());
  }

  private int scoreTotal(String s) {
    try {
      String[] a = s.split("-");
      return Integer.parseInt(a[0]) + Integer.parseInt(a[1]);
    } catch (Exception e) {
      return 99;
    }
  }

  private Map<String, Object> applyOverride(Map<String, Object> row) {
    Map<String, Object> m = new LinkedHashMap<>(row);
    TianwenRecommendOverride ov = overrideMapper.selectById(recId(m));
    if (ov != null) {
      deepMerge(m, parseJsonMap(ov.getOverrideJson()));
      m.put("overrideApplied", true);
      m.put("overrideUpdatedAt", ov.getUpdatedAt());
      m.put("overrideAdmin", ov.getAdminUsername());
    }
    return m;
  }

  private List<Map<String, Object>> applyOverrides(List<Map<String, Object>> rows) {
    if (rows == null || rows.isEmpty()) return new ArrayList<>();
    List<String> ids = rows.stream().map(this::recId).filter(x -> !x.isEmpty()).toList();
    Map<String, TianwenRecommendOverride> map =
        overrideMapper.selectByIds(ids).stream()
            .collect(
                Collectors.toMap(
                    TianwenRecommendOverride::getRecommendationId, x -> x, (a, b) -> a));
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> m = new LinkedHashMap<>(row);
      TianwenRecommendOverride ov = map.get(recId(m));
      if (ov != null) {
        deepMerge(m, parseJsonMap(ov.getOverrideJson()));
        m.put("overrideApplied", true);
        m.put("overrideUpdatedAt", ov.getUpdatedAt());
        m.put("overrideAdmin", ov.getAdminUsername());
      }
      out.add(m);
    }
    return out;
  }

  private void decorate(Map<String, Object> m) {
    if ("0".equals(str(m.getOrDefault("is_latest", "1")))) m.put("result_status", 9);
    String s = str(m.getOrDefault("result_status", "0"));
    String label = "待结算", clazz = "pending";
    if ("3".equals(s)) {
      label = "走水";
      clazz = "push";
    } else if (HIT.contains(s)) {
      label = "命中";
      clazz = "win";
    } else if (LOST.contains(s)) {
      label = "未中";
      clazz = "lost";
    } else if ("9".equals(s)) {
      label = "作废";
      clazz = "obsolete";
    }
    m.put("result", new LinkedHashMap<>(Map.of("label", label, "className", clazz)));
  }

  /** V17：列表/首页/详情统一使用会员权限中心。 */
  private Map<String, Object> mask(
      Map<String, Object> item, TianwenH5User user, boolean manuallyUnlocked) {

    Map<String, Object> m = deepCopy(item);

    TianwenAccessResult access;
    try {
      access = accessPolicyService.check(user, m, manuallyUnlocked);
    } catch (Exception e) {
      log.warn(
          "推荐权限核验异常 recommendationId={}, userId={}, vipLevel={}: {}",
          recId(m),
          user == null ? "anonymous" : user.getId(),
          user == null ? 0 : Objects.requireNonNullElse(user.getVipLevel(), 0),
          e.getMessage());
      throw new ServiceException("推荐权限核验异常，请稍后重试");
    }

    if (access == null) {
      log.warn("推荐权限核验结果为空 recommendationId={}", recId(m));
      throw new ServiceException("推荐权限核验异常，请稍后重试");
    }

    boolean upcoming =
        "0".equals(str(m.getOrDefault("result_status", "0")))
            && isFutureMatchTime(str(m.get("match_time")));
    m.put("isUpcoming", upcoming);
    Map<String, Object> accessMap = access.toMap();
    m.put("requiredVipName", accessMap.get("requiredVipName"));

    m.put("access", accessMap);
    m.put("locked", !access.isAllowed());
    m.put("canView", access.isAllowed());
    m.put("accessType", access.getAccessType());

    if (access.isAllowed()) {
      return m;
    }

    m.put("unlockAmount", properties.getPaidUnlockAmount());

    m.put("lockReason", access.getReason());

    m.put("direction", "解锁后查看");
    m.put("handicap", null);
    m.put("display_handicap", null);
    m.put("odds", null);

    Map<String, Object> script = new LinkedHashMap<>();

    script.put("winner", "解锁后查看");
    script.put("goal_bias", "解锁后查看");
    script.put("total_goal_est", null);
    script.put("line", null);
    script.put("score_range", new ArrayList<>());

    m.put("script", script);

    return m;
  }

  private Map<String, Integer> counts(List<Map<String, Object>> items) {
    Map<String, Integer> c = new LinkedHashMap<>();
    c.put("all", 0);
    c.put("pending", 0);
    c.put("hit", 0);
    c.put("finished", 0);
    c.put("obsolete", 0);
    for (Map<String, Object> i : items) {
      c.put("all", c.get("all") + 1);
      String k = quickKey(i);
      if (!"finished".equals(k)) c.put(k, c.getOrDefault(k, 0) + 1);
      if (isSettled(i)) c.put("finished", c.get("finished") + 1);
    }
    return c;
  }

  private String quickKey(Map<String, Object> i) {
    if (isObsolete(i)) return "obsolete";
    String s = str(i.get("result_status"));
    if ("0".equals(s)) return "pending";
    if (HIT.contains(s)) return "hit";
    return isSettled(i) ? "finished" : "pending";
  }

  private boolean isSettled(Map<String, Object> i) {
    return FINISHED.contains(str(i.get("result_status")));
  }

  private boolean isObsolete(Map<String, Object> i) {
    return "9".equals(str(i.get("result_status")))
        || "0".equals(str(i.getOrDefault("is_latest", "1")));
  }

  private Map<String, Object> dailyPerformance(List<Map<String, Object>> items) {
    List<Map<String, Object>> settled =
        items.stream().filter(this::isSettled).collect(Collectors.toList());
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("settled", settled.size());
    d.put("bestMarket", bestStat(settled, x -> str(x.get("market"))));
    d.put(
        "bestRateBucket",
        bestStat(
            settled,
            x -> {
              double r = Optional.ofNullable(toDouble(x.get("rate"))).orElse(0D);
              if (r >= 98) return "98-100";
              if (r >= 95) return "95-97.99";
              if (r >= 93) return "93-94.99";
              if (r >= 90) return "90-92.99";
              if (r >= 85) return "85-89.99";
              if (r >= 80) return "80-84.99";
              return "<80";
            }));
    return d;
  }

  private Map<String, Object> bestStat(
      List<Map<String, Object>> items,
      java.util.function.Function<Map<String, Object>, String> keyFn) {
    Map<String, int[]> stats = new LinkedHashMap<>();
    for (Map<String, Object> x : items) {
      String k = keyFn.apply(x);
      if (k == null || k.isBlank()) continue;
      int[] a = stats.computeIfAbsent(k, z -> new int[2]);
      a[1]++;
      if (HIT.contains(str(x.get("result_status")))) a[0]++;
    }
    return stats.entrySet().stream()
        .max(
            Comparator.comparingDouble(
                e -> e.getValue()[1] == 0 ? 0 : (double) e.getValue()[0] / e.getValue()[1]))
        .map(
            e -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("label", e.getKey());
              m.put("hit", e.getValue()[0]);
              m.put("total", e.getValue()[1]);
              m.put(
                  "hitRate",
                  e.getValue()[1] == 0
                      ? 0
                      : Math.round(e.getValue()[0] * 10000D / e.getValue()[1]) / 100D);
              return m;
            })
        .orElse(null);
  }

  private Map<String, Object> source(String model) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("mode", "mysql-fast");
    s.put("table", "football_ai_recommend_record");
    s.put("model", model);
    s.put("modelLabel", "nami".equals(model) ? "纳米" : "捷报");
    return s;
  }

  private Map<String, Object> auth(TianwenH5User u, String model) {
    Map<String, Object> a = new LinkedHashMap<>();

    a.put("loggedIn", u != null);
    a.put("freeViewsRemaining", u == null || u.getFreeViewsRemaining() == null
        ? 0
        : u.getFreeViewsRemaining());
    a.put("model", model);

    TianwenVipLevel rawLevel =
        TianwenVipLevel.of(u == null ? null : u.getVipLevel());

    boolean vipActive = isVipActive(u, rawLevel);
    TianwenVipLevel effective =
        vipActive ? rawLevel : TianwenVipLevel.NORMAL;

    a.put("vipLevel", effective.getLevel());
    a.put("vipName", effective.getLabel());
    a.put("vipActive", vipActive);
    a.put("vipStartAt", u == null ? null : u.getVipStartAt());
    a.put("vipExpireAt", u == null ? null : u.getVipExpireAt());
    a.put("vipMaxFreeRate", effective.getMaxFreeRate());
    a.put("vipAllAccess", effective.isAllAccess());

    // 调试/前端展示都很有用：保留数据库原始等级
    a.put("rawVipLevel", rawLevel.getLevel());
    a.put("rawVipName", rawLevel.getLabel());

    return a;
  }

  private boolean isVipActive(TianwenH5User user, TianwenVipLevel level) {
    if (user == null || level == null || level == TianwenVipLevel.NORMAL) {
      return false;
    }

    LocalDateTime expireAt = user.getVipExpireAt();
    return expireAt == null || expireAt.isAfter(LocalDateTime.now());
  }

  private Map<String, Object> pagination(int p, int ps, int total, boolean more) {
    return new LinkedHashMap<>(Map.of("page", p, "pageSize", ps, "total", total, "hasMore", more));
  }

  private Map<String, Object> cleanOverride(Map<String, Object> fields) {
    if (fields == null) return new LinkedHashMap<>();
    Set<String> allowed =
        Set.of(
            "league_name",
            "match_time",
            "home_team",
            "guest_team",
            "recommend_role",
            "stage",
            "market",
            "direction",
            "handicap",
            "display_handicap",
            "odds",
            "rate",
            "final_score",
            "result_status",
            "is_latest",
            "match_score",
            "final_home_score",
            "final_guest_score",
            "home_red",
            "guest_red",
            "script.winner",
            "script.goal_bias",
            "script.total_goal_est",
            "script.line",
            "script.score_range");
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : fields.entrySet()) {
      if (!allowed.contains(e.getKey())) continue;
      if (e.getKey().startsWith("script.")) {
        Map<String, Object> s = map(out.get("script"));
        s.put(e.getKey().substring(7), e.getValue());
        out.put("script", s);
      } else out.put(e.getKey(), e.getValue());
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object v) {
    return v instanceof Map ? new LinkedHashMap<>((Map<String, Object>) v) : new LinkedHashMap<>();
  }

  private Map<String, Object> parseJsonMap(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }

  private String toJson(Object v) {
    try {
      return objectMapper.writeValueAsString(v);
    } catch (Exception e) {
      throw new ServiceException("JSON序列化失败");
    }
  }

  @SuppressWarnings("unchecked")
  private void deepMerge(Map<String, Object> a, Map<String, Object> b) {
    for (Map.Entry<String, Object> e : b.entrySet()) {
      if (e.getValue() instanceof Map && a.get(e.getKey()) instanceof Map) {
        deepMerge((Map<String, Object>) a.get(e.getKey()), (Map<String, Object>) e.getValue());
      } else a.put(e.getKey(), e.getValue());
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> deepCopy(Map<String, Object> m) {
    return objectMapper.convertValue(m, new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  private String recId(Map<String, Object> m) {
    return str(m.get("id")).isEmpty() ? str(m.get("match_id")) : str(m.get("id"));
  }

  private String normalizeModel(String v) {
    String s = trim(v).toLowerCase(Locale.ROOT);
    return "nami".equals(s) ? "nami" : "jiebao";
  }

  private void clearStatsCache() {
    cacheEpoch.incrementAndGet();
    statsCache.clear();
    listCache.clear();
    homeLatestCache.clear();
  }

  private ListCacheValue computeListValue(QuerySpec spec) {
    List<Map<String, Object>> raw = recommendationMapper.selectFastList(spec.db);
    boolean hasMore = raw.size() > spec.pageSize;
    if (hasMore) {
      raw = new ArrayList<>(raw.subList(0, spec.pageSize));
    }
    List<Map<String, Object>> items = applyOverrides(raw);
    items.forEach(this::decorateAndScript);
    long ts = System.currentTimeMillis();
    return new ListCacheValue(ts + LIST_FRESH_MILLIS, ts + LIST_STALE_MILLIS, items, hasMore);
  }

  private ListCacheValue getOrRefreshListCache(
      Map<String, ListCacheValue> cache,
      Set<String> refreshingKeys,
      String key,
      Supplier<ListCacheValue> loader) {
    long now = System.currentTimeMillis();
    ListCacheValue cached = cache.get(key);
    if (cached != null && cached.staleAt > now) {
      if (cached.freshUntil <= now && refreshingKeys.add(key)) {
        long epoch = cacheEpoch.get();
        CompletableFuture.runAsync(
            () -> {
              try {
                ListCacheValue fresh = loader.get();
                if (cacheEpoch.get() == epoch) {
                  putListCache(cache, key, fresh, System.currentTimeMillis());
                }
              } finally {
                refreshingKeys.remove(key);
              }
            });
      }
      return cached;
    }
    long epoch = cacheEpoch.get();
    ListCacheValue fresh = loader.get();
    if (cacheEpoch.get() == epoch) {
      putListCache(cache, key, fresh, System.currentTimeMillis());
    }
    return fresh;
  }

  private String buildListCacheKey(Map<String, Object> db) {
    TreeMap<String, String> sorted = new TreeMap<>();
    for (Map.Entry<String, Object> e : db.entrySet()) {
      sorted.put(e.getKey(), String.valueOf(e.getValue()));
    }
    return sorted.toString();
  }

  private void putListCache(
      Map<String, ListCacheValue> cache, String key, ListCacheValue value, long now) {
    if (cache.size() >= MAX_LIST_CACHE_SIZE) {
      cache.entrySet().removeIf(e -> e.getValue().staleAt <= now);
      if (cache.size() >= MAX_LIST_CACHE_SIZE) {
        cache.clear();
      }
    }
    cache.put(key, value);
  }

  private String buildStatsCacheKey(Map<String, Object> db) {
    TreeMap<String, String> sorted = new TreeMap<>();
    for (Map.Entry<String, Object> e : db.entrySet()) {
      if ("offset".equals(e.getKey()) || "limit".equals(e.getKey())) {
        continue;
      }
      sorted.put(e.getKey(), String.valueOf(e.getValue()));
    }
    return sorted.toString();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> deepCopyMap(Map<String, Object> source) {
    if (source == null) return new LinkedHashMap<>();
    try {
      return objectMapper.convertValue(source, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      return new LinkedHashMap<>(source);
    }
  }

  private static class StatsCacheValue {
    final long freshUntil;
    final long staleAt;
    final Map<String, Object> data;

    StatsCacheValue(long freshUntil, long staleAt, Map<String, Object> data) {
      this.freshUntil = freshUntil;
      this.staleAt = staleAt;
      this.data = data;
    }
  }

  private static class ListCacheValue {
    final long freshUntil;
    final long staleAt;
    final List<Map<String, Object>> items;
    final boolean hasMore;

    ListCacheValue(
        long freshUntil, long staleAt, List<Map<String, Object>> items, boolean hasMore) {
      this.freshUntil = freshUntil;
      this.staleAt = staleAt;
      this.items = items;
      this.hasMore = hasMore;
    }
  }

  private static class QuerySpec {
    int page;
    int pageSize;
    String quick;
    String sport;
    String model;
    Map<String, Object> db;
  }
}
