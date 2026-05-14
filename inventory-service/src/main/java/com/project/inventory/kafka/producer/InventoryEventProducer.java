package com.project.inventory.kafka.producer;

import com.project.inventory.dto.InventoryEvent;
import com.project.inventory.dto.LowStockAlert;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class InventoryEventProducer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Timer kafkaPublishLatency;

    @Value("${app.kafka.topics.inventory-updates}")
    private String inventoryUpdatesTopic;

    @Value("${app.kafka.topics.low-stock-alerts}")
    private String lowStockAlertsTopic;

    @Value("${app.kafka.topics.inventory-updates-dlq}")
    private String dlqTopic;

    public InventoryEventProducer(KafkaTemplate<String, Object> kafkaTemplate, MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaPublishLatency = Timer.builder("kafka_publish_latency_seconds")
                .description("Kafka publish latency")
                .register(meterRegistry);
    }

    @Retry(name = "kafkaPublish", fallbackMethod = "sendToDlq")
    public void publishInventoryUpdate(InventoryEvent event) {
        kafkaPublishLatency.record(() -> {
            String key = event.getProductId().toString();
            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(inventoryUpdatesTopic, key, event);
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish inventory event for product {}: {}",
                            event.getProductId(), ex.getMessage());
                } else {
                    log.info("Published inventory event for product {} to partition {} offset {}",
                            event.getProductId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        });
    }

    @Retry(name = "kafkaPublish")
    public void publishLowStockAlert(LowStockAlert alert) {
        String key = alert.getProductId().toString();
        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(lowStockAlertsTopic, key, alert);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish low-stock alert for product {}: {}",
                        alert.getProductId(), ex.getMessage());
            } else {
                log.info("Published low-stock alert for product {}", alert.getProductId());
            }
        });
    }

    public void sendToDlq(InventoryEvent event, Throwable t) {
        log.error("Sending event to DLQ after retry exhaustion for product {}: {}",
                event.getProductId(), t.getMessage());
        kafkaTemplate.send(dlqTopic, event.getProductId().toString(), event);
    }
}
