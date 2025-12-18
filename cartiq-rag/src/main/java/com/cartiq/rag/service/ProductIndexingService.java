package com.cartiq.rag.service;

import com.cartiq.product.dto.ProductDTO;
import com.cartiq.product.service.ProductService;
import com.cartiq.rag.config.RagConfig;
import com.google.cloud.aiplatform.v1.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for indexing products into Vertex AI Vector Search.
 * Generates embeddings and upserts datapoints to the vector index.
 */
@Slf4j
@Service
public class ProductIndexingService {

    private final ProductService productService;
    private final EmbeddingService embeddingService;
    private final RagConfig ragConfig;
    private final String projectId;
    private final String location;

    private IndexServiceClient indexServiceClient;
    private final AtomicBoolean indexingInProgress = new AtomicBoolean(false);

    public ProductIndexingService(
            ProductService productService,
            EmbeddingService embeddingService,
            RagConfig ragConfig,
            @Value("${vertex.ai.project-id:}") String projectId,
            @Value("${vertex.ai.location:us-central1}") String location) {
        this.productService = productService;
        this.embeddingService = embeddingService;
        this.ragConfig = ragConfig;
        this.projectId = projectId;
        this.location = location;

        initializeClient();
    }

    private void initializeClient() {
        String apiEndpoint = ragConfig.getVectorSearch().getApiEndpoint();
        if (apiEndpoint == null || apiEndpoint.isBlank()) {
            log.warn("Vector Search API endpoint not configured for indexing");
            return;
        }

        try {
            IndexServiceSettings settings = IndexServiceSettings.newBuilder()
                    .setEndpoint(apiEndpoint)
                    .build();
            this.indexServiceClient = IndexServiceClient.create(settings);
            log.info("Initialized Index Service client for product indexing");
        } catch (IOException e) {
            log.error("Failed to initialize Index Service client: {}", e.getMessage());
        }
    }

    /**
     * Index all products on application startup if configured.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (ragConfig.getIndexing().isOnStartup() && ragConfig.isEnabled()) {
            log.info("Starting product indexing on application startup");
            indexAllProductsAsync();
        }
    }

    /**
     * Index all products asynchronously.
     */
    @Async
    public void indexAllProductsAsync() {
        if (!indexingInProgress.compareAndSet(false, true)) {
            log.warn("Indexing already in progress, skipping");
            return;
        }

        try {
            indexAllProducts();
        } finally {
            indexingInProgress.set(false);
        }
    }

    /**
     * Index all products from the database.
     * Processes in batches to manage memory and API rate limits.
     *
     * @return Number of products indexed
     */
    public int indexAllProducts() {
        if (!isAvailable()) {
            log.warn("Product indexing not available - Index Service client not initialized");
            return 0;
        }

        log.info("Starting full product indexing...");
        long startTime = System.currentTimeMillis();
        AtomicInteger totalIndexed = new AtomicInteger(0);
        AtomicInteger totalFailed = new AtomicInteger(0);

        int batchSize = ragConfig.getIndexing().getBatchSize();
        int page = 0;
        Page<ProductDTO> productPage;

        do {
            productPage = productService.getAllProducts(PageRequest.of(page, batchSize));
            List<ProductDTO> products = productPage.getContent();

            if (!products.isEmpty()) {
                int indexed = indexProductBatch(products);
                totalIndexed.addAndGet(indexed);
                totalFailed.addAndGet(products.size() - indexed);
            }

            page++;
            log.info("Indexed page {}/{}, total indexed: {}",
                    page, productPage.getTotalPages(), totalIndexed.get());

        } while (productPage.hasNext());

        long duration = System.currentTimeMillis() - startTime;
        log.info("Product indexing completed in {}ms. Total: {}, Indexed: {}, Failed: {}",
                duration, productPage.getTotalElements(), totalIndexed.get(), totalFailed.get());

        return totalIndexed.get();
    }

    // Rate limiting with exponential backoff
    // Vertex AI embedding quota is limited - start conservatively at 1 request/second
    private long currentDelayMs = 1000; // Start with 1 second delay
    private static final long MIN_DELAY_MS = 500; // Never go below 500ms (2 req/sec max)
    private static final long MAX_DELAY_MS = 60000; // Max 1 minute

