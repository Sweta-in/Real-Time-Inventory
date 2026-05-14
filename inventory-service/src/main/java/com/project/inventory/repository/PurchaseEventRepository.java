package com.project.inventory.repository;

import com.project.inventory.model.PurchaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface PurchaseEventRepository extends JpaRepository<PurchaseEvent, UUID> {
    List<PurchaseEvent> findByUserId(UUID userId);
    List<PurchaseEvent> findByProductId(UUID productId);
    long countByUserId(UUID userId);
}
