package com.project.inventory.dto;

import com.project.inventory.model.InventoryAuditLog;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InventoryEvent {
    private UUID productId;
    private UUID warehouseId;
    private Integer oldStock;
    private Integer newStock;
    private InventoryAuditLog.EventType eventType;
    private String correlationId;
    private Instant timestamp;
}
