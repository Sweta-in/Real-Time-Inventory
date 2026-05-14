package com.project.inventory.repository;

import com.project.inventory.model.InventoryAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<InventoryAuditLog, UUID> {

    Page<InventoryAuditLog> findByProductIdOrderByTimestampDesc(UUID productId, Pageable pageable);

    Page<InventoryAuditLog> findAllByOrderByTimestampDesc(Pageable pageable);

    boolean existsByCorrelationId(String correlationId);
}
