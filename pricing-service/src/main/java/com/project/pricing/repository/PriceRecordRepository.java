package com.project.pricing.repository;

import com.project.pricing.model.PriceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

@Repository
public interface PriceRecordRepository extends JpaRepository<PriceRecord, UUID> {
    Optional<PriceRecord> findTopByProductIdOrderByCalculatedAtDesc(UUID productId);
    List<PriceRecord> findByReason(PriceRecord.PriceReason reason);
}
