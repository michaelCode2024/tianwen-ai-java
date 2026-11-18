package com.ruoyi.tianwen.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 把 football_prediction 的一行收成前端可展示的三份结果。未解锁时只保留玩法名称。 */
public final class TianwenPredictionView {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final Set<String> SETTLED = Set.of("1", "2", "3", "4", "5");

  private TianwenPredictionView() {}

  public static String platformOf(String model) {
    String value = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    if ("nami".equals(value) || "antscore".equals(value) || "ant_score".equals(value)) {
      return "antscore";
    }
    return "nowscore";
  }

  public static boolean settled(Map<String, Object> row) {
    if (row == null) {
      return false;
    }
    for (String key : List.of("result_status", "euro_status", "ou_status", "asia_status")) {
      if (SETTLED.contains(text(row.get(key)))) {
        return true;
      }
    }
    return false;
  }

  public static Map<String, Object> fromRow(Map<String, Object> row, boolean allowed) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("prediction_id", row.get("prediction_id"));
    view.put("model_version", row.get("model_version"));
    view.put("model_platform", row.get("model_platform"));
    view.put("match_id", text(row.get("match_id")));
    view.put("recommendation_id", row.get("recommendation_id"));
    view.put("league_name", row.get("league_name"));
    view.put("match_time", row.get("match_time"));
    view.put("home_team", row.get("home_team"));
    view.put("guest_team", row.get("away_team"));
    view.put("stage", row.get("snapshot_stage"));
    view.put("locked", !allowed);
    view.put(
        "markets",
        List.of(
            market(
                "欧赔",
                row.get("euro_direction"),
                null,
                row.get("euro_odds"),
                row.get("euro_prob"),
                row.get("euro_kelly"),
                row.get("euro_status"),
                allowed),
            market(
                "大小球",
                row.get("ou_direction"),
                row.get("ou_line"),
                row.get("ou_odds"),
                row.get("ou_prob"),
                row.get("ou_kelly"),
                row.get("ou_status"),
                allowed),
            market(
                "让分",
                row.get("asia_direction"),
                row.get("asia_line"),
                row.get("asia_odds"),
                row.get("asia_prob"),
                row.get("asia_kelly"),
                row.get("asia_status"),
                allowed)));
    if (!allowed) {
      return view;
    }
    view.put("p_home", row.get("p_home"));
    view.put("p_draw", row.get("p_draw"));
    view.put("p_away", row.get("p_away"));
    view.put("score_home", row.get("score_home"));
    view.put("score_away", row.get("score_away"));
    view.put("final_home_score", row.get("final_home_score"));
    view.put("final_away_score", row.get("final_away_score"));
    view.put("analysis", analysis(row.get("analysis_json")));
    return view;
  }

  private static Map<String, Object> market(
      String label,
      Object direction,
      Object handicap,
      Object odds,
      Object prob,
      Object kelly,
      Object status,
      boolean allowed) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("label", label);
    item.put("locked", !allowed);
    if (!allowed) {
      return item;
    }
    item.put("direction", direction);
    item.put("available", direction != null && !text(direction).isBlank());
    item.put("handicap", handicap);
    item.put("odds", odds);
    item.put("prob", prob);
    item.put("kelly", kelly);
    item.put("status", status == null ? 0 : status);
    item.put("statusText", statusText(status));
    return item;
  }

  private static Map<String, Object> analysis(Object raw) {
    Map<String, Object> parsed = parseMap(raw);
    Object steps = parsed.get("steps");
    Map<String, Object> analysis = new LinkedHashMap<>();
    analysis.put("steps", steps instanceof List ? steps : new ArrayList<>());
    return analysis;
  }

  private static Map<String, Object> parseMap(Object raw) {
    if (raw instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach((key, value) -> copy.put(String.valueOf(key), value));
      return copy;
    }
    String text = null;
    if (raw instanceof byte[] bytes) {
      text = new String(bytes, StandardCharsets.UTF_8);
    } else if (raw != null) {
      text = String.valueOf(raw);
    }
    if (text == null || text.isBlank() || "null".equals(text)) {
      return Map.of();
    }
    try {
      return JSON.readValue(text, MAP_TYPE);
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  public static String statusText(Object status) {
    return switch (text(status)) {
      case "1" -> "全赢";
      case "2" -> "全输";
      case "3" -> "走水";
      case "4" -> "半赢";
      case "5" -> "半输";
      default -> "待结算";
    };
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }
}
