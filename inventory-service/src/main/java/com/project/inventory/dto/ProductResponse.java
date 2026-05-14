package com.project.inventory.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProductResponse {
    private UUID id;
    private String name;
    private String category;
    private String description;
    private BigDecimal basePrice;
    private String imageUrl;
    private Instant createdAt;
}
