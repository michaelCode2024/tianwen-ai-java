package com.ruoyi.tianwen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tianwen.ai")
public class TianwenAiProperties {
    private long sessionSeconds = 30L * 24 * 60 * 60;
    private int freeViewCount = 3;
    private int checkinRewardDays = 7;
    private int checkinRewardFreeViews = 1;
    private double defaultMinRate = 90D;
    private String defaultModel = "jiebao";
    private String paidUnlockAmount = "9.90";
    private boolean allowManualPaidUnlock = false;

    public long getSessionSeconds(){return sessionSeconds;} public void setSessionSeconds(long v){sessionSeconds=v;}
    public int getFreeViewCount(){return freeViewCount;} public void setFreeViewCount(int v){freeViewCount=v;}
    public int getCheckinRewardDays(){return checkinRewardDays;} public void setCheckinRewardDays(int v){checkinRewardDays=v;}
    public int getCheckinRewardFreeViews(){return checkinRewardFreeViews;} public void setCheckinRewardFreeViews(int v){checkinRewardFreeViews=v;}
    public double getDefaultMinRate(){return defaultMinRate;} public void setDefaultMinRate(double v){defaultMinRate=v;}
    public String getDefaultModel(){return defaultModel;} public void setDefaultModel(String v){defaultModel=v;}
    public String getPaidUnlockAmount(){return paidUnlockAmount;} public void setPaidUnlockAmount(String v){paidUnlockAmount=v;}
    public boolean isAllowManualPaidUnlock(){return allowManualPaidUnlock;} public void setAllowManualPaidUnlock(boolean v){allowManualPaidUnlock=v;}
}
