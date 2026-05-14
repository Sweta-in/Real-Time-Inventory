package com.project.inventory.dto;

import com.project.inventory.model.InventoryAuditLog;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLogResponse {
    private UUID id;
    private UUID productId;
    private UUID warehouseId;
    private InventoryAuditLog.EventType eventType;
    private Integer quantityDelta;
    private Integer stockBefore;
    private Integer stockAfter;
    private String triggeredBy;
    private String correlationId;
    private Instant timestamp;
}
