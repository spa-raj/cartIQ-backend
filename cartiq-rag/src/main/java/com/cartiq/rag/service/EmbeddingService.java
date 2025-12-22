package com.cartiq.rag.service;

import com.cartiq.rag.config.RagConfig;
import com.google.genai.Client;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import com.google.genai.types.ContentEmbedding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Service for generating text embeddings using Vertex AI's text-embedding-004 model.
 * Supports optional caching via Redis to reduce API calls and costs.
 */
@Slf4j
@Service
public class EmbeddingService {

    private final Client client;
    private final RagConfig ragConfig;
    @Nullable
    private final RedisTemplate<String, Object> redisTemplate;
    private final String embeddingModel;

    private static final String EMBEDDING_CACHE_PREFIX = "embedding:";
    private static final String QUERY_CACHE_PREFIX = "query_embedding:";

    public EmbeddingService(
            RagConfig ragConfig,
            @Autowired(required = false) @Nullable RedisTemplate<String, Object> redisTemplate,
            @Value("${vertex.ai.project-id:}") String projectId,
            @Value("${vertex.ai.location:us-central1}") String location) {
        this.ragConfig = ragConfig;
        this.redisTemplate = redisTemplate;
        this.embeddingModel = ragConfig.getEmbedding().getModel();

        if (redisTemplate == null) {
            log.warn("Redis not available - embedding caching disabled");
        } else {
            log.info("Redis available - embedding caching enabled");
        }

        // Initialize Vertex AI client for embeddings
        Client tempClient = null;
        if (projectId != null && !projectId.isBlank()) {
            try {
                tempClient = Client.builder()
                        .project(projectId)
                        .location(location)
                        .vertexAI(true)
                        .build();
                log.info("Initialized embedding client for Vertex AI: project={}, location={}, model={}",
                        projectId, location, embeddingModel);
            } catch (Exception e) {
                log.error("Failed to initialize embedding client: {}", e.getMessage());
            }
        } else {
            log.warn("Vertex AI not configured for embeddings - projectId is empty");
        }
        this.client = tempClient;
    }

    /**
     * Generate embedding for a single text.
     * Uses cache if available.
     *
     * @param text Text to embed
     * @param cacheKey Optional cache key (use product ID for products, null for queries)
     * @return Embedding vector as List of floats
     */
    public List<Float> embedText(String text, String cacheKey) {
        if (client == null) {
            log.warn("Embedding client not initialized, returning empty embedding");
            return List.of();
        }

        // Check cache first (only if Redis is available)
        if (redisTemplate != null && cacheKey != null) {
            String fullCacheKey = EMBEDDING_CACHE_PREFIX + cacheKey;
            @SuppressWarnings("unchecked")
            List<Float> cached = (List<Float>) redisTemplate.opsForValue().get(fullCacheKey);
            if (cached != null) {
                log.debug("Cache hit for embedding: {}", cacheKey);
                return cached;
            }
        }

        try {
            // Generate embedding using Vertex AI
            EmbedContentResponse response = client.models.embedContent(
                    embeddingModel,
                    text,
                    null // Use default config
            );

            Optional<List<ContentEmbedding>> embeddingsOpt = response.embeddings();
            if (embeddingsOpt.isPresent() && !embeddingsOpt.get().isEmpty()) {
                ContentEmbedding contentEmbedding = embeddingsOpt.get().get(0);
                List<Float> embedding = contentEmbedding.values().orElse(List.of());

                // Cache the result (only if Redis is available)
                if (redisTemplate != null && cacheKey != null && !embedding.isEmpty()) {
                    String fullCacheKey = EMBEDDING_CACHE_PREFIX + cacheKey;
                    Duration ttl = parseDuration(ragConfig.getCache().getProductEmbeddingTtl());
                    redisTemplate.opsForValue().set(fullCacheKey, embedding, ttl);
                    log.debug("Cached embedding for: {}", cacheKey);
                }

                return embedding;
            }

            log.warn("No embedding returned for text: {}", text.substring(0, Math.min(50, text.length())));
            return List.of();

        } catch (Exception e) {
            String message = e.getMessage();
            log.error("Error generating embedding: {}", message);
            // Propagate rate limit errors for upstream retry handling
            if (message != null && message.contains("429")) {
                throw new RateLimitException("Embedding API rate limited: " + message);
            }
            return List.of();
        }
    }

