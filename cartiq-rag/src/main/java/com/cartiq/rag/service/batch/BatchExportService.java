package com.cartiq.rag.service.batch;

import com.cartiq.product.dto.ProductDTO;
import com.cartiq.product.service.ProductService;
import com.cartiq.rag.config.RagConfig;
import com.cartiq.rag.service.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Service for exporting products to GCS in JSONL format for batch embedding.
 * This is Step 1 of the batch indexing pipeline.
 *
 * Creates two files:
 * 1. products.jsonl - Input for batch embedding ({"content": "text"} per line)
 * 2. metadata.jsonl - Product metadata for later correlation ({"id": "...", "categoryId": "...", ...})
 */
@Slf4j
@Service
public class BatchExportService {

    private final ProductService productService;
    private final EmbeddingService embeddingService;
    private final RagConfig ragConfig;
    private final Storage storage;
    private final ObjectMapper objectMapper;
    private final String projectId;

    /**
     * Result of product export operation.
     */
    public record ExportResult(
            String inputGcsUri,
            String metadataGcsUri,
            int productCount,
            long durationMs
    ) {}

    public BatchExportService(
            ProductService productService,
            EmbeddingService embeddingService,
            RagConfig ragConfig,
            @Value("${vertex.ai.project-id:}") String projectId) {
        this.productService = productService;
        this.embeddingService = embeddingService;
        this.ragConfig = ragConfig;
        this.projectId = projectId;
        this.objectMapper = new ObjectMapper();

        // Initialize GCS client
        Storage tempStorage = null;
        if (projectId != null && !projectId.isBlank()) {
            try {
                tempStorage = StorageOptions.newBuilder()
                        .setProjectId(projectId)
                        .build()
                        .getService();
                log.info("Initialized GCS client for batch export: project={}", projectId);
            } catch (Exception e) {
                log.error("Failed to initialize GCS client: {}", e.getMessage());
            }
        }
        this.storage = tempStorage;
    }

    /**
     * Export all products to GCS in JSONL format for batch embedding.
     *
     * Creates:
     * - gs://bucket/input/{timestamp}/products.jsonl (for batch prediction)
     * - gs://bucket/input/{timestamp}/metadata.jsonl (for correlation)
     *
     * @return ExportResult with GCS URIs and product count
     */
    public ExportResult exportProductsToGcs() {
        return exportProductsToGcs(generateTimestamp());
    }

    /**
     * Export all products to GCS with specified timestamp.
     *
     * @param timestamp Timestamp for unique folder naming
     * @return ExportResult with GCS URIs and product count
     */
    public ExportResult exportProductsToGcs(String timestamp) {
        if (storage == null) {
            throw new IllegalStateException("GCS client not initialized. Check project configuration.");
        }

        String bucket = ragConfig.getBatchIndexing().getGcsBucket();
        String prefix = ragConfig.getBatchIndexing().getInputPrefix();

        // Files are in timestamp folder: input/{timestamp}/products.jsonl
        String inputPath = String.format("%s/%s/products.jsonl", prefix, timestamp);
        String metadataPath = String.format("%s/%s/metadata.jsonl", prefix, timestamp);

        log.info("Starting product export to gs://{}/{}", bucket, inputPath);
        long startTime = System.currentTimeMillis();

        StringBuilder inputJsonl = new StringBuilder();
        StringBuilder metadataJsonl = new StringBuilder();
        int totalProducts = 0;
        int failed = 0;
        int pageSize = 500;
        int page = 0;
        Page<ProductDTO> productPage;

        do {
            productPage = productService.getAllProducts(PageRequest.of(page, pageSize));

            for (ProductDTO product : productPage.getContent()) {
                try {
                    // Build input line for batch embedding
                    String inputLine = buildInputLine(product);
                    inputJsonl.append(inputLine).append("\n");

                    // Build metadata line for later correlation
                    String metadataLine = buildMetadataLine(product);
                    metadataJsonl.append(metadataLine).append("\n");

                    totalProducts++;
                } catch (Exception e) {
                    log.warn("Failed to build JSON for product {}: {}", product.getId(), e.getMessage());
                    failed++;
                }
            }

            page++;
            if (page % 10 == 0) {
                log.info("Exported page {}/{}", page, productPage.getTotalPages());
            }
        } while (productPage.hasNext());

        // Upload input file to GCS
        uploadToGcs(bucket, inputPath, inputJsonl.toString());

        // Upload metadata file to GCS
        uploadToGcs(bucket, metadataPath, metadataJsonl.toString());

        long duration = System.currentTimeMillis() - startTime;
        String inputGcsUri = String.format("gs://%s/%s", bucket, inputPath);
        String metadataGcsUri = String.format("gs://%s/%s", bucket, metadataPath);

        log.info("Exported {} products ({} failed) to {} in {}ms",
                totalProducts, failed, inputGcsUri, duration);

        return new ExportResult(inputGcsUri, metadataGcsUri, totalProducts, duration);
    }

    /**
     * Build input line for batch embedding.
     * Format: {"content": "product text for embedding"}
     */
    private String buildInputLine(ProductDTO product) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();

        // Build the embedding text
        String embeddingText = embeddingService.buildProductEmbeddingText(
                product.getName(),
                product.getDescription(),
                product.getBrand(),
                product.getCategoryName()
        );

        // Simple format for text-embedding-004 batch prediction
        root.put("content", embeddingText);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * Build metadata line for later correlation.
     * Format: {"id": "...", "categoryId": "...", "brand": "...", "price": ..., "rating": ...}
     */
    private String buildMetadataLine(ProductDTO product) throws Exception {
        ObjectNode metadata = objectMapper.createObjectNode();

        metadata.put("id", product.getId().toString());

        if (product.getCategoryId() != null) {
            metadata.put("categoryId", product.getCategoryId().toString());
        }
        if (product.getBrand() != null && !product.getBrand().isBlank()) {
            metadata.put("brand", product.getBrand().toLowerCase());
        }
        if (product.getPrice() != null) {
            metadata.put("price", product.getPrice().doubleValue());
        }
        if (product.getRating() != null) {
            metadata.put("rating", product.getRating().doubleValue());
        }

        return objectMapper.writeValueAsString(metadata);
    }

    /**
     * Upload content to GCS.
     */
    private void uploadToGcs(String bucket, String path, String content) {
        BlobId blobId = BlobId.of(bucket, path);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType("application/jsonl")
                .build();

        storage.create(blobInfo, content.getBytes(StandardCharsets.UTF_8));
        log.debug("Uploaded to gs://{}/{}", bucket, path);
    }

    /**
     * Generate a timestamp string for unique folder naming.
     */
    public String generateTimestamp() {
        return Instant.now().toString().replace(":", "-").replace(".", "-");
    }

    /**
     * Check if the export service is available.
     */
    public boolean isAvailable() {
        return storage != null && ragConfig.isEnabled();
    }
}
