package com.project.inventory.controller;

import com.project.inventory.dto.*;
import com.project.inventory.service.InventoryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory")
@Tag(name = "Inventory", description = "Inventory management endpoints")
public class InventoryController {

    private final InventoryService inventoryService;
    private final Timer apiTimer;

    public InventoryController(InventoryService inventoryService, MeterRegistry meterRegistry) {
        this.inventoryService = inventoryService;
        this.apiTimer = Timer.builder("api_request_duration_seconds")
                .tag("service", "inventory")
                .description("API request duration")
                .register(meterRegistry);
    }

    @PostMapping("/{productId}/sell")
    @Operation(summary = "Sell product - deduct stock")
    public ResponseEntity<InventoryResponse> sell(
            @PathVariable UUID productId,
            @Valid @RequestBody StockChangeRequest request) {
        return ResponseEntity.ok(apiTimer.record(() -> inventoryService.sell(productId, request)));
    }

    @PostMapping("/{productId}/restock")
    @Operation(summary = "Restock product - add stock")
    public ResponseEntity<InventoryResponse> restock(
            @PathVariable UUID productId,
            @Valid @RequestBody StockChangeRequest request) {
        return ResponseEntity.ok(apiTimer.record(() -> inventoryService.restock(productId, request)));
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Get current stock level (cached)")
    public ResponseEntity<InventoryResponse> getStock(@PathVariable UUID productId) {
        return ResponseEntity.ok(apiTimer.record(() -> inventoryService.getStock(productId)));
    }

    @GetMapping("/low-stock")
    @Operation(summary = "Get items below low-stock threshold")
    public ResponseEntity<List<InventoryResponse>> getLowStock() {
        return ResponseEntity.ok(inventoryService.getLowStockItems());
    }

    @GetMapping("/{productId}/history")
    @Operation(summary = "Get audit trail for product")
    public ResponseEntity<List<AuditLogResponse>> getHistory(@PathVariable UUID productId) {
        return ResponseEntity.ok(inventoryService.getHistory(productId));
    }
}
