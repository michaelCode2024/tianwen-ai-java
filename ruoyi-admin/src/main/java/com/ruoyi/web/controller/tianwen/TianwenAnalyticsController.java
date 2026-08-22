package com.ruoyi.web.controller.tianwen;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.tianwen.service.ITianwenAnalyticsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 天问AI公开数据统计接口。
 *
 * 只返回聚合统计，不包含待结算推荐方向，
 * 不受单场解锁影响。
 */
@Anonymous
@RestController
@RequestMapping("/api/h5")
public class TianwenAnalyticsController {

    private final ITianwenAnalyticsService analyticsService;

    public TianwenAnalyticsController(
            ITianwenAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/performance")
    public Map<String,Object> performance(
            @RequestParam Map<String,String> query) {
        return analyticsService.performance(query);
    }

    @GetMapping("/data/overview")
    public Map<String,Object> overview(
            @RequestParam Map<String,String> query) {
        return analyticsService.dataOverview(query);
    }

    @GetMapping("/data/trend")
    public Map<String,Object> trend(
            @RequestParam Map<String,String> query) {
        return analyticsService.dataTrend(query);
    }

    @GetMapping("/data/models")
    public Map<String,Object> models(
            @RequestParam Map<String,String> query) {
        return analyticsService.dataModels(query);
    }
}
