package com.ruoyi.tianwen.domain;

import java.time.LocalDateTime;

public class TianwenRecommendOverride {
    private String recommendationId;
    private String overrideJson;
    private String adminUsername;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public String getRecommendationId() { return recommendationId; }
    public void setRecommendationId(String recommendationId) { this.recommendationId = recommendationId; }
    public String getOverrideJson() { return overrideJson; }
    public void setOverrideJson(String overrideJson) { this.overrideJson = overrideJson; }
    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
