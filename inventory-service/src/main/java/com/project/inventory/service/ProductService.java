package com.project.inventory.service;

import com.project.inventory.dto.ProductCreateRequest;
import com.project.inventory.dto.ProductResponse;
import com.project.inventory.model.Product;
import com.project.inventory.repository.ProductRepository;
import com.project.inventory.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepo;
    private final SearchService searchService;

    public ProductService(ProductRepository productRepo, SearchService searchService) {
        this.productRepo = productRepo;
        this.searchService = searchService;
    }

    @Transactional
    public ProductResponse createProduct(ProductCreateRequest request) {
        Product product = Product.builder()
                .name(request.getName())
                .category(request.getCategory())
                .description(request.getDescription())
                .basePrice(request.getBasePrice())
                .imageUrl(request.getImageUrl())
                .build();

        product = productRepo.save(product);
        log.info("Created product: {} ({})", product.getName(), product.getId());

        // Generate embedding and index in Elasticsearch
        String textForEmbedding = product.getName() + " " +
                product.getCategory() + " " +
                (product.getDescription() != null ? product.getDescription() : "");
        float[] embedding = searchService.getEmbedding(textForEmbedding);
        searchService.indexProduct(product, embedding);

        return toResponse(product);
    }

    public ProductResponse getProduct(UUID id) {
        Product product = productRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
        return toResponse(product);
    }

    public List<ProductResponse> searchProducts(String query, String category) {
        return searchService.searchByKeyword(query, category);
    }

    public List<ProductResponse> semanticSearch(String query) {
        return searchService.semanticSearch(query);
    }

    private ProductResponse toResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .category(product.getCategory())
                .description(product.getDescription())
                .basePrice(product.getBasePrice())
                .imageUrl(product.getImageUrl())
                .createdAt(product.getCreatedAt())
                .build();
    }
}
