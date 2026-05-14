package com.project.inventory.dto;

import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InventoryResponse {
    private UUID productId;
    private UUID warehouseId;
    private Integer stockLevel;
    private Integer capacityMax;
    private Instant lastUpdated;
    private String productName;
}
