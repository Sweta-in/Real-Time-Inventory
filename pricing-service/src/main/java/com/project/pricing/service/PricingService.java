package com.project.pricing.service;

import com.project.pricing.model.PriceAuditLog;
import com.project.pricing.model.PriceRecord;
import com.project.pricing.repository.PriceAuditLogRepository;
import com.project.pricing.repository.PriceRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);
    private final PriceRecordRepository priceRepo;
    private final PriceAuditLogRepository auditRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RestTemplate restTemplate;
    private final Map<String, Counter> reasonCounters = new HashMap<>();

    @Value("${app.inventory-service-url}") private String inventoryServiceUrl;
    @Value("${app.forecasting-service-url}") private String forecastingServiceUrl;

    public PricingService(PriceRecordRepository priceRepo, PriceAuditLogRepository auditRepo,
                          KafkaTemplate<String, Object> kafkaTemplate, MeterRegistry meterRegistry) {
        this.priceRepo = priceRepo;
        this.auditRepo = auditRepo;
        this.kafkaTemplate = kafkaTemplate;
        this.restTemplate = new RestTemplate();
        for (PriceRecord.PriceReason r : PriceRecord.PriceReason.values()) {
            reasonCounters.put(r.name(), Counter.builder("pricing_recalculations_total")
                    .tag("reason", r.name()).register(meterRegistry));
        }
    }

    public Map<String, Object> getCurrentPrice(UUID productId) {
        Optional<PriceRecord> latest = priceRepo.findTopByProductIdOrderByCalculatedAtDesc(productId);
        if (latest.isPresent()) {
            PriceRecord r = latest.get();
            return Map.of("productId", productId, "currentPrice", r.getCurrentPrice(),
                    "basePrice", r.getBasePrice(), "multiplier", r.getMultiplier(),
                    "reason", r.getReason(), "calculatedAt", r.getCalculatedAt());
        }
        // No price record yet — calculate
        return recalculate(productId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> recalculate(UUID productId) {
        try {
            // Fetch inventory data
            Map<String, Object> inventory = restTemplate.getForObject(
                    inventoryServiceUrl + "/api/inventory/" + productId, Map.class);
            int stockLevel = inventory != null ? (int) inventory.getOrDefault("stockLevel", 50) : 50;
            int capacityMax = inventory != null ? (int) inventory.getOrDefault("capacityMax", 100) : 100;

            // Fetch base price from products
            Map<String, Object> product = restTemplate.getForObject(
                    inventoryServiceUrl + "/api/products/" + productId, Map.class);
            BigDecimal basePrice = product != null ?
                    new BigDecimal(product.getOrDefault("basePrice", "10.00").toString()) :
                    BigDecimal.TEN;

            // Apply pricing rules
            BigDecimal multiplier = BigDecimal.ONE;
            PriceRecord.PriceReason reason = PriceRecord.PriceReason.BASE_PRICE;
            double stockRatio = capacityMax > 0 ? (double) stockLevel / capacityMax : 0.5;

            if (stockRatio < 0.10) {
                multiplier = new BigDecimal("1.15");
                reason = PriceRecord.PriceReason.SCARCITY_PREMIUM;
            } else if (stockRatio > 0.80) {
                multiplier = new BigDecimal("0.90");
                reason = PriceRecord.PriceReason.CLEARANCE_NUDGE;
            }

            // Try demand forecast
            try {
                Map<String, Object> forecast = restTemplate.getForObject(
                        forecastingServiceUrl + "/forecast/" + productId + "?days=7", Map.class);
                if (forecast != null && forecast.containsKey("predicted_demand_7d")) {
                    double demand7d = ((Number) forecast.get("predicted_demand_7d")).doubleValue();
                    if (demand7d > stockLevel * 0.8) {
                        multiplier = multiplier.multiply(new BigDecimal("1.10"));
                        reason = PriceRecord.PriceReason.DEMAND_SURGE;
                    }
                }
            } catch (Exception e) {
                log.warn("Forecast unavailable, skipping demand rule: {}", e.getMessage());
            }

            BigDecimal newPrice = basePrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
            reasonCounters.getOrDefault(reason.name(), reasonCounters.get("BASE_PRICE")).increment();

            // Get old price for audit
            BigDecimal oldPrice = priceRepo.findTopByProductIdOrderByCalculatedAtDesc(productId)
                    .map(PriceRecord::getCurrentPrice).orElse(basePrice);

            // Save price record
            PriceRecord record = PriceRecord.builder()
                    .productId(productId).currentPrice(newPrice).basePrice(basePrice)
                    .multiplier(multiplier).reason(reason).build();
            priceRepo.save(record);

            // Write audit log
            auditRepo.save(PriceAuditLog.builder()
                    .productId(productId).oldPrice(oldPrice).newPrice(newPrice)
                    .reason(reason.name()).build());

            // Publish price update event
            kafkaTemplate.send("price-updates", productId.toString(),
                    Map.of("productId", productId, "oldPrice", oldPrice, "newPrice", newPrice,
                            "reason", reason.name(), "timestamp", Instant.now().toString()));

            log.info("Price recalculated for product {}: {} -> {} ({})", productId, oldPrice, newPrice, reason);
            return Map.of("productId", productId, "currentPrice", newPrice, "basePrice", basePrice,
                    "multiplier", multiplier, "reason", reason, "calculatedAt", Instant.now());
        } catch (Exception e) {
            log.error("Price recalculation failed for {}: {}", productId, e.getMessage());
            return Map.of("productId", productId, "error", e.getMessage());
        }
    }

    public List<Map<String, Object>> getSlowMovers() {
        return priceRepo.findByReason(PriceRecord.PriceReason.SLOW_MOVER).stream()
                .map(r -> Map.<String, Object>of("productId", r.getProductId(),
                        "currentPrice", r.getCurrentPrice(), "reason", r.getReason()))
                .toList();
    }

    @Scheduled(fixedRate = 21600000) // Every 6 hours
    public void scheduledRecalculation() {
        log.info("Running scheduled price recalculation...");
        // Recalc for recently updated products would happen here
    }
}
