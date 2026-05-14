package com.project.pricing.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "price_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PriceRecord {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "current_price", nullable = false, precision = 12, scale = 2) private BigDecimal currentPrice;
    @Column(name = "base_price", nullable = false, precision = 12, scale = 2) private BigDecimal basePrice;
    @Column(precision = 6, scale = 4) private BigDecimal multiplier;
    @Enumerated(EnumType.STRING) private PriceReason reason;
    @Column(name = "calculated_at", nullable = false) private Instant calculatedAt;
    @PrePersist void onCreate() { if (calculatedAt == null) calculatedAt = Instant.now(); }

    public enum PriceReason { SCARCITY_PREMIUM, CLEARANCE_NUDGE, DEMAND_SURGE, SLOW_MOVER, BASE_PRICE, SCHEDULED }
}
