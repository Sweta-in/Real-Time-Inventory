package com.project.inventory.dto;

import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RestockRecommendation {
    private UUID productId;
    private UUID warehouseId;
    private Integer currentStock;
    private Integer predictedDemand7d;
    private Integer recommendedQuantity;
    private String correlationId;
    private Instant timestamp;
}
