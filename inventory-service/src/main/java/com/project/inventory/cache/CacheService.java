package com.project.inventory.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.inventory.dto.InventoryResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Counter cacheHits;
    private final Counter cacheMisses;

    @Value("${app.cache.ttl-seconds}")
    private int ttlSeconds;

    @Value("${app.cache.key-prefix}")
    private String keyPrefix;

    public CacheService(RedisTemplate<String, Object> redisTemplate,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheHits = Counter.builder("inventory_cache_hits_total")
                .description("Cache hits").register(meterRegistry);
        this.cacheMisses = Counter.builder("inventory_cache_misses_total")
                .description("Cache misses").register(meterRegistry);
    }

    public Optional<InventoryResponse> get(UUID productId) {
        try {
            String key = keyPrefix + productId;
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                cacheHits.increment();
                InventoryResponse response = objectMapper.convertValue(cached, InventoryResponse.class);
                log.debug("Cache HIT for product {}", productId);
                return Optional.of(response);
            }
            cacheMisses.increment();
            log.debug("Cache MISS for product {}", productId);
            return Optional.empty();
        } catch (Exception e) {
            cacheMisses.increment();
            log.warn("Redis read failed for product {}, falling back to DB: {}", productId, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(UUID productId, InventoryResponse response) {
        try {
            String key = keyPrefix + productId;
            redisTemplate.opsForValue().set(key, response, Duration.ofSeconds(ttlSeconds));
            log.debug("Cached product {}", productId);
        } catch (Exception e) {
            log.warn("Redis write failed for product {}: {}", productId, e.getMessage());
        }
    }

    public void invalidate(UUID productId) {
        try {
            String key = keyPrefix + productId;
            redisTemplate.delete(key);
            // Publish invalidation event for other instances
            redisTemplate.convertAndSend("cache-invalidation", productId.toString());
            log.debug("Invalidated cache for product {}", productId);
        } catch (Exception e) {
            log.warn("Redis invalidation failed for product {}: {}", productId, e.getMessage());
        }
    }

    public void evict(UUID productId) {
        try {
            String key = keyPrefix + productId;
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis evict failed for product {}: {}", productId, e.getMessage());
        }
    }
}
