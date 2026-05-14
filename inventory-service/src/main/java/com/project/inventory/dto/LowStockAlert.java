package com.project.inventory.dto;

import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LowStockAlert {
    private UUID productId;
    private UUID warehouseId;
    private Integer currentStock;
    private Integer threshold;
    private String correlationId;
    private Instant timestamp;
}
