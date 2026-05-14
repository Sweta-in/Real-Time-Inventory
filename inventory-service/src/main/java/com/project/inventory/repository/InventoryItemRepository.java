package com.project.inventory.repository;

import com.project.inventory.model.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    List<InventoryItem> findByProductId(UUID productId);

    Optional<InventoryItem> findByProductIdAndWarehouseId(UUID productId, UUID warehouseId);

    @Query("SELECT i FROM InventoryItem i WHERE i.stockLevel < :threshold")
    List<InventoryItem> findLowStockItems(@Param("threshold") int threshold);

    @Query("SELECT i FROM InventoryItem i WHERE i.productId = :productId AND i.stockLevel > 0 ORDER BY i.stockLevel DESC")
    List<InventoryItem> findAvailableWarehouses(@Param("productId") UUID productId);
}
