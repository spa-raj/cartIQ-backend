package com.cartiq.rag.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Debug controller for inspecting Redis cached query embeddings.
 * Protected by internal API key.
 *
 * Note: Only QUERY embeddings are cached. Product embeddings are pre-computed
 * during batch indexing and stored directly in Vertex AI Vector Search index.
 *
 * Endpoints:
 * - GET /api/internal/debug/embeddings/stats - Get cache statistics
 * - GET /api/internal/debug/embeddings/queries - List all cached query embeddings
 * - GET /api/internal/debug/embeddings/query/{hashCode} - Get specific query embedding
 */
@RestController
@RequestMapping("/api/internal/debug/embeddings")
@Slf4j
public class DebugEmbeddingCacheController {

    @Nullable
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${cartiq.internal.api-key:}")
    private String internalApiKey;

    private static final String QUERY_CACHE_PREFIX = "query_embedding:";

    public DebugEmbeddingCacheController(
            @Autowired(required = false) @Nullable RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Get cache statistics - count and sample keys.
     *
     * Usage:
     * curl -H "X-Internal-Api-Key: your-key" \
     *      https://your-app/api/internal/debug/embeddings/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getCacheStats(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {

        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        if (redisTemplate == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Redis not available"));
        }

        Set<String> queryKeys = redisTemplate.keys(QUERY_CACHE_PREFIX + "*");
        int queryCount = queryKeys != null ? queryKeys.size() : 0;

        Map<String, Object> stats = new HashMap<>();
        stats.put("queryEmbeddings", Map.of(
                "prefix", QUERY_CACHE_PREFIX,
                "count", queryCount,
                "sampleKeys", queryKeys != null ? queryKeys.stream().limit(5).toList() : List.of()
        ));
        stats.put("totalCachedEmbeddings", queryCount);
        stats.put("note", "Only query embeddings are cached. Product embeddings are stored in Vertex AI Vector Search index.");

        return ResponseEntity.ok(stats);
    }

    /**
     * List all cached query embeddings.
     */
    @GetMapping("/queries")
    public ResponseEntity<?> listQueryEmbeddings(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestParam(defaultValue = "20") int limit) {

        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        if (redisTemplate == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Redis not available"));
        }

        Set<String> keys = redisTemplate.keys(QUERY_CACHE_PREFIX + "*");

        List<Map<String, Object>> embeddings = keys != null ? keys.stream()
                .limit(limit)
                .map(key -> {
                    Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                    return Map.<String, Object>of(
                            "key", key,
                            "queryHash", key.replace(QUERY_CACHE_PREFIX, ""),
                            "ttlSeconds", ttl != null ? ttl : -1,
                            "ttlFormatted", formatTtl(ttl)
                    );
                })
                .toList() : List.of();

        return ResponseEntity.ok(Map.of(
                "count", keys != null ? keys.size() : 0,
                "showing", embeddings.size(),
                "embeddings", embeddings
        ));
    }

    /**
     * Get a specific query embedding by hash code.
     */
    @GetMapping("/query/{hashCode}")
    public ResponseEntity<?> getQueryEmbedding(
            @PathVariable String hashCode,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestParam(defaultValue = "false") boolean includeVector) {

        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        if (redisTemplate == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Redis not available"));
        }

        String cacheKey = QUERY_CACHE_PREFIX + hashCode;
        Object cached = redisTemplate.opsForValue().get(cacheKey);

        if (cached == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "message", "Query embedding not found in cache",
                            "hashCode", hashCode,
                            "cacheKey", cacheKey
                    ));
        }

        Long ttl = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);

        @SuppressWarnings("unchecked")
        List<? extends Number> vector = (List<? extends Number>) cached;

        Map<String, Object> response = new HashMap<>();
        response.put("hashCode", hashCode);
        response.put("cacheKey", cacheKey);
        response.put("dimensions", vector.size());
        response.put("ttlSeconds", ttl);
        response.put("ttlFormatted", formatTtl(ttl));

        if (includeVector) {
            response.put("vector", vector);
        } else {
            response.put("vectorPreview", Map.of(
                    "first5", vector.stream().limit(5).toList(),
                    "last5", vector.stream().skip(Math.max(0, vector.size() - 5)).toList()
            ));
        }

        return ResponseEntity.ok(response);
    }

    private boolean isValidApiKey(String apiKey) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            log.warn("Internal API key not configured - rejecting request");
            return false;
        }
        return internalApiKey.equals(apiKey);
    }

    private ResponseEntity<?> unauthorizedResponse() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Invalid or missing API key"));
    }

    private String formatTtl(Long ttlSeconds) {
        if (ttlSeconds == null || ttlSeconds < 0) {
            return "no expiry";
        }
        long hours = ttlSeconds / 3600;
        long minutes = (ttlSeconds % 3600) / 60;
        long seconds = ttlSeconds % 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
