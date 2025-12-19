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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

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
     * Uses streaming to avoid loading all data into memory.
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

        log.info("Transforming embeddings (streaming): metadata={}, embeddings={}, output={}",
                metadataGcsUri, embeddingsGcsPrefix, outputGcsUri);
        long startTime = System.currentTimeMillis();

        // Get embedding files in sorted order
        List<Blob> embeddingFiles = getEmbeddingFiles(embeddingsGcsPrefix);
        if (embeddingFiles.isEmpty()) {
            log.warn("No embeddings found in {}", embeddingsGcsPrefix);
            return 0;
        }
        log.info("Found {} embedding files", embeddingFiles.size());

        // Create output file with streaming write
        String outputBucket = extractBucket(outputGcsUri);
        String outputPath = extractPath(outputGcsUri);
        BlobId blobId = BlobId.of(outputBucket, outputPath);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType("application/jsonl")
                .build();

        int transformed = 0;
        int failed = 0;

        // Stream: read metadata line by line, match with embeddings, write output immediately
        try (WritableByteChannel outputChannel = storage.writer(blobInfo)) {
            // Create iterators for streaming
            Iterator<String> metadataIterator = createMetadataIterator(metadataGcsUri);
            Iterator<String> embeddingsIterator = createEmbeddingsIterator(embeddingFiles);

            int recordIndex = 0;
            while (metadataIterator.hasNext() && embeddingsIterator.hasNext()) {
                String metadataLine = metadataIterator.next();
                String embeddingLine = embeddingsIterator.next();

                try {
                    JsonNode metadata = objectMapper.readTree(metadataLine);
                    JsonNode embedding = objectMapper.readTree(embeddingLine);

                    String vectorSearchLine = buildVectorSearchLine(metadata, embedding);
                    if (vectorSearchLine != null) {
                        // Write directly to GCS
                        byte[] lineBytes = (vectorSearchLine + "\n").getBytes(StandardCharsets.UTF_8);
                        outputChannel.write(ByteBuffer.wrap(lineBytes));
                        transformed++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to transform record {}: {}", recordIndex, e.getMessage());
                    failed++;
                }

                recordIndex++;
                if (recordIndex % 5000 == 0) {
                    log.info("Progress: processed {} records, transformed {}", recordIndex, transformed);
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to write output to GCS: " + e.getMessage(), e);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Transformed {} embeddings ({} failed) to {} in {}ms",
                transformed, failed, outputGcsUri, duration);

        return transformed;
    }

    /**
     * Get sorted list of embedding files from GCS prefix.
     */
    private List<Blob> getEmbeddingFiles(String gcsPrefix) {
        String bucket = extractBucket(gcsPrefix);
        String prefix = extractPath(gcsPrefix);

        Bucket storageBucket = storage.get(bucket);
        if (storageBucket == null) {
            log.error("Bucket not found: {}", bucket);
            return List.of();
        }

        List<Blob> predictionFiles = new ArrayList<>();
        for (Blob blob : storageBucket.list(Storage.BlobListOption.prefix(prefix)).iterateAll()) {
            if (!blob.getName().endsWith("/") && blob.getName().contains("prediction")) {
                predictionFiles.add(blob);
            }
        }

        predictionFiles.sort((a, b) -> a.getName().compareTo(b.getName()));
        return predictionFiles;
    }

    /**
     * Create an iterator that reads metadata file line by line.
     */
    private Iterator<String> createMetadataIterator(String gcsUri) {
        String bucket = extractBucket(gcsUri);
        String path = extractPath(gcsUri);

        Blob blob = storage.get(BlobId.of(bucket, path));
        if (blob == null) {
            log.error("Metadata file not found: {}", gcsUri);
            return List.<String>of().iterator();
        }

        // Read content and create line iterator
        String content = new String(blob.getContent(), StandardCharsets.UTF_8);
        return new LineIterator(content);
    }

    /**
     * Create an iterator that reads embeddings from multiple files in order.
     */
    private Iterator<String> createEmbeddingsIterator(List<Blob> embeddingFiles) {
        return new MultiFileLineIterator(embeddingFiles);
    }

    /**
     * Simple line iterator that doesn't hold all lines in memory at once.
     */
    private static class LineIterator implements Iterator<String> {
        private final BufferedReader reader;
        private String nextLine;

        LineIterator(String content) {
            this.reader = new BufferedReader(new StringReader(content));
            advance();
        }

        private void advance() {
            try {
                do {
                    nextLine = reader.readLine();
                } while (nextLine != null && nextLine.isBlank());
            } catch (IOException e) {
                nextLine = null;
            }
        }

        @Override
        public boolean hasNext() {
            return nextLine != null;
        }

        @Override
        public String next() {
            if (nextLine == null) {
                throw new NoSuchElementException();
            }
            String result = nextLine;
            advance();
            return result;
        }
    }

    /**
     * Iterator that reads lines from multiple blobs in sequence.
     */
    private class MultiFileLineIterator implements Iterator<String> {
        private final Iterator<Blob> blobIterator;
        private Iterator<String> currentLineIterator;

        MultiFileLineIterator(List<Blob> blobs) {
            this.blobIterator = blobs.iterator();
            advanceToNextFile();
        }

        private void advanceToNextFile() {
            currentLineIterator = null;
            while (blobIterator.hasNext() && (currentLineIterator == null || !currentLineIterator.hasNext())) {
                Blob blob = blobIterator.next();
                log.debug("Reading embedding file: {}", blob.getName());
                String content = new String(blob.getContent(), StandardCharsets.UTF_8);
                currentLineIterator = new LineIterator(content);
            }
        }

        @Override
        public boolean hasNext() {
            if (currentLineIterator != null && currentLineIterator.hasNext()) {
                return true;
            }
            advanceToNextFile();
            return currentLineIterator != null && currentLineIterator.hasNext();
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return currentLineIterator.next();
        }
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
