package com.project.analytics.service;

import com.project.analytics.model.ABTestEvent;
import com.project.analytics.repository.ABTestEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private final ABTestEventRepository abTestRepo;

    public AnalyticsService(ABTestEventRepository abTestRepo) {
        this.abTestRepo = abTestRepo;
    }

    public void logExperimentEvent(String userId, String group, String recId, boolean clicked) {
        ABTestEvent event = ABTestEvent.builder()
                .userId(userId).experimentGroup(group)
                .recommendationId(recId).wasClicked(clicked).build();
        abTestRepo.save(event);
        log.info("Logged A/B event: user={}, group={}, clicked={}", userId, group, clicked);
    }

    public Map<String, Object> getExperimentResults() {
        long impressionsA = abTestRepo.countByExperimentGroup("A");
        long clicksA = abTestRepo.countClicksByGroup("A");
        long impressionsB = abTestRepo.countByExperimentGroup("B");
        long clicksB = abTestRepo.countClicksByGroup("B");

        double ctrA = impressionsA > 0 ? (double) clicksA / impressionsA : 0;
        double ctrB = impressionsB > 0 ? (double) clicksB / impressionsB : 0;

        // Chi-squared test for significance
        double chiSquared = 0;
        boolean significant = false;
        String winner = "inconclusive";

        if (impressionsA > 0 && impressionsB > 0) {
            long totalImpressions = impressionsA + impressionsB;
            long totalClicks = clicksA + clicksB;
            double expectedClickRatio = (double) totalClicks / totalImpressions;

            double expectedClicksA = impressionsA * expectedClickRatio;
            double expectedNoClicksA = impressionsA * (1 - expectedClickRatio);
            double expectedClicksB = impressionsB * expectedClickRatio;
            double expectedNoClicksB = impressionsB * (1 - expectedClickRatio);

            if (expectedClicksA > 0 && expectedNoClicksA > 0 && expectedClicksB > 0 && expectedNoClicksB > 0) {
                chiSquared = Math.pow(clicksA - expectedClicksA, 2) / expectedClicksA
                        + Math.pow((impressionsA - clicksA) - expectedNoClicksA, 2) / expectedNoClicksA
                        + Math.pow(clicksB - expectedClicksB, 2) / expectedClicksB
                        + Math.pow((impressionsB - clicksB) - expectedNoClicksB, 2) / expectedNoClicksB;
                significant = chiSquared > 3.841; // p < 0.05 for 1 df
                if (significant) winner = ctrA > ctrB ? "A" : "B";
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("groupA", Map.of("impressions", impressionsA, "clicks", clicksA, "ctr", Math.round(ctrA * 10000.0) / 10000.0));
        result.put("groupB", Map.of("impressions", impressionsB, "clicks", clicksB, "ctr", Math.round(ctrB * 10000.0) / 10000.0));
        result.put("chiSquaredStatistic", Math.round(chiSquared * 100.0) / 100.0);
        result.put("pValue", chiSquared > 3.841 ? "< 0.05" : ">= 0.05");
        result.put("significant", significant);
        result.put("winner", winner);
        return result;
    }
}
