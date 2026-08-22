package com.ruoyi.web.controller.tianwen;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.tianwen.service.ITianwenRecommendationService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旧接口兼容层：全部在 Java/MySQL 内部完成，不再代理 Python/外部域名。
 */
@Anonymous
@RestController
@RequestMapping("/api")
public class TianwenProxyController {
    private final ITianwenRecommendationService recommendationService;
    public TianwenProxyController(ITianwenRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @GetMapping("/recommendations")
    public Map<String,Object> recommendations(@RequestParam Map<String,String> query) {
        return recommendationService.legacyRecommendations(query);
    }

    @GetMapping("/summary")
    public Map<String,Object> summary(@RequestParam Map<String,String> query) {
        return recommendationService.summary(query);
    }

    @GetMapping("/review")
    public Map<String,Object> review(@RequestParam Map<String,String> query) {
        return recommendationService.review(query);
    }

    @PostMapping("/settle-overdue")
    public Map<String,Object> settleOverdue() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("code",200);
        out.put("msg","Java H5 服务已取消上游代理；推荐结算仍由现有 worker 写入 football_ai_recommend_record。后续可继续迁移结算 worker。 ");
        return out;
    }
}
