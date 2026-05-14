package com.project.pricing.repository;

import com.project.pricing.model.PriceAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface PriceAuditLogRepository extends JpaRepository<PriceAuditLog, UUID> {
    Page<PriceAuditLog> findAllByOrderByTimestampDesc(Pageable pageable);
    Page<PriceAuditLog> findByProductIdOrderByTimestampDesc(UUID productId, Pageable pageable);
}
