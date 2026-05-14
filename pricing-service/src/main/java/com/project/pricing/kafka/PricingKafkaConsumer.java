package com.project.pricing.kafka;

import com.project.pricing.service.PricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

@Component
public class PricingKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(PricingKafkaConsumer.class);
    private final PricingService pricingService;

    public PricingKafkaConsumer(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @KafkaListener(topics = "inventory-updates", groupId = "pricing-inventory-group")
    public void onInventoryUpdate(Map<String, Object> event) {
        try {
            String productId = (String) event.get("productId");
            if (productId != null) {
                log.info("Received inventory update for product {}, recalculating price", productId);
                pricingService.recalculate(UUID.fromString(productId));
            }
        } catch (Exception e) {
            log.error("Failed to process inventory update for pricing: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "restock-recommendations", groupId = "pricing-restock-group")
    public void onRestockRecommendation(Map<String, Object> event) {
        try {
            String productId = (String) event.get("productId");
            if (productId != null) {
                log.info("Received restock recommendation for product {}, recalculating price", productId);
                pricingService.recalculate(UUID.fromString(productId));
            }
        } catch (Exception e) {
            log.error("Failed to process restock recommendation for pricing: {}", e.getMessage());
        }
    }
}
