package com.project.inventory.kafka.consumer;

import com.project.inventory.dto.RestockRecommendation;
import com.project.inventory.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class RestockConsumer {

    private static final Logger log = LoggerFactory.getLogger(RestockConsumer.class);

    private final InventoryService inventoryService;

    public RestockConsumer(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.restock-recommendations}",
            groupId = "inventory-restock-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeRestockRecommendation(RestockRecommendation recommendation, Acknowledgment ack) {
        String correlationId = recommendation.getCorrelationId();
        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }

        try {
            log.info("Received restock recommendation for product {} - recommended qty: {}",
                    recommendation.getProductId(), recommendation.getRecommendedQuantity());

            inventoryService.autoRestock(
                    recommendation.getProductId(),
                    recommendation.getWarehouseId(),
                    recommendation.getRecommendedQuantity(),
                    correlationId
            );

            ack.acknowledge();
            log.info("Successfully processed restock for product {}", recommendation.getProductId());
        } catch (Exception e) {
            log.error("Failed to process restock recommendation for product {}: {}",
                    recommendation.getProductId(), e.getMessage(), e);
            // Don't ack — message will be retried
        } finally {
            MDC.remove("correlationId");
        }
    }
}
