package com.ruoyi.tianwen.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class TianwenCheckin {
    private Long id;
    private Long userId;
    private LocalDate checkinDate;
    private Integer streak;
    private Integer rewardGranted;
    private LocalDateTime createdAt;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDate getCheckinDate() { return checkinDate; }
    public void setCheckinDate(LocalDate checkinDate) { this.checkinDate = checkinDate; }
    public Integer getStreak() { return streak; }
    public void setStreak(Integer streak) { this.streak = streak; }
    public Integer getRewardGranted() { return rewardGranted; }
    public void setRewardGranted(Integer rewardGranted) { this.rewardGranted = rewardGranted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
