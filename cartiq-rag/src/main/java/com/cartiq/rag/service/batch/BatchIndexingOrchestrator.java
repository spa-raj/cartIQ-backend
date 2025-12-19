package com.cartiq.rag.service.batch;

import com.google.cloud.aiplatform.v1.JobState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the full batch indexing pipeline.
 * Coordinates: Export -> Batch Embedding -> Transform -> Index Update
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchIndexingOrchestrator {

    private final BatchExportService batchExportService;
    private final BatchEmbeddingService batchEmbeddingService;
    private final EmbeddingTransformService embeddingTransformService;
    private final BatchIndexUpdateService batchIndexUpdateService;

    /**
     * Result of a batch indexing run.
     */
    public record BatchIndexingResult(
            String timestamp,
            String inputGcsUri,
            String embeddingJobName,
            String embeddingsOutputUri,
            String vectorsGcsUri,
            int productsExported,
            int embeddingsTransformed,
            JobState embeddingJobState,
            String indexUpdateResult,
            long durationMs,
            boolean success,
            String errorMessage
    ) {
        public static BatchIndexingResult failure(String timestamp, String errorMessage) {
            return new BatchIndexingResult(
                    timestamp, null, null, null, null,
                    0, 0, null, null, 0, false, errorMessage
            );
        }
    }

    /**
     * Run the full batch indexing pipeline synchronously.
     * This is a long-running operation (can take 15-30+ minutes for large catalogs).
     *
     * @return BatchIndexingResult with details of the run
     */
    public BatchIndexingResult runFullPipeline() {
        String timestamp = generateTimestamp();
        long startTime = System.currentTimeMillis();

        log.info("Starting full batch indexing pipeline: timestamp={}", timestamp);

        try {
            // Step 1: Export products to GCS
            log.info("Step 1/4: Exporting products to GCS...");
            BatchExportService.ExportResult exportResult = batchExportService.exportProductsToGcs(timestamp);
            String inputGcsUri = exportResult.inputGcsUri();
            int productsExported = exportResult.productCount();
            log.info("Export complete: {} ({} products)", inputGcsUri, productsExported);

            // Step 2: Submit batch embedding job
            log.info("Step 2/4: Submitting batch embedding job...");
            String embeddingJobName = batchEmbeddingService.submitBatchEmbeddingJobWithTimestamp(timestamp, inputGcsUri);
            log.info("Batch job submitted: {}", embeddingJobName);

            // Wait for embedding job to complete (poll every 30 seconds, timeout 2 hours)
            log.info("Waiting for batch embedding job to complete...");
            JobState jobState = batchEmbeddingService.waitForJobCompletion(
                    embeddingJobName, 30, 7200);

            if (jobState != JobState.JOB_STATE_SUCCEEDED) {
                String errorMsg = "Batch embedding job failed with state: " + jobState;
                log.error(errorMsg);
                return new BatchIndexingResult(
                        timestamp, inputGcsUri, embeddingJobName, null, null,
                        productsExported, 0, jobState, null,
                        System.currentTimeMillis() - startTime, false, errorMsg
                );
            }

            String embeddingsOutputUri = batchEmbeddingService.getJobOutputUri(embeddingJobName);
            log.info("Batch embedding complete: {}", embeddingsOutputUri);

            // Step 3: Transform embeddings to Vector Search format
            log.info("Step 3/4: Transforming embeddings to Vector Search format...");
            int transformed = embeddingTransformService.transformToVectorSearchFormat(timestamp);
            log.info("Transformation complete: {} embeddings", transformed);

            // Step 4: Update Vector Search index
            log.info("Step 4/4: Updating Vector Search index...");
            String indexUpdateResult = batchIndexUpdateService.updateIndexFromGcs(timestamp);
            log.info("Index update complete: {}", indexUpdateResult);

            long duration = System.currentTimeMillis() - startTime;
            String bucket = getBucketFromUri(inputGcsUri);
            String vectorsGcsUri = String.format("gs://%s/vectors/%s/", bucket, timestamp);

            log.info("Full batch indexing pipeline completed in {}ms", duration);

            return new BatchIndexingResult(
                    timestamp, inputGcsUri, embeddingJobName, embeddingsOutputUri, vectorsGcsUri,
                    productsExported, transformed, jobState, indexUpdateResult, duration, true, null
            );

        } catch (Exception e) {
            log.error("Batch indexing pipeline failed: {}", e.getMessage(), e);
            return BatchIndexingResult.failure(timestamp, e.getMessage());
        }
    }

    /**
     * Run the full batch indexing pipeline asynchronously.
     *
     * @return CompletableFuture with the result
     */
    @Async
    public CompletableFuture<BatchIndexingResult> runFullPipelineAsync() {
        return CompletableFuture.completedFuture(runFullPipeline());
    }

    /**
     * Run only the export step.
     */
    public BatchExportService.ExportResult runExportOnly() {
        String timestamp = generateTimestamp();
        return batchExportService.exportProductsToGcs(timestamp);
    }

    /**
     * Run only the batch embedding step (assumes export was already done).
     *
     * @param inputGcsUri GCS URI of the exported products
     * @return Job name for tracking
     */
    public String runBatchEmbeddingOnly(String inputGcsUri) {
        String timestamp = extractTimestampFromUri(inputGcsUri);
        return batchEmbeddingService.submitBatchEmbeddingJobWithTimestamp(timestamp, inputGcsUri);
    }

    /**
     * Run only the transformation step (assumes embedding job completed).
     *
     * @param timestamp Timestamp for path resolution
     * @return Number of embeddings transformed
     */
    public int runTransformOnly(String timestamp) {
        return embeddingTransformService.transformToVectorSearchFormat(timestamp);
    }

    /**
     * Run only the index update step (assumes transformation completed).
     *
     * @param timestamp Timestamp for path resolution
     * @return Index update result
     */
    public String runIndexUpdateOnly(String timestamp) {
        return batchIndexUpdateService.updateIndexFromGcs(timestamp);
    }

    /**
     * Get the status of a batch embedding job.
     */
    public JobState getEmbeddingJobStatus(String jobName) {
        return batchEmbeddingService.getJobStatus(jobName);
    }

    /**
     * Check if all services are available.
     */
    public boolean isAvailable() {
        return batchExportService.isAvailable()
                && batchEmbeddingService.isAvailable()
                && embeddingTransformService.isAvailable()
                && batchIndexUpdateService.isAvailable();
    }

    private String generateTimestamp() {
        return Instant.now().toString()
                .replace(":", "-")
                .replace(".", "-");
    }

    private String getBucketFromUri(String gcsUri) {
        String withoutPrefix = gcsUri.replace("gs://", "");
        int slashIndex = withoutPrefix.indexOf('/');
        return slashIndex > 0 ? withoutPrefix.substring(0, slashIndex) : withoutPrefix;
    }

    private String extractTimestampFromUri(String gcsUri) {
        // Extract timestamp from paths like gs://bucket/input/2024-01-15T10-30-00-000Z/products.jsonl
        String[] parts = gcsUri.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].contains("T") && parts[i].contains("-")) {
                return parts[i];
            }
        }
        return generateTimestamp();
    }
}
