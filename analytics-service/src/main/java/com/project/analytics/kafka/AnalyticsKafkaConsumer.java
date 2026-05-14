package com.project.analytics.kafka;

import com.project.analytics.service.AnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class AnalyticsKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsKafkaConsumer.class);
    private final AnalyticsService analyticsService;

    public AnalyticsKafkaConsumer(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @KafkaListener(topics = "ab-test-events", groupId = "analytics-ab-group")
    public void onABTestEvent(Map<String, Object> event) {
        try {
            analyticsService.logExperimentEvent(
                    (String) event.get("userId"),
                    (String) event.get("experimentGroup"),
                    (String) event.getOrDefault("recommendationId", ""),
                    Boolean.TRUE.equals(event.get("wasClicked"))
            );
        } catch (Exception e) {
            log.error("Failed to process A/B test event: {}", e.getMessage());
        }
    }
}
