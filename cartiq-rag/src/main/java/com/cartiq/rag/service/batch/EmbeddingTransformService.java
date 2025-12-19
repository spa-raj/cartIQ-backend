package com.cartiq.rag.service.batch;

import com.cartiq.rag.config.RagConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.storage.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for transforming batch embedding output to Vector Search format.
 * This is Step 3 of the batch indexing pipeline.
 *
 * Reads:
 * - Batch prediction output: {"predictions":[{"embeddings":{"values":[...]}}]}
 * - Metadata file: {"id":"...","categoryId":"...","brand":"...","price":...,"rating":...}
 *
 * Produces Vector Search format:
 * {"id":"...","embedding":[...],"restricts":[...],"numeric_restricts":[...]}
 */
@Slf4j
@Service
public class EmbeddingTransformService {

    private final RagConfig ragConfig;
    private final Storage storage;
    private final ObjectMapper objectMapper;
    private final String projectId;

    public EmbeddingTransformService(
            RagConfig ragConfig,
            @Value("${vertex.ai.project-id:}") String projectId) {
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
                log.info("Initialized GCS client for embedding transformation");
            } catch (Exception e) {
                log.error("Failed to initialize GCS client: {}", e.getMessage());
            }
        }
        this.storage = tempStorage;
    }

    /**
     * Transform batch embedding output to Vector Search format using timestamp-based paths.
     *
     * @param timestamp Timestamp string for path resolution
     * @return Number of datapoints transformed
     */
    public int transformToVectorSearchFormat(String timestamp) {
        String bucket = ragConfig.getBatchIndexing().getGcsBucket();
        String inputPrefix = ragConfig.getBatchIndexing().getInputPrefix();
        String embeddingsPrefix = ragConfig.getBatchIndexing().getEmbeddingsPrefix();
        String vectorsPrefix = ragConfig.getBatchIndexing().getVectorsPrefix();

        // Paths based on timestamp
        String metadataGcsUri = String.format("gs://%s/%s/%s/metadata.jsonl", bucket, inputPrefix, timestamp);
        String embeddingsGcsPrefix = String.format("gs://%s/%s/%s/", bucket, embeddingsPrefix, timestamp);
        String outputGcsUri = String.format("gs://%s/%s/%s/products.jsonl", bucket, vectorsPrefix, timestamp);

        return transformToVectorSearchFormat(metadataGcsUri, embeddingsGcsPrefix, outputGcsUri);
    }

    /**
     * Transform batch embedding output to Vector Search format.
     *
     * @param metadataGcsUri GCS URI of metadata file (gs://bucket/input/timestamp/metadata.jsonl)
     * @param embeddingsGcsPrefix GCS URI prefix containing embedding output (gs://bucket/embeddings/timestamp/)
     * @param outputGcsUri GCS URI for Vector Search format output (gs://bucket/vectors/timestamp/products.jsonl)
     * @return Number of datapoints transformed
     */
    public int transformToVectorSearchFormat(String metadataGcsUri, String embeddingsGcsPrefix, String outputGcsUri) {
        if (storage == null) {
            throw new IllegalStateException("GCS client not initialized. Check project configuration.");
        }

        log.info("Transforming embeddings: metadata={}, embeddings={}, output={}",
                metadataGcsUri, embeddingsGcsPrefix, outputGcsUri);
        long startTime = System.currentTimeMillis();

        // Step 1: Read metadata file (preserves order)
        List<JsonNode> metadataList = readMetadataFile(metadataGcsUri);
        if (metadataList.isEmpty()) {
            log.warn("No metadata found in {}", metadataGcsUri);
            return 0;
        }
        log.info("Loaded {} metadata records", metadataList.size());

        // Step 2: Read all embedding outputs (preserves order from batch prediction)
        List<JsonNode> embeddingsList = readEmbeddingsFiles(embeddingsGcsPrefix);
        if (embeddingsList.isEmpty()) {
            log.warn("No embeddings found in {}", embeddingsGcsPrefix);
            return 0;
        }
        log.info("Loaded {} embedding records", embeddingsList.size());

        // Step 3: Verify counts match
        if (metadataList.size() != embeddingsList.size()) {
            log.warn("Metadata count ({}) != Embeddings count ({}). Using minimum.",
                    metadataList.size(), embeddingsList.size());
        }

        int count = Math.min(metadataList.size(), embeddingsList.size());

        // Step 4: Combine and transform
        StringBuilder output = new StringBuilder();
        int transformed = 0;
        int failed = 0;

        for (int i = 0; i < count; i++) {
            try {
                JsonNode metadata = metadataList.get(i);
                JsonNode embedding = embeddingsList.get(i);

                String vectorSearchLine = buildVectorSearchLine(metadata, embedding);
                if (vectorSearchLine != null) {
                    output.append(vectorSearchLine).append("\n");
                    transformed++;
                }
            } catch (Exception e) {
                log.warn("Failed to transform record {}: {}", i, e.getMessage());
                failed++;
            }
        }

        // Step 5: Write output to GCS
        String outputBucket = extractBucket(outputGcsUri);
        String outputPath = extractPath(outputGcsUri);

        BlobId blobId = BlobId.of(outputBucket, outputPath);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType("application/jsonl")
                .build();

        storage.create(blobInfo, output.toString().getBytes(StandardCharsets.UTF_8));

        long duration = System.currentTimeMillis() - startTime;
        log.info("Transformed {} embeddings ({} failed) to {} in {}ms",
                transformed, failed, outputGcsUri, duration);

        return transformed;
    }

    /**
     * Read metadata file from GCS.
     */
    private List<JsonNode> readMetadataFile(String gcsUri) {
        List<JsonNode> result = new ArrayList<>();

        String bucket = extractBucket(gcsUri);
        String path = extractPath(gcsUri);

        Blob blob = storage.get(BlobId.of(bucket, path));
        if (blob == null) {
            log.error("Metadata file not found: {}", gcsUri);
            return result;
        }

        String content = new String(blob.getContent(), StandardCharsets.UTF_8);
        for (String line : content.split("\n")) {
            if (!line.isBlank()) {
                try {
                    result.add(objectMapper.readTree(line));
                } catch (Exception e) {
                    log.warn("Failed to parse metadata line: {}", e.getMessage());
                }
            }
        }

        return result;
    }

    /**
     * Read all embedding output files from GCS prefix.
     * Batch prediction outputs are named like: predictions-00001-of-00010.jsonl
     */
    private List<JsonNode> readEmbeddingsFiles(String gcsPrefix) {
        List<JsonNode> result = new ArrayList<>();

        String bucket = extractBucket(gcsPrefix);
        String prefix = extractPath(gcsPrefix);

        Bucket storageBucket = storage.get(bucket);
        if (storageBucket == null) {
            log.error("Bucket not found: {}", bucket);
            return result;
        }

        // Collect all prediction files and sort them
        List<Blob> predictionFiles = new ArrayList<>();
        for (Blob blob : storageBucket.list(Storage.BlobListOption.prefix(prefix)).iterateAll()) {
            if (!blob.getName().endsWith("/") && blob.getName().contains("prediction")) {
                predictionFiles.add(blob);
            }
        }

        // Sort by name to maintain order
        predictionFiles.sort((a, b) -> a.getName().compareTo(b.getName()));

        // Read each file in order
        for (Blob blob : predictionFiles) {
            log.debug("Reading prediction file: {}", blob.getName());
            String content = new String(blob.getContent(), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                if (!line.isBlank()) {
                    try {
                        result.add(objectMapper.readTree(line));
                    } catch (Exception e) {
                        log.warn("Failed to parse embedding line: {}", e.getMessage());
                    }
                }
            }
        }

        return result;
    }

    /**
     * Build a Vector Search datapoint from metadata and embedding.
     */
    private String buildVectorSearchLine(JsonNode metadata, JsonNode embeddingResponse) throws Exception {
        // Extract embedding values from batch prediction response
        // Format: {"predictions":[{"embeddings":{"values":[...]}}]}
        JsonNode predictions = embeddingResponse.get("predictions");
        if (predictions == null || !predictions.isArray() || predictions.isEmpty()) {
            log.warn("No predictions in embedding response");
            return null;
        }

        JsonNode embeddings = predictions.get(0).get("embeddings");
        if (embeddings == null) {
            log.warn("No embeddings in prediction");
            return null;
        }

        JsonNode embeddingValues = embeddings.get("values");
        if (embeddingValues == null || !embeddingValues.isArray()) {
            log.warn("No embedding values found");
            return null;
        }

        // Get product ID from metadata
        if (!metadata.has("id")) {
            log.warn("No id in metadata");
            return null;
        }

        // Build Vector Search datapoint
        ObjectNode datapoint = objectMapper.createObjectNode();
        datapoint.put("id", metadata.get("id").asText());

        // Add embedding
        ArrayNode embedding = objectMapper.createArrayNode();
        for (JsonNode value : embeddingValues) {
            embedding.add(value.doubleValue());
        }
        datapoint.set("embedding", embedding);

        // Add categorical restricts
        ArrayNode restricts = objectMapper.createArrayNode();

        if (metadata.has("categoryId") && !metadata.get("categoryId").isNull()) {
            ObjectNode categoryRestrict = objectMapper.createObjectNode();
            categoryRestrict.put("namespace", "category_id");
            ArrayNode allowList = objectMapper.createArrayNode();
            allowList.add(metadata.get("categoryId").asText());
            categoryRestrict.set("allow", allowList);
            restricts.add(categoryRestrict);
        }

        if (metadata.has("brand") && !metadata.get("brand").isNull()) {
            ObjectNode brandRestrict = objectMapper.createObjectNode();
            brandRestrict.put("namespace", "brand");
            ArrayNode allowList = objectMapper.createArrayNode();
            allowList.add(metadata.get("brand").asText());
            brandRestrict.set("allow", allowList);
            restricts.add(brandRestrict);
        }

        if (!restricts.isEmpty()) {
            datapoint.set("restricts", restricts);
        }

        // Add numeric restricts
        ArrayNode numericRestricts = objectMapper.createArrayNode();

        if (metadata.has("price") && !metadata.get("price").isNull()) {
            ObjectNode priceRestrict = objectMapper.createObjectNode();
            priceRestrict.put("namespace", "price");
            priceRestrict.put("value_double", metadata.get("price").asDouble());
            numericRestricts.add(priceRestrict);
        }

        if (metadata.has("rating") && !metadata.get("rating").isNull()) {
            ObjectNode ratingRestrict = objectMapper.createObjectNode();
            ratingRestrict.put("namespace", "rating");
            ratingRestrict.put("value_double", metadata.get("rating").asDouble());
            numericRestricts.add(ratingRestrict);
        }

        if (!numericRestricts.isEmpty()) {
            datapoint.set("numeric_restricts", numericRestricts);
        }

        return objectMapper.writeValueAsString(datapoint);
    }

    /**
     * Extract bucket name from GCS URI.
     */
    private String extractBucket(String gcsUri) {
        String withoutPrefix = gcsUri.replace("gs://", "");
        int slashIndex = withoutPrefix.indexOf('/');
        return slashIndex > 0 ? withoutPrefix.substring(0, slashIndex) : withoutPrefix;
    }

    /**
     * Extract path from GCS URI.
     */
    private String extractPath(String gcsUri) {
        String withoutPrefix = gcsUri.replace("gs://", "");
        int slashIndex = withoutPrefix.indexOf('/');
        return slashIndex > 0 ? withoutPrefix.substring(slashIndex + 1) : "";
    }

    /**
     * Check if service is available.
     */
    public boolean isAvailable() {
        return storage != null && ragConfig.isEnabled();
    }
}
