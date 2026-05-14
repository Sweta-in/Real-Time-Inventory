package com.project.inventory.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ProductSearchRepository extends ElasticsearchRepository<ProductDocument, String> {

    List<ProductDocument> findByNameContaining(String name);

    List<ProductDocument> findByCategory(String category);

    List<ProductDocument> findByNameContainingAndCategory(String name, String category);
}
