package com.project.analytics.controller;

import com.project.analytics.service.AnalyticsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @PostMapping("/experiment/event")
    public ResponseEntity<Void> logEvent(@RequestBody Map<String, Object> body) {
        analyticsService.logExperimentEvent(
                (String) body.get("userId"),
                (String) body.get("experimentGroup"),
                (String) body.getOrDefault("recommendationId", ""),
                Boolean.TRUE.equals(body.get("wasClicked"))
        );
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/experiment/results")
    public ResponseEntity<Map<String, Object>> getResults() {
        return ResponseEntity.ok(analyticsService.getExperimentResults());
    }
}
