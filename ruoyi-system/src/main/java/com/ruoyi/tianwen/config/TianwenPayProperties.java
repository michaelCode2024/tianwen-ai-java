package com.ruoyi.tianwen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tianwen.pay")
public class TianwenPayProperties {
    private String gatewayBaseUrl = "https://pay.spaceiax.com";
    private String merchantKey = "tianwen_ai";
    private String merchantSecret = "";
    private String notifyUrl = "";
    private String returnUrl = "";
    private String defaultCoin = "USDT";
    private String defaultChain = "auto";
    private int expireSeconds = 1800;

    private String unlockUsdAmount = "1.50";
    private String unlockCnyAmount = "9.90";

    private String vipUsdAmount = "13.90";
    private String vipCnyAmount = "98.00";
    private int vipDays = 30;

    private String svipUsdAmount = "41.90";
    private String svipCnyAmount = "298.00";
    private int svipDays = 30;

    private String supremeUsdAmount = "139.00";
    private String supremeCnyAmount = "998.00";
    private int supremeDays = 30;

    public String getGatewayBaseUrl() { return gatewayBaseUrl; }
    public void setGatewayBaseUrl(String gatewayBaseUrl) { this.gatewayBaseUrl = gatewayBaseUrl; }

    public String getMerchantKey() { return merchantKey; }
    public void setMerchantKey(String merchantKey) { this.merchantKey = merchantKey; }

    public String getMerchantSecret() { return merchantSecret; }
    public void setMerchantSecret(String merchantSecret) { this.merchantSecret = merchantSecret; }

    public String getNotifyUrl() { return notifyUrl; }
    public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }

    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }

    public String getDefaultCoin() { return defaultCoin; }
    public void setDefaultCoin(String defaultCoin) { this.defaultCoin = defaultCoin; }

    public String getDefaultChain() { return defaultChain; }
    public void setDefaultChain(String defaultChain) { this.defaultChain = defaultChain; }

    public int getExpireSeconds() { return expireSeconds; }
    public void setExpireSeconds(int expireSeconds) { this.expireSeconds = expireSeconds; }

    public String getUnlockUsdAmount() { return unlockUsdAmount; }
    public void setUnlockUsdAmount(String unlockUsdAmount) { this.unlockUsdAmount = unlockUsdAmount; }

    public String getUnlockCnyAmount() { return unlockCnyAmount; }
    public void setUnlockCnyAmount(String unlockCnyAmount) { this.unlockCnyAmount = unlockCnyAmount; }

    public String getVipUsdAmount() { return vipUsdAmount; }
    public void setVipUsdAmount(String vipUsdAmount) { this.vipUsdAmount = vipUsdAmount; }

    public String getVipCnyAmount() { return vipCnyAmount; }
    public void setVipCnyAmount(String vipCnyAmount) { this.vipCnyAmount = vipCnyAmount; }

    public int getVipDays() { return vipDays; }
    public void setVipDays(int vipDays) { this.vipDays = vipDays; }

    public String getSvipUsdAmount() { return svipUsdAmount; }
    public void setSvipUsdAmount(String svipUsdAmount) { this.svipUsdAmount = svipUsdAmount; }

    public String getSvipCnyAmount() { return svipCnyAmount; }
    public void setSvipCnyAmount(String svipCnyAmount) { this.svipCnyAmount = svipCnyAmount; }

    public int getSvipDays() { return svipDays; }
    public void setSvipDays(int svipDays) { this.svipDays = svipDays; }

    public String getSupremeUsdAmount() { return supremeUsdAmount; }
    public void setSupremeUsdAmount(String supremeUsdAmount) { this.supremeUsdAmount = supremeUsdAmount; }

    public String getSupremeCnyAmount() { return supremeCnyAmount; }
    public void setSupremeCnyAmount(String supremeCnyAmount) { this.supremeCnyAmount = supremeCnyAmount; }

    public int getSupremeDays() { return supremeDays; }
    public void setSupremeDays(int supremeDays) { this.supremeDays = supremeDays; }
}
