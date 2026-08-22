package com.ruoyi.tianwen.dto;
import java.util.Map;
public class AdminRecommendUpdateRequest {
    private String recommendationId;
    private Map<String,Object> fields;
    public String getRecommendationId() { return recommendationId; }
    public void setRecommendationId(String recommendationId) { this.recommendationId = recommendationId; }
    public Map<String,Object> getFields() { return fields; }
    public void setFields(Map<String,Object> fields) { this.fields = fields; }
}
