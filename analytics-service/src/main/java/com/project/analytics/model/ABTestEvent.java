package com.project.analytics.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ab_test_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ABTestEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false) private String userId;
    @Column(name = "experiment_group", nullable = false) private String experimentGroup;
    @Column(name = "recommendation_id") private String recommendationId;
    @Column(name = "was_clicked", nullable = false) private Boolean wasClicked;
    @Column(nullable = false) private Instant timestamp;
    @PrePersist void onCreate() { if (timestamp == null) timestamp = Instant.now(); }
}
