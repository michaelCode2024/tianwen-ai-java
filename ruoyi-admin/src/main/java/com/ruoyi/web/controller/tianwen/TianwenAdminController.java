package com.ruoyi.web.controller.tianwen;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.tianwen.dto.AdminAdjustUserRequest;
import com.ruoyi.tianwen.dto.AdminRecommendUpdateRequest;
import com.ruoyi.tianwen.service.ITianwenRecommendationService;
import com.ruoyi.tianwen.service.ITianwenUserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/tianwen")
public class TianwenAdminController {
    private final ITianwenUserService userService;
    private final ITianwenRecommendationService recommendationService;

    public TianwenAdminController(ITianwenUserService userService, ITianwenRecommendationService recommendationService) {
        this.userService = userService;
        this.recommendationService = recommendationService;
    }

    @PreAuthorize("@ss.hasPermi('tianwen:user:list')")
    @GetMapping("/users")
    public Map<String,Object> users(@RequestParam(required=false) String q,
                                    @RequestParam(defaultValue="1") int page,
                                    @RequestParam(name="page_size", defaultValue="20") int pageSize) {
        return userService.adminUsers(q, page, pageSize);
    }

    @PreAuthorize("@ss.hasPermi('tianwen:user:adjust')")
    @PostMapping("/users/adjust")
    public Map<String,Object> adjust(@RequestBody AdminAdjustUserRequest body) {
        return userService.adminAdjust(body.getUserId(), body.getBalanceDelta(), body.getFreeViewsDelta(), body.getRemark(), SecurityUtils.getUsername());
    }

    @PreAuthorize("@ss.hasPermi('tianwen:recommend:list')")
    @GetMapping("/recommendations")
    public Map<String,Object> recommendations(@RequestParam Map<String,String> query) {
        return recommendationService.adminList(query);
    }

    @PreAuthorize("@ss.hasPermi('tianwen:recommend:edit')")
    @PostMapping("/recommendations/update")
    public Map<String,Object> update(@RequestBody AdminRecommendUpdateRequest body) {
        return recommendationService.updateOverride(body.getRecommendationId(), body.getFields(), SecurityUtils.getUsername());
    }

    @PreAuthorize("@ss.hasPermi('tianwen:recommend:reset')")
    @PostMapping("/recommendations/reset")
    public Map<String,Object> reset(@RequestBody Map<String,Object> body) {
        return recommendationService.resetOverride(String.valueOf(body.getOrDefault("recommendationId", "")));
    }
}
