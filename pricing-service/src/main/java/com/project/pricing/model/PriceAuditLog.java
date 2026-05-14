package com.project.pricing.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "price_audit_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PriceAuditLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "old_price", precision = 12, scale = 2) private BigDecimal oldPrice;
    @Column(name = "new_price", nullable = false, precision = 12, scale = 2) private BigDecimal newPrice;
    private String reason;
    @Column(name = "correlation_id") private String correlationId;
    @Column(nullable = false, updatable = false) private Instant timestamp;
    @PrePersist void onCreate() { if (timestamp == null) timestamp = Instant.now(); }
}
