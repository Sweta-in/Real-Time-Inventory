package com.project.inventory.controller;

import com.project.inventory.dto.ProductCreateRequest;
import com.project.inventory.dto.ProductResponse;
import com.project.inventory.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Products", description = "Product management and search endpoints")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @Operation(summary = "Create a new product")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product by ID")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable UUID id) {
        return ResponseEntity.ok(productService.getProduct(id));
    }

    @GetMapping("/search")
    @Operation(summary = "Full-text search products")
    public ResponseEntity<List<ProductResponse>> searchProducts(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(productService.searchProducts(q, category));
    }

    @GetMapping("/search/semantic")
    @Operation(summary = "Semantic vector search")
    public ResponseEntity<List<ProductResponse>> semanticSearch(
            @RequestParam String q) {
        return ResponseEntity.ok(productService.semanticSearch(q));
    }
}
