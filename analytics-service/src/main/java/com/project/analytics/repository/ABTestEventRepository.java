package com.project.analytics.repository;

import com.project.analytics.model.ABTestEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface ABTestEventRepository extends JpaRepository<ABTestEvent, UUID> {
    long countByExperimentGroup(String group);

    @Query("SELECT COUNT(e) FROM ABTestEvent e WHERE e.experimentGroup = :group AND e.wasClicked = true")
    long countClicksByGroup(String group);
}
