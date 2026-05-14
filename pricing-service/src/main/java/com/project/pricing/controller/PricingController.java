package com.project.pricing.controller;

import com.project.pricing.service.PricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/pricing")
@Tag(name = "Pricing", description = "Dynamic pricing endpoints")
public class PricingController {

    private final PricingService pricingService;

    public PricingController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Get current dynamic price")
    public ResponseEntity<Map<String, Object>> getCurrentPrice(@PathVariable UUID productId) {
        return ResponseEntity.ok(pricingService.getCurrentPrice(productId));
    }

    @PostMapping("/{productId}/recalc")
    @Operation(summary = "Trigger price recalculation")
    public ResponseEntity<Map<String, Object>> recalculate(@PathVariable UUID productId) {
        return ResponseEntity.ok(pricingService.recalculate(productId));
    }

    @GetMapping("/slow-movers")
    @Operation(summary = "Get items with low velocity")
    public ResponseEntity<List<Map<String, Object>>> getSlowMovers() {
        return ResponseEntity.ok(pricingService.getSlowMovers());
    }
}
