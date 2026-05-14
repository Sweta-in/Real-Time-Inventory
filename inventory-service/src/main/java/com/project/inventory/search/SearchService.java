package com.project.inventory.search;

import com.project.inventory.dto.EmbeddingRequest;
import com.project.inventory.dto.EmbeddingResponse;
import com.project.inventory.dto.ProductResponse;
import com.project.inventory.model.Product;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final ProductSearchRepository searchRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final RestTemplate restTemplate;

    @Value("${app.recommendation-service.url}")
    private String recommendationServiceUrl;

    public SearchService(ProductSearchRepository searchRepository,
                         ElasticsearchOperations elasticsearchOperations,
                         RestTemplate restTemplate) {
        this.searchRepository = searchRepository;
        this.elasticsearchOperations = elasticsearchOperations;
        this.restTemplate = restTemplate;
    }

    public void indexProduct(Product product, float[] embedding) {
        try {
            ProductDocument doc = ProductDocument.builder()
                    .id(product.getId().toString())
                    .name(product.getName())
                    .category(product.getCategory())
                    .description(product.getDescription())
                    .basePrice(product.getBasePrice())
                    .imageUrl(product.getImageUrl())
                    .embeddingVector(embedding)
                    .build();
            searchRepository.save(doc);
            log.info("Indexed product {} in Elasticsearch", product.getId());
        } catch (Exception e) {
            log.error("Failed to index product {} in Elasticsearch: {}", product.getId(), e.getMessage());
        }
    }

    public List<ProductResponse> searchByKeyword(String query, String category) {
        try {
            List<ProductDocument> results;
            if (category != null && !category.isBlank()) {
                results = searchRepository.findByNameContainingAndCategory(query, category);
            } else {
                results = searchRepository.findByNameContaining(query);
            }
            return results.stream().map(this::toProductResponse).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Elasticsearch keyword search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @CircuitBreaker(name = "recommendationService", fallbackMethod = "semanticSearchFallback")
    public List<ProductResponse> semanticSearch(String query) {
        try {
            // Get embedding from recommendation service
            EmbeddingRequest request = new EmbeddingRequest(query);
            EmbeddingResponse embeddingResponse = restTemplate.postForObject(
                    recommendationServiceUrl + "/embed",
                    request,
                    EmbeddingResponse.class
            );

            if (embeddingResponse == null || embeddingResponse.getEmbedding() == null) {
                log.warn("Empty embedding response for query: {}", query);
                return Collections.emptyList();
            }

            float[] queryVector = new float[embeddingResponse.getEmbedding().size()];
            for (int i = 0; i < embeddingResponse.getEmbedding().size(); i++) {
                queryVector[i] = embeddingResponse.getEmbedding().get(i);
            }

            // Use Elasticsearch kNN search
            NativeQuery nativeQuery = NativeQuery.builder()
                    .withKnnSearch(knn -> knn
                            .field("embeddingVector")
                            .queryVector(queryVector)
                            .numCandidates(50)
                            .k(10))
                    .build();

            SearchHits<ProductDocument> searchHits =
                    elasticsearchOperations.search(nativeQuery, ProductDocument.class);

            return searchHits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .map(this::toProductResponse)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Semantic search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<ProductResponse> semanticSearchFallback(String query, Throwable t) {
        log.warn("Semantic search circuit breaker open, falling back to keyword search: {}", t.getMessage());
        return searchByKeyword(query, null);
    }

    @CircuitBreaker(name = "recommendationService", fallbackMethod = "getEmbeddingFallback")
    public float[] getEmbedding(String text) {
        EmbeddingRequest request = new EmbeddingRequest(text);
        EmbeddingResponse response = restTemplate.postForObject(
                recommendationServiceUrl + "/embed",
                request,
                EmbeddingResponse.class
        );
        if (response != null && response.getEmbedding() != null) {
            float[] result = new float[response.getEmbedding().size()];
            for (int i = 0; i < response.getEmbedding().size(); i++) {
                result[i] = response.getEmbedding().get(i);
            }
            return result;
        }
        return new float[384];
    }

    public float[] getEmbeddingFallback(String text, Throwable t) {
        log.warn("Embedding service unavailable, using zero vector: {}", t.getMessage());
        return new float[384];
    }

    private ProductResponse toProductResponse(ProductDocument doc) {
        return ProductResponse.builder()
                .id(java.util.UUID.fromString(doc.getId()))
                .name(doc.getName())
                .category(doc.getCategory())
                .description(doc.getDescription())
                .basePrice(doc.getBasePrice())
                .imageUrl(doc.getImageUrl())
                .build();
    }
}