    /**
     * Exception for rate limit errors that should be retried.
     */
    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) {
            super(message);
        }
    }

    /**
     * Generate embedding for a search query.
     * Uses shorter cache TTL for queries.
     *
     * @param query Search query
     * @return Embedding vector
     */
    public List<Float> embedQuery(String query) {
        if (client == null) {
            log.warn("Embedding client not initialized");
            return List.of();
        }

        // Check query cache (only if Redis is available)
        String cacheKey = QUERY_CACHE_PREFIX + query.hashCode();
        if (redisTemplate != null) {
            @SuppressWarnings("unchecked")
            List<Float> cached = (List<Float>) redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.debug("Cache hit for query embedding");
                return cached;
            }
        }

        try {
            EmbedContentResponse response = client.models.embedContent(
                    embeddingModel,
                    query,
                    null
            );

            Optional<List<ContentEmbedding>> embeddingsOpt = response.embeddings();
            if (embeddingsOpt.isPresent() && !embeddingsOpt.get().isEmpty()) {
                ContentEmbedding contentEmbedding = embeddingsOpt.get().get(0);
                List<Float> embedding = contentEmbedding.values().orElse(List.of());

                // Cache with shorter TTL for queries (only if Redis is available)
                if (redisTemplate != null && !embedding.isEmpty()) {
                    Duration ttl = parseDuration(ragConfig.getCache().getQueryEmbeddingTtl());
                    redisTemplate.opsForValue().set(cacheKey, embedding, ttl);
                }

                return embedding;
            }

            return List.of();

        } catch (Exception e) {
            log.error("Error generating query embedding: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Generate embeddings for multiple texts in batch.
     * More efficient than individual calls for bulk operations.
     *
     * @param texts List of texts to embed
     * @return List of embeddings (same order as input)
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        if (client == null || texts.isEmpty()) {
            return texts.stream().map(t -> List.<Float>of()).toList();
        }

        List<List<Float>> results = new ArrayList<>();

        // Process in smaller batches to avoid API limits
        int batchSize = 20; // Vertex AI limit
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            for (String text : batch) {
                results.add(embedText(text, null)); // No caching for batch
            }
        }

        return results;
    }

    /**
     * Build embedding text for a product.
     * Combines name, description, brand, and category for comprehensive embedding.
     * Category is placed at the start with synonyms for better semantic matching.
     *
     * @param name Product name
     * @param description Product description
     * @param brand Product brand
     * @param categoryName Category name
     * @return Combined text for embedding
     */
    public String buildProductEmbeddingText(String name, String description, String brand, String categoryName) {
        StringBuilder sb = new StringBuilder();

        // Start with category and synonyms for stronger semantic matching
        if (categoryName != null && !categoryName.isBlank()) {
            sb.append("Category: ").append(categoryName);
            String synonyms = getCategorySynonyms(categoryName);
            if (!synonyms.isEmpty()) {
                sb.append(" (").append(synonyms).append(")");
            }
            sb.append(". ");
        }

        if (name != null && !name.isBlank()) {
            sb.append(name).append(". ");
        }
        if (brand != null && !brand.isBlank()) {
            sb.append("Brand: ").append(brand).append(". ");
        }
        if (description != null && !description.isBlank()) {
            // Truncate long descriptions to stay within token limits
            String truncated = description.length() > 800
                    ? description.substring(0, 800) + "..."
                    : description;
            sb.append(truncated);
        }

        return sb.toString().trim();
    }

    /**
     * Get synonyms for common category names to improve semantic matching.
     * E.g., "Smartphones" -> "mobile phones, cell phones, handsets"
     */
    private String getCategorySynonyms(String categoryName) {
        if (categoryName == null) return "";

        String lower = categoryName.toLowerCase();

        // Common category synonyms for better search matching
        if (lower.contains("smartphone") || lower.equals("mobiles")) {
            return "mobile phones, cell phones, handsets, cellular phones";
        }
        if (lower.contains("television") || lower.equals("tvs")) {
            return "TVs, TV sets, smart TVs, LED TV, OLED TV";
        }
        if (lower.contains("laptop")) {
            return "notebooks, portable computers, ultrabooks";
        }
        if (lower.contains("headphone") || lower.contains("earphone")) {
            return "earbuds, earphones, headsets, audio accessories";
        }
        if (lower.contains("tablet")) {
            return "iPad, Android tablet, tab, portable tablet";
        }
        if (lower.contains("camera")) {
            return "DSLR, mirrorless, digital camera, photography";
        }
        if (lower.contains("watch") && lower.contains("smart")) {
            return "fitness tracker, wearable, smartwatch";
        }
        if (lower.contains("refrigerator") || lower.contains("fridge")) {
            return "fridge, freezer, refrigerator, cooling appliance";
        }
        if (lower.contains("washing machine")) {
            return "washer, laundry machine, clothes washer";
        }
        if (lower.contains("air conditioner") || lower.equals("acs")) {
            return "AC, cooling system, air cooler, split AC";
        }
        if (lower.contains("kurta") || lower.contains("kurti")) {
            return "Indian wear, ethnic wear, traditional dress";
        }
        if (lower.contains("saree") || lower.contains("sari")) {
            return "traditional wear, Indian dress, ethnic saree";
        }
        if (lower.contains("t-shirt") || lower.contains("tshirt")) {
            return "tee, casual wear, top";
        }
        if (lower.contains("jeans")) {
            return "denim, pants, trousers";
        }
        if (lower.contains("sneaker") || lower.contains("sports shoe")) {
            return "athletic shoes, running shoes, trainers";
        }

        return "";
    }

    /**
     * Check if the embedding service is available.
     */
    public boolean isAvailable() {
        return client != null && ragConfig.isEnabled();
    }

    /**
     * Get embedding dimensions (for index configuration).
     */
    public int getEmbeddingDimensions() {
        return ragConfig.getEmbedding().getDimensions();
    }

    // Parse duration string like "24h" or "1h" to Duration
    private Duration parseDuration(String durationStr) {
        if (durationStr == null || durationStr.isBlank()) {
            return Duration.ofHours(1);
        }

        try {
            if (durationStr.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(durationStr.replace("h", "")));
            } else if (durationStr.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(durationStr.replace("m", "")));
            } else if (durationStr.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(durationStr.replace("d", "")));
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid duration format: {}, using default 1h", durationStr);
        }

        return Duration.ofHours(1);
    }
}
