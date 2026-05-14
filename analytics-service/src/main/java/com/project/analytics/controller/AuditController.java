package com.project.analytics.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final EntityManager entityManager;

    public AuditController(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @GetMapping("/inventory")
    public ResponseEntity<List<?>> getInventoryAudit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Query query = entityManager.createNativeQuery(
                "SELECT * FROM inventory_audit_log ORDER BY timestamp DESC LIMIT :size OFFSET :offset");
        query.setParameter("size", size);
        query.setParameter("offset", page * size);
        return ResponseEntity.ok(query.getResultList());
    }

    @GetMapping("/pricing")
    public ResponseEntity<List<?>> getPricingAudit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Query query = entityManager.createNativeQuery(
                "SELECT * FROM price_audit_log ORDER BY timestamp DESC LIMIT :size OFFSET :offset");
        query.setParameter("size", size);
        query.setParameter("offset", page * size);
        return ResponseEntity.ok(query.getResultList());
    }
}