    /**
     * Index a batch of products.
     *
     * @param products List of products to index
     * @return Number of successfully indexed products
     */
    public int indexProductBatch(List<ProductDTO> products) {
        if (!isAvailable() || products.isEmpty()) {
            return 0;
        }

        List<IndexDatapoint> datapoints = new ArrayList<>();

        for (ProductDTO product : products) {
            try {
                IndexDatapoint datapoint = createDatapointWithRetry(product);
                if (datapoint != null) {
                    datapoints.add(datapoint);
                    // Success - gradually reduce delay (but not below minimum)
                    currentDelayMs = Math.max(MIN_DELAY_MS, currentDelayMs / 2);
                }

                // Small delay between requests
                Thread.sleep(currentDelayMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Indexing interrupted");
                break;
            } catch (Exception e) {
                log.warn("Failed to create datapoint for product {}: {}",
                        product.getId(), e.getMessage());
            }
        }

        if (datapoints.isEmpty()) {
            return 0;
        }

        return upsertDatapoints(datapoints);
    }

    /**
     * Create datapoint with retry and exponential backoff on rate limit errors.
     */
    private IndexDatapoint createDatapointWithRetry(ProductDTO product) {
        int maxRetries = 5;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                return createDatapoint(product);
            } catch (EmbeddingService.RateLimitException e) {
                // Rate limited - exponential backoff
                currentDelayMs = Math.min(MAX_DELAY_MS, currentDelayMs * 2);
                log.warn("Rate limited, backing off to {}ms (attempt {}/{})",
                        currentDelayMs, attempt + 1, maxRetries);
                try {
                    Thread.sleep(currentDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } catch (Exception e) {
                // Other error - don't retry
                log.warn("Error creating datapoint for product {}: {}", product.getId(), e.getMessage());
                return null;
            }
        }
        log.warn("Max retries exceeded for product {}", product.getId());
        return null;
    }

    /**
     * Index a single product (for real-time updates).
     *
     * @param product Product to index
     * @return true if successful
     */
    public boolean indexProduct(ProductDTO product) {
        if (!isAvailable()) {
            return false;
        }

        try {
            IndexDatapoint datapoint = createDatapoint(product);
            if (datapoint == null) {
                return false;
            }

            int result = upsertDatapoints(List.of(datapoint));
            return result > 0;

        } catch (Exception e) {
            log.error("Failed to index product {}: {}", product.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Remove a product from the index.
     *
     * @param productId Product ID to remove
     * @return true if successful
     */
    public boolean removeProduct(String productId) {
        if (!isAvailable()) {
            return false;
        }

        String indexName = getIndexResourceName();
        if (indexName == null) {
            return false;
        }

        try {
            RemoveDatapointsRequest request = RemoveDatapointsRequest.newBuilder()
                    .setIndex(indexName)
                    .addDatapointIds(productId)
                    .build();

            indexServiceClient.removeDatapoints(request);
            log.debug("Removed product {} from vector index", productId);
            return true;

        } catch (Exception e) {
            log.error("Failed to remove product {} from index: {}", productId, e.getMessage());
            return false;
        }
    }

    /**
     * Create an IndexDatapoint from a product.
     */
    private IndexDatapoint createDatapoint(ProductDTO product) {
        // Build embedding text
        String embeddingText = embeddingService.buildProductEmbeddingText(
                product.getName(),
                product.getDescription(),
                product.getBrand(),
                product.getCategoryName()
        );

        // Generate embedding
        List<Float> embedding = embeddingService.embedText(embeddingText, product.getId().toString());
        if (embedding.isEmpty()) {
            log.warn("Failed to generate embedding for product: {}", product.getId());
            return null;
        }

        // Build datapoint with metadata for filtering
        IndexDatapoint.Builder builder = IndexDatapoint.newBuilder()
                .setDatapointId(product.getId().toString());

        // Add feature vector
        for (Float value : embedding) {
            builder.addFeatureVector(value);
        }

        // Add categorical restricts for filtering
        if (product.getCategoryId() != null) {
            builder.addRestricts(IndexDatapoint.Restriction.newBuilder()
                    .setNamespace("category_id")
                    .addAllowList(product.getCategoryId().toString())
                    .build());
        }

        if (product.getBrand() != null && !product.getBrand().isBlank()) {
            builder.addRestricts(IndexDatapoint.Restriction.newBuilder()
                    .setNamespace("brand")
                    .addAllowList(product.getBrand().toLowerCase())
                    .build());
        }

        // Add numeric restricts for range filtering
        if (product.getPrice() != null) {
            builder.addNumericRestricts(IndexDatapoint.NumericRestriction.newBuilder()
                    .setNamespace("price")
                    .setValueDouble(product.getPrice().doubleValue())
                    .build());
        }

        if (product.getRating() != null) {
            builder.addNumericRestricts(IndexDatapoint.NumericRestriction.newBuilder()
                    .setNamespace("rating")
                    .setValueDouble(product.getRating().doubleValue())
                    .build());
        }

        return builder.build();
    }

    /**
     * Upsert datapoints to the vector index.
     */
    private int upsertDatapoints(List<IndexDatapoint> datapoints) {
        String indexName = getIndexResourceName();
        if (indexName == null) {
            log.error("Index resource name not configured");
            return 0;
        }

        try {
            UpsertDatapointsRequest request = UpsertDatapointsRequest.newBuilder()
                    .setIndex(indexName)
                    .addAllDatapoints(datapoints)
                    .build();

            indexServiceClient.upsertDatapoints(request);
            log.debug("Upserted {} datapoints to vector index", datapoints.size());
            return datapoints.size();

        } catch (Exception e) {
            log.error("Failed to upsert datapoints: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Get the full resource name for the index.
     * Format: projects/{project}/locations/{location}/indexes/{index_id}
     */
    private String getIndexResourceName() {
        String indexEndpoint = ragConfig.getVectorSearch().getIndexEndpoint();
        if (indexEndpoint == null || indexEndpoint.isBlank()) {
            return null;
        }

        // If it's already a full resource name, extract index ID
        // Index endpoint format: projects/{project}/locations/{location}/indexEndpoints/{endpoint_id}
        // We need the index resource name, which is different

        // For now, construct from config - you may need to add a separate index ID config
        if (projectId != null && !projectId.isBlank()) {
            String deployedIndexId = ragConfig.getVectorSearch().getDeployedIndexId();
            if (deployedIndexId != null) {
                return String.format("projects/%s/locations/%s/indexes/%s",
                        projectId, location, deployedIndexId);
            }
        }

        return null;
    }

    /**
     * Check if indexing service is available.
     */
    public boolean isAvailable() {
        return ragConfig.isEnabled()
                && indexServiceClient != null
                && embeddingService.isAvailable();
    }

    /**
     * Check if indexing is currently in progress.
     */
    public boolean isIndexingInProgress() {
        return indexingInProgress.get();
    }

    /**
     * Close the client when the service is destroyed.
     */
    public void close() {
        if (indexServiceClient != null) {
            indexServiceClient.close();
        }
    }
}
