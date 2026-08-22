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
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 天问 AI 推荐服务 V3。
 *
 * <p>重要：本实现不再请求 https://ai.lookfunny.xyz，也不依赖 server.py。 推荐列表/详情/后台推荐管理全部直接查询
 * MySQL：football_ai_recommend_record， 赛事即时状态关联 t_leisu_schedule。
 */
@Service
public class TianwenRecommendationServiceImpl implements ITianwenRecommendationService {
  private static final Set<String> HIT = Set.of("1", "3", "4");
  private static final Set<String> LOST = Set.of("2", "5");
  private static final Set<String> FINISHED = Set.of("1", "2", "3", "4", "5");

  // 推荐统计不是实时交易数据，缓存 15 秒即可大幅降低聚合 SQL 压力。
  private static final long STATS_CACHE_MILLIS = 15_000L;
  private final Map<String, StatsCacheValue> statsCache = new ConcurrentHashMap<>();

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

    // 多取 1 条用于判断是否还有下一页，避免 COUNT(*)。
    spec.db.put("limit", spec.pageSize + 1);

    List<Map<String, Object>> raw = recommendationMapper.selectFastList(spec.db);
    boolean hasMore = raw.size() > spec.pageSize;

    if (hasMore) {
      raw = new ArrayList<>(raw.subList(0, spec.pageSize));
    }

    List<Map<String, Object>> items = applyOverrides(raw);
    items.forEach(this::decorateAndScript);

    Set<String> unlocked =
        user == null
            ? Collections.emptySet()
            : new HashSet<>(unlockMapper.selectUnlockedIds(user.getId()));

    List<Map<String, Object>> viewItems =
        items.stream()
            .map(i -> mask(i, user, unlocked.contains(recId(i))))
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
    if (cached != null && cached.expireAt > now) {
      return deepCopyMap(cached.data);
    }

    Map<String, Object> countRow = recommendationMapper.selectFastCounts(spec.db);

    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("all", intValue(countRow, "all_count"));
    counts.put("pending", intValue(countRow, "pending_count"));
    counts.put("hit", intValue(countRow, "hit_count"));
    counts.put("finished", intValue(countRow, "finished_count"));
    counts.put("obsolete", intValue(countRow, "obsolete_count"));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("counts", counts);
    out.put("count", counts.get("all"));

    statsCache.put(cacheKey, new StatsCacheValue(now + STATS_CACHE_MILLIS, deepCopyMap(out)));

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
    if (minRate == null) minRate = properties.getDefaultMinRate();

    int limit = positiveInt(q.get("limit"), 24, 1, 50);

    db.put("minRate", minRate);
    db.put("limit", limit);
    db.put("status", trim(q.get("status")));

    List<Map<String, Object>> raw = recommendationMapper.selectHomeLatest(db);
    List<Map<String, Object>> rows = applyOverrides(raw);
    rows.forEach(this::decorateAndScript);

    Set<String> unlocked =
        user == null
            ? Collections.emptySet()
            : new HashSet<>(unlockMapper.selectUnlockedIds(user.getId()));

    List<Map<String, Object>> items =
        rows.stream()
            .map(i -> mask(i, user, unlocked.contains(recId(i))))
            .collect(Collectors.toList());

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", 200);
    out.put("items", items);
    out.put("count", items.size());
    out.put("auth", auth(user, "all"));
    out.put(
        "source",
        Map.of(
            "mode", "mysql-fast",
            "table", "football_ai_recommend_record",
            "latestOnly", true));
    return out;
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
    db.put("matchId", trim(q.get("match_id")));
    db.put("stage", trim(q.get("stage")));
    db.put("market", trim(q.get("market")));
    db.put("recommendRole", trim(q.get("recommend_role")));
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
      min = properties.getDefaultMinRate();
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

    final Long id;

    try {
      id = Long.valueOf(idText);
    } catch (Exception e) {
      throw new ServiceException("recommendationId格式错误");
    }

    // 1. 当前推荐
    Map<String, Object> row = recommendationMapper.selectById(id);

    if (row == null) {
      throw new ServiceException("推荐不存在");
    }

    row = applyOverride(row);
    decorateAndScript(row);
    decorateResult(row);

    // 2. 当前推荐是否已经单独解锁
    boolean manuallyUnlocked = user != null && unlockMapper.selectOne(user.getId(), idText) != null;

    // 3. 使用V17统一VIP权限
    Map<String, Object> current = mask(row, user, manuallyUnlocked);

    // 4. 同场历史推荐
    String matchId = str(row.get("match_id"));

    List<Map<String, Object>> history = new ArrayList<>();

    if (!matchId.isEmpty()) {

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
                      String historyId = recId(item);
                      boolean unlocked = unlockedSet.contains(historyId);
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

    out.put("code", 200);
    out.put("item", current);
    out.put("history", history);
    out.put("historyCount", history.size());
    out.put("match", match);
    out.put("auth", auth(user, normalizeModel(str(row.get("model")))));

    return out;
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
    if (HIT.contains(s)) {
      label = "命中";
      clazz = "win";
    } else if (LOST.contains(s)) {
      label = "未中";
      clazz = "lost";
    } else if ("3".equals(s)) {
      label = "走水";
      clazz = "push";
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
      throw new ServiceException(
          "推荐权限判断失败，recommendationId=" + recId(m)
              + ", userId=" + (user == null ? "anonymous" : user.getId())
              + ", vipLevel=" + (user == null ? 0 : Objects.requireNonNullElse(user.getVipLevel(), 0))
              + ": " + e.getMessage());
    }

    if (access == null) {
      throw new ServiceException("推荐权限判断结果为空，recommendationId=" + recId(m));
    }

    m.put("access", access.toMap());
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
    statsCache.clear();
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
    final long expireAt;
    final Map<String, Object> data;

    StatsCacheValue(long expireAt, Map<String, Object> data) {
      this.expireAt = expireAt;
      this.data = data;
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
