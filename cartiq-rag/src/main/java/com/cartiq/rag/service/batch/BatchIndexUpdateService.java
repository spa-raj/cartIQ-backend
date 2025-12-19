package com.cartiq.rag.service.batch;

import com.cartiq.rag.config.RagConfig;
import com.google.cloud.aiplatform.v1.*;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Struct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

/**
 * Service for updating Vector Search index from GCS.
 * This is Step 4 (final step) of the batch indexing pipeline.
 */
@Slf4j
@Service
public class BatchIndexUpdateService {

    private final RagConfig ragConfig;
    private final String projectId;
    private final String location;
    private IndexServiceClient indexServiceClient;

    public BatchIndexUpdateService(
            RagConfig ragConfig,
            @org.springframework.beans.factory.annotation.Value("${vertex.ai.project-id:}") String projectId,
            @org.springframework.beans.factory.annotation.Value("${vertex.ai.location:us-central1}") String location) {
        this.ragConfig = ragConfig;
        this.projectId = projectId;
        this.location = location;

        initializeClient();
    }

    private void initializeClient() {
        if (projectId == null || projectId.isBlank()) {
            log.warn("Project ID not configured for batch index update");
            return;
        }

        String endpoint = location + "-aiplatform.googleapis.com:443";
        try {
            IndexServiceSettings settings = IndexServiceSettings.newBuilder()
                    .setEndpoint(endpoint)
                    .build();
            this.indexServiceClient = IndexServiceClient.create(settings);
            log.info("Initialized IndexServiceClient for batch index updates: endpoint={}", endpoint);
        } catch (IOException e) {
            log.error("Failed to initialize IndexServiceClient: {}", e.getMessage());
        }
    }

    /**
     * Update the Vector Search index from GCS.
     *
     * @param vectorsGcsUri GCS URI containing Vector Search format data (gs://bucket/vectors/timestamp/)
     * @param completeOverwrite If true, completely replace index contents; if false, merge with existing
     * @return Updated index name
     */
    public String updateIndexFromGcs(String vectorsGcsUri, boolean completeOverwrite) {
        if (indexServiceClient == null) {
            throw new IllegalStateException("IndexServiceClient not initialized. Check project configuration.");
        }

        String indexId = ragConfig.getVectorSearch().getIndexId();
        if (indexId == null || indexId.isBlank()) {
            throw new IllegalStateException("Index ID not configured. Set cartiq.rag.vectorsearch.index-id");
        }

        String indexName = String.format("projects/%s/locations/%s/indexes/%s",
                projectId, location, indexId);

        log.info("Updating index {} from {}, completeOverwrite={}", indexName, vectorsGcsUri, completeOverwrite);
        long startTime = System.currentTimeMillis();

        try {
            // Build the metadata struct for index update
            Struct metadataStruct = Struct.newBuilder()
                    .putFields("contentsDeltaUri", com.google.protobuf.Value.newBuilder()
                            .setStringValue(vectorsGcsUri)
                            .build())
                    .putFields("isCompleteOverwrite", com.google.protobuf.Value.newBuilder()
                            .setBoolValue(completeOverwrite)
                            .build())
                    .build();

            // Build the index update request
            Index index = Index.newBuilder()
                    .setName(indexName)
                    .setMetadata(com.google.protobuf.Value.newBuilder()
                            .setStructValue(metadataStruct)
                            .build())
                    .build();

            FieldMask updateMask = FieldMask.newBuilder()
                    .addPaths("metadata")
                    .build();

            // Submit the update (this is a long-running operation)
            var operation = indexServiceClient.updateIndexAsync(index, updateMask);

            log.info("Index update operation submitted: {}", operation.getName());

            // Wait for completion
            Index updatedIndex = operation.get();

            long duration = System.currentTimeMillis() - startTime;
            log.info("Index update completed in {}ms: {}", duration, updatedIndex.getName());

            return updatedIndex.getName();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Index update interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Index update failed: " + e.getMessage(), e);
        }
    }

    /**
     * Update index using default configuration paths.
     *
     * @param timestamp Timestamp string for path resolution
     * @return Updated index name
     */
    public String updateIndexFromGcs(String timestamp) {
        String bucket = ragConfig.getBatchIndexing().getGcsBucket();
        String vectorsPrefix = ragConfig.getBatchIndexing().getVectorsPrefix();
        boolean completeOverwrite = ragConfig.getBatchIndexing().isCompleteOverwrite();

        String vectorsGcsUri = String.format("gs://%s/%s/%s/", bucket, vectorsPrefix, timestamp);

        return updateIndexFromGcs(vectorsGcsUri, completeOverwrite);
    }

    /**
     * Start index update without waiting for completion.
     * Returns operation name for tracking status.
     *
     * @param vectorsGcsUri GCS URI containing Vector Search format data
     * @param completeOverwrite If true, completely replace index contents
     * @return Operation name for tracking
     */
    public String startIndexUpdate(String vectorsGcsUri, boolean completeOverwrite) {
        if (indexServiceClient == null) {
            throw new IllegalStateException("IndexServiceClient not initialized. Check project configuration.");
        }

        String indexId = ragConfig.getVectorSearch().getIndexId();
        if (indexId == null || indexId.isBlank()) {
            throw new IllegalStateException("Index ID not configured. Set cartiq.rag.vectorsearch.index-id");
        }

        String indexName = String.format("projects/%s/locations/%s/indexes/%s",
                projectId, location, indexId);

        log.info("Starting async index update {} from {}", indexName, vectorsGcsUri);

        // Build the metadata struct for index update
        Struct metadataStruct = Struct.newBuilder()
                .putFields("contentsDeltaUri", com.google.protobuf.Value.newBuilder()
                        .setStringValue(vectorsGcsUri)
                        .build())
                .putFields("isCompleteOverwrite", com.google.protobuf.Value.newBuilder()
                        .setBoolValue(completeOverwrite)
                        .build())
                .build();

        Index index = Index.newBuilder()
                .setName(indexName)
                .setMetadata(com.google.protobuf.Value.newBuilder()
                        .setStructValue(metadataStruct)
                        .build())
                .build();

        FieldMask updateMask = FieldMask.newBuilder()
                .addPaths("metadata")
                .build();

        var operation = indexServiceClient.updateIndexAsync(index, updateMask);

        try {
            String operationName = operation.getName();
            log.info("Index update operation started: {}", operationName);
            return operationName;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while getting operation name", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to start index update operation: " + e.getMessage(), e);
        }
    }

    /**
     * Check if service is available.
     */
    public boolean isAvailable() {
        return indexServiceClient != null && ragConfig.isEnabled();
    }

    /**
     * Close the client when done.
     */
    public void close() {
        if (indexServiceClient != null) {
            indexServiceClient.close();
        }
    }
}
