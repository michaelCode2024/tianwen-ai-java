package com.ruoyi.web.controller.tianwen;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.tianwen.dto.CreatePayOrderRequest;
import com.ruoyi.tianwen.service.ITianwenPayService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Anonymous
@RestController
@RequestMapping("/api/h5/pay")
public class TianwenPayController {

    private final ITianwenPayService payService;

    public TianwenPayController(ITianwenPayService payService) {
        this.payService = payService;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return payService.getPayConfig();
    }

    @PostMapping("/orders")
    public Map<String, Object> createOrder(
            @RequestBody CreatePayOrderRequest body,
            HttpServletRequest request) {
        return payService.createOrder(readToken(request), body, extractPublicOrigin(request));
    }

    @GetMapping("/orders")
    public Map<String, Object> listOrders(
            @RequestParam(defaultValue = "all") String tab,
            HttpServletRequest request) {
        return payService.listOrders(readToken(request), tab);
    }

    @GetMapping("/orders/{mchOrderNo}")
    public Map<String, Object> getOrder(
            @PathVariable String mchOrderNo,
            @RequestParam(defaultValue = "1") String sync,
            @RequestParam(defaultValue = "zh") String lang,
            HttpServletRequest request) {
        boolean doSync = !"0".equals(sync) && !"false".equalsIgnoreCase(sync);
        return payService.getOrder(readToken(request), mchOrderNo, doSync, lang);
    }

    @PostMapping(value = "/webhook/nexavault", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> webhook(@RequestBody Map<String, Object> payload) {
        try {
            String result = payService.handleNexaVaultWebhook(payload);
            return ResponseEntity.ok(result);
        } catch (ServiceException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    private String readToken(HttpServletRequest request) {
        String token = request.getHeader("X-Tianwen-Token");
        if (token != null && !token.trim().isEmpty()) {
            return token.trim();
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.trim().isEmpty()) {
            return "";
        }
        authorization = authorization.trim();
        if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return authorization;
    }

    private String extractPublicOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.trim().isEmpty() && !"null".equalsIgnoreCase(origin.trim())) {
            return origin.trim();
        }
        String proto = firstHeaderToken(request.getHeader("X-Forwarded-Proto"));
        if (proto.isEmpty()) {
            proto = request.getScheme();
        }
        String host = firstHeaderToken(request.getHeader("X-Forwarded-Host"));
        if (host.isEmpty()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.trim().isEmpty()) {
            return "";
        }
        return proto + "://" + host.trim();
    }

    private String firstHeaderToken(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        int idx = raw.indexOf(',');
        return (idx >= 0 ? raw.substring(0, idx) : raw).trim();
    }
}
