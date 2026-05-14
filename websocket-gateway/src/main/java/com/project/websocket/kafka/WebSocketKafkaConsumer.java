package com.project.websocket.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class WebSocketKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketKafkaConsumer.class);
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketKafkaConsumer(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @KafkaListener(topics = "inventory-updates", groupId = "ws-inventory-group")
    public void onInventoryUpdate(Map<String, Object> event) {
        try {
            String productId = (String) event.get("productId");
            messagingTemplate.convertAndSend("/topic/inventory/" + productId, event);
            log.debug("Broadcast inventory update for product {}", productId);
        } catch (Exception e) {
            log.error("Failed to broadcast inventory update: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "price-updates", groupId = "ws-price-group")
    public void onPriceUpdate(Map<String, Object> event) {
        try {
            String productId = (String) event.get("productId");
            messagingTemplate.convertAndSend("/topic/pricing/" + productId, event);
            log.debug("Broadcast price update for product {}", productId);
        } catch (Exception e) {
            log.error("Failed to broadcast price update: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "low-stock-alerts", groupId = "ws-alert-group")
    public void onLowStockAlert(Map<String, Object> event) {
        try {
            messagingTemplate.convertAndSend("/topic/alerts", event);
            log.info("Broadcast low-stock alert for product {}", event.get("productId"));
        } catch (Exception e) {
            log.error("Failed to broadcast low-stock alert: {}", e.getMessage());
        }
    }
}
