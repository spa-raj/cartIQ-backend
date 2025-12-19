package com.cartiq.rag.controller;

import com.cartiq.rag.service.batch.*;
import com.google.cloud.aiplatform.v1.JobState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for batch indexing operations.
 * Provides endpoints for each step of the batch indexing pipeline.
 * Protected by API key - only accessible from CI/CD workflows.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/indexing/batch")
@RequiredArgsConstructor
public class BatchIndexingController {

    private final BatchIndexingOrchestrator orchestrator;
    private final BatchExportService batchExportService;
    private final BatchEmbeddingService batchEmbeddingService;
    private final EmbeddingTransformService embeddingTransformService;
    private final BatchIndexUpdateService batchIndexUpdateService;

    @Value("${cartiq.internal.api-key:}")
    private String internalApiKey;

    /**
     * Run the full batch indexing pipeline.
     * This is a long-running operation - consider using /start for async execution.
     *
     * POST /api/internal/indexing/batch/run
     */
    @PostMapping("/run")
    public ResponseEntity<?> runFullPipeline(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        log.info("Received request to run full batch indexing pipeline");

        if (!orchestrator.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Batch indexing services not available"));
        }

        try {
            BatchIndexingOrchestrator.BatchIndexingResult result = orchestrator.runFullPipeline();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Batch indexing failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Start the full batch indexing pipeline asynchronously.
     * Returns immediately with a tracking reference.
     *
     * POST /api/internal/indexing/batch/start
     */
    @PostMapping("/start")
    public ResponseEntity<?> startFullPipeline(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        log.info("Received request to start async batch indexing pipeline");

        if (!orchestrator.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Batch indexing services not available"));
        }

        // Fire and forget - result will be logged by the orchestrator
        orchestrator.runFullPipelineAsync();

        return ResponseEntity.accepted()
                .body(Map.of(
                        "message", "Batch indexing pipeline started",
                        "status", "RUNNING"
                ));
    }

    /**
     * Step 1: Export products to GCS.
     *
     * POST /api/internal/indexing/batch/export
     */
    @PostMapping("/export")
    public ResponseEntity<?> exportProducts(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestParam(required = false) String timestamp) {
        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        log.info("Received request to export products");

        if (!batchExportService.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Export service not available"));
        }

        try {
            BatchExportService.ExportResult result = timestamp != null
                    ? batchExportService.exportProductsToGcs(timestamp)
                    : batchExportService.exportProductsToGcs();

            return ResponseEntity.ok(Map.of(
                    "inputGcsUri", result.inputGcsUri(),
                    "metadataGcsUri", result.metadataGcsUri(),
                    "productCount", result.productCount(),
                    "durationMs", result.durationMs(),
                    "message", "Products exported successfully"
            ));
        } catch (Exception e) {
            log.error("Export failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Step 2: Submit batch embedding job.
     *
     * POST /api/internal/indexing/batch/embed
     */
    @PostMapping("/embed")
    public ResponseEntity<?> submitEmbeddingJob(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestParam String inputGcsUri,
            @RequestParam(required = false) String timestamp,
            @RequestParam(required = false) String outputGcsPrefix) {
        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        log.info("Received request to submit batch embedding job: input={}, timestamp={}", inputGcsUri, timestamp);

        if (!batchEmbeddingService.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Embedding service not available"));
        }

        try {
            String jobName;
            if (outputGcsPrefix != null) {
                jobName = batchEmbeddingService.submitBatchEmbeddingJob(inputGcsUri, outputGcsPrefix);
            } else if (timestamp != null) {
                // Use the same timestamp as export step to ensure path consistency
                jobName = batchEmbeddingService.submitBatchEmbeddingJobWithTimestamp(timestamp, inputGcsUri);
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Either timestamp or outputGcsPrefix is required"));
            }

            return ResponseEntity.ok(Map.of(
                    "jobName", jobName,
                    "message", "Batch embedding job submitted"
            ));
        } catch (Exception e) {
            log.error("Embedding job submission failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get status of a batch embedding job.
     *
     * GET /api/internal/indexing/batch/embed/status
     */
    @GetMapping("/embed/status")
    public ResponseEntity<?> getEmbeddingJobStatus(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestParam String jobName) {
        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        log.info("Checking status of embedding job: {}", jobName);

        if (!batchEmbeddingService.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Embedding service not available"));
        }

        try {
            JobState state = batchEmbeddingService.getJobStatus(jobName);
            String outputUri = null;

            if (state == JobState.JOB_STATE_SUCCEEDED) {
                outputUri = batchEmbeddingService.getJobOutputUri(jobName);
            }

            return ResponseEntity.ok(Map.of(
                    "jobName", jobName,
                    "state", state.name(),
                    "outputUri", outputUri != null ? outputUri : ""
            ));
        } catch (Exception e) {
            log.error("Failed to get job status: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Step 3: Transform embeddings to Vector Search format.
     *
     * POST /api/internal/indexing/batch/transform
     */
    @PostMapping("/transform")
    public ResponseEntity<?> transformEmbeddings(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestParam(required = false) String metadataGcsUri,
            @RequestParam(required = false) String embeddingsGcsPrefix,
            @RequestParam(required = false) String outputGcsUri,
            @RequestParam(required = false) String timestamp) {
        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        log.info("Received request to transform embeddings");

        if (!embeddingTransformService.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Transform service not available"));
        }

        try {
            int transformed;
            if (metadataGcsUri != null && embeddingsGcsPrefix != null && outputGcsUri != null) {
                transformed = embeddingTransformService.transformToVectorSearchFormat(
                        metadataGcsUri, embeddingsGcsPrefix, outputGcsUri);
            } else if (timestamp != null) {
                transformed = embeddingTransformService.transformToVectorSearchFormat(timestamp);
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Either (metadataGcsUri, embeddingsGcsPrefix, outputGcsUri) or timestamp required"));
            }

            return ResponseEntity.ok(Map.of(
                    "transformedCount", transformed,
                    "message", "Embeddings transformed successfully"
            ));
        } catch (Exception e) {
            log.error("Transform failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Step 4: Update Vector Search index from GCS.
     *
     * POST /api/internal/indexing/batch/update-index
     */
    @PostMapping("/update-index")
    public ResponseEntity<?> updateIndex(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestParam(required = false) String vectorsGcsUri,
            @RequestParam(required = false) String timestamp,
            @RequestParam(defaultValue = "true") boolean completeOverwrite) {
        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        log.info("Received request to update index");

        if (!batchIndexUpdateService.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Index update service not available"));
        }

        try {
            String result;
            if (vectorsGcsUri != null) {
                result = batchIndexUpdateService.updateIndexFromGcs(vectorsGcsUri, completeOverwrite);
            } else if (timestamp != null) {
                result = batchIndexUpdateService.updateIndexFromGcs(timestamp);
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Either vectorsGcsUri or timestamp required"));
            }

            return ResponseEntity.ok(Map.of(
                    "indexName", result,
                    "message", "Index updated successfully"
            ));
        } catch (Exception e) {
            log.error("Index update failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Health check for batch indexing services.
     *
     * GET /api/internal/indexing/batch/health
     */
    @GetMapping("/health")
    public ResponseEntity<?> healthCheck(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        return ResponseEntity.ok(Map.of(
                "available", orchestrator.isAvailable(),
                "exportService", batchExportService.isAvailable(),
                "embeddingService", batchEmbeddingService.isAvailable(),
                "transformService", embeddingTransformService.isAvailable(),
                "indexUpdateService", batchIndexUpdateService.isAvailable()
        ));
    }

    /**
     * Validate the internal API key.
     */
    private boolean isValidApiKey(String apiKey) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            log.warn("Internal API key not configured - rejecting request");
            return false;
        }
        return internalApiKey.equals(apiKey);
    }

    /**
     * Return unauthorized response.
     */
    private ResponseEntity<Map<String, Object>> unauthorizedResponse() {
        log.warn("Unauthorized batch indexing request - invalid API key");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "success", false,
                        "message", "Invalid or missing API key"
                ));
    }
}
