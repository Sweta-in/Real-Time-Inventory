package com.project.inventory.service;

import com.project.inventory.cache.CacheService;
import com.project.inventory.dto.*;
import com.project.inventory.kafka.producer.InventoryEventProducer;
import com.project.inventory.model.InventoryAuditLog;
import com.project.inventory.model.InventoryItem;
import com.project.inventory.model.Product;
import com.project.inventory.repository.AuditLogRepository;
import com.project.inventory.repository.InventoryItemRepository;
import com.project.inventory.repository.ProductRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryItemRepository inventoryRepo;
    private final ProductRepository productRepo;
    private final AuditLogRepository auditLogRepo;
    private final InventoryEventProducer eventProducer;
    private final CacheService cacheService;
    private final Counter lowStockAlertsCounter;

    @Value("${app.low-stock-threshold}")
    private int lowStockThreshold;

    public InventoryService(InventoryItemRepository inventoryRepo,
                            ProductRepository productRepo,
                            AuditLogRepository auditLogRepo,
                            InventoryEventProducer eventProducer,
                            CacheService cacheService,
                            MeterRegistry meterRegistry) {
        this.inventoryRepo = inventoryRepo;
        this.productRepo = productRepo;
        this.auditLogRepo = auditLogRepo;
        this.eventProducer = eventProducer;
        this.cacheService = cacheService;
        this.lowStockAlertsCounter = Counter.builder("low_stock_alerts_total")
                .description("Low stock alerts fired").register(meterRegistry);
    }

    public InventoryResponse getStock(UUID productId) {
        // Cache-aside: try cache first
        Optional<InventoryResponse> cached = cacheService.get(productId);
        if (cached.isPresent()) {
            return cached.get();
        }

        // Cache miss: load from DB
        List<InventoryItem> items = inventoryRepo.findByProductId(productId);
        if (items.isEmpty()) {
            throw new RuntimeException("No inventory found for product " + productId);
        }

        InventoryItem item = items.get(0);
        String productName = productRepo.findById(productId)
                .map(Product::getName).orElse("Unknown");

        InventoryResponse response = InventoryResponse.builder()
                .productId(productId)
                .warehouseId(item.getWarehouseId())
                .stockLevel(item.getStockLevel())
                .capacityMax(item.getCapacityMax())
                .lastUpdated(item.getLastUpdated())
                .productName(productName)
                .build();

        cacheService.put(productId, response);
        return response;
    }

    @Transactional
    public InventoryResponse sell(UUID productId, StockChangeRequest request) {
        String correlationId = MDC.get("correlationId");

        InventoryItem item = inventoryRepo
                .findByProductIdAndWarehouseId(productId, request.getWarehouseId())
                .orElse(null);

        // Multi-warehouse routing: if primary warehouse has no stock, find another
        if (item == null || item.getStockLevel() < request.getQuantity()) {
            List<InventoryItem> alternatives = inventoryRepo.findAvailableWarehouses(productId);
            item = alternatives.stream()
                    .filter(i -> i.getStockLevel() >= request.getQuantity())
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(
                            "Insufficient stock across all warehouses for product " + productId));
            log.info("Auto-routed sale from warehouse {} to {} for product {}",
                    request.getWarehouseId(), item.getWarehouseId(), productId);
        }

        int oldStock = item.getStockLevel();
        int newStock = oldStock - request.getQuantity();
        item.setStockLevel(newStock);
        inventoryRepo.save(item);

        // Write audit log
        writeAuditLog(productId, item.getWarehouseId(),
                InventoryAuditLog.EventType.SALE,
                -request.getQuantity(), oldStock, newStock,
                "API_SELL", correlationId);

        // Invalidate cache
        cacheService.invalidate(productId);

        // Publish Kafka event
        InventoryEvent event = InventoryEvent.builder()
                .productId(productId)
                .warehouseId(item.getWarehouseId())
                .oldStock(oldStock)
                .newStock(newStock)
                .eventType(InventoryAuditLog.EventType.SALE)
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .build();
        eventProducer.publishInventoryUpdate(event);

        // Check low stock
        if (newStock < lowStockThreshold) {
            lowStockAlertsCounter.increment();
            LowStockAlert alert = LowStockAlert.builder()
                    .productId(productId)
                    .warehouseId(item.getWarehouseId())
                    .currentStock(newStock)
                    .threshold(lowStockThreshold)
                    .correlationId(correlationId)
                    .timestamp(Instant.now())
                    .build();
            eventProducer.publishLowStockAlert(alert);
        }

        return buildResponse(productId, item);
    }

    @Transactional
    public InventoryResponse restock(UUID productId, StockChangeRequest request) {
        String correlationId = MDC.get("correlationId");

        InventoryItem item = inventoryRepo
                .findByProductIdAndWarehouseId(productId, request.getWarehouseId())
                .orElseThrow(() -> new RuntimeException(
                        "No inventory record for product " + productId + " in warehouse " + request.getWarehouseId()));

        int oldStock = item.getStockLevel();
        int newStock = oldStock + request.getQuantity();
        item.setStockLevel(newStock);
        inventoryRepo.save(item);

        writeAuditLog(productId, item.getWarehouseId(),
                InventoryAuditLog.EventType.RESTOCK,
                request.getQuantity(), oldStock, newStock,
                "API_RESTOCK", correlationId);

        cacheService.invalidate(productId);

        InventoryEvent event = InventoryEvent.builder()
                .productId(productId)
                .warehouseId(item.getWarehouseId())
                .oldStock(oldStock)
                .newStock(newStock)
                .eventType(InventoryAuditLog.EventType.RESTOCK)
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .build();
        eventProducer.publishInventoryUpdate(event);

        return buildResponse(productId, item);
    }

    @Transactional
    public void autoRestock(UUID productId, UUID warehouseId, int quantity, String correlationId) {
        InventoryItem item = inventoryRepo
                .findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseThrow(() -> new RuntimeException(
                        "No inventory for product " + productId + " in warehouse " + warehouseId));

        int oldStock = item.getStockLevel();
        int newStock = oldStock + quantity;
        item.setStockLevel(newStock);
        inventoryRepo.save(item);

        writeAuditLog(productId, warehouseId,
                InventoryAuditLog.EventType.AUTO_RESTOCK,
                quantity, oldStock, newStock,
                "FORECAST_AUTO_RESTOCK", correlationId);

        cacheService.invalidate(productId);

        InventoryEvent event = InventoryEvent.builder()
                .productId(productId)
                .warehouseId(warehouseId)
                .oldStock(oldStock)
                .newStock(newStock)
                .eventType(InventoryAuditLog.EventType.AUTO_RESTOCK)
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .build();
        eventProducer.publishInventoryUpdate(event);

        log.info("Auto-restocked product {} in warehouse {} with {} units", productId, warehouseId, quantity);
    }

    public List<InventoryResponse> getLowStockItems() {
        return inventoryRepo.findLowStockItems(lowStockThreshold).stream()
                .map(item -> buildResponse(item.getProductId(), item))
                .collect(Collectors.toList());
    }

    public List<AuditLogResponse> getHistory(UUID productId) {
        Page<InventoryAuditLog> logs = auditLogRepo
                .findByProductIdOrderByTimestampDesc(productId, PageRequest.of(0, 100));
        return logs.getContent().stream()
                .map(this::toAuditResponse)
                .collect(Collectors.toList());
    }

    private void writeAuditLog(UUID productId, UUID warehouseId,
                                InventoryAuditLog.EventType eventType,
                                int quantityDelta, int stockBefore, int stockAfter,
                                String triggeredBy, String correlationId) {
        InventoryAuditLog auditLog = InventoryAuditLog.builder()
                .productId(productId)
                .warehouseId(warehouseId)
                .eventType(eventType)
                .quantityDelta(quantityDelta)
                .stockBefore(stockBefore)
                .stockAfter(stockAfter)
                .triggeredBy(triggeredBy)
                .correlationId(correlationId)
                .build();
        auditLogRepo.save(auditLog);
    }

    private InventoryResponse buildResponse(UUID productId, InventoryItem item) {
        String productName = productRepo.findById(productId)
                .map(Product::getName).orElse("Unknown");
        return InventoryResponse.builder()
                .productId(productId)
                .warehouseId(item.getWarehouseId())
                .stockLevel(item.getStockLevel())
                .capacityMax(item.getCapacityMax())
                .lastUpdated(item.getLastUpdated())
                .productName(productName)
                .build();
    }

    private AuditLogResponse toAuditResponse(InventoryAuditLog log) {
        return AuditLogResponse.builder()
                .id(log.getId())
                .productId(log.getProductId())
                .warehouseId(log.getWarehouseId())
                .eventType(log.getEventType())
                .quantityDelta(log.getQuantityDelta())
                .stockBefore(log.getStockBefore())
                .stockAfter(log.getStockAfter())
                .triggeredBy(log.getTriggeredBy())
                .correlationId(log.getCorrelationId())
                .timestamp(log.getTimestamp())
                .build();
    }
}
