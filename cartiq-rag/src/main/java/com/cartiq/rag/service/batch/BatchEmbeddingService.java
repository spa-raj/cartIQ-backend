package com.cartiq.rag.service.batch;

import com.cartiq.rag.config.RagConfig;
import com.google.cloud.aiplatform.v1.*;
import com.google.cloud.aiplatform.v1.BatchPredictionJob.InputConfig;
import com.google.cloud.aiplatform.v1.BatchPredictionJob.OutputConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Service for submitting batch embedding prediction jobs to Vertex AI.
 * This is Step 2 of the batch indexing pipeline.
 */
@Slf4j
@Service
public class BatchEmbeddingService {

    private final RagConfig ragConfig;
    private final String projectId;
    private final String location;
    private JobServiceClient jobServiceClient;

    public BatchEmbeddingService(
            RagConfig ragConfig,
            @Value("${vertex.ai.project-id:}") String projectId,
            @Value("${vertex.ai.location:us-central1}") String location) {
        this.ragConfig = ragConfig;
        this.projectId = projectId;
        this.location = location;

        initializeClient();
    }

    private void initializeClient() {
        if (projectId == null || projectId.isBlank()) {
            log.warn("Project ID not configured for batch embedding");
            return;
        }

        String endpoint = location + "-aiplatform.googleapis.com:443";
        try {
            JobServiceSettings settings = JobServiceSettings.newBuilder()
                    .setEndpoint(endpoint)
                    .build();
            this.jobServiceClient = JobServiceClient.create(settings);
            log.info("Initialized JobServiceClient for batch embeddings: endpoint={}", endpoint);
        } catch (IOException e) {
            log.error("Failed to initialize JobServiceClient: {}", e.getMessage());
        }
    }

    /**
     * Submit a batch embedding prediction job.
     *
     * @param inputGcsUri GCS URI of the input JSONL file (gs://bucket/path/products.jsonl)
     * @param outputGcsPrefix GCS URI prefix for output (gs://bucket/embeddings/)
     * @return Job resource name for tracking status
     */
    public String submitBatchEmbeddingJob(String inputGcsUri, String outputGcsPrefix) {
        if (jobServiceClient == null) {
            throw new IllegalStateException("JobServiceClient not initialized. Check project configuration.");
        }

        String embeddingModel = ragConfig.getBatchIndexing().getEmbeddingModel();
        String modelPath = String.format("publishers/google/models/%s", embeddingModel);
        String displayName = "cartiq-product-embeddings-" + System.currentTimeMillis();

        log.info("Submitting batch embedding job: model={}, input={}, output={}",
                modelPath, inputGcsUri, outputGcsPrefix);

        BatchPredictionJob job = BatchPredictionJob.newBuilder()
                .setDisplayName(displayName)
                .setModel(modelPath)
                .setInputConfig(InputConfig.newBuilder()
                        .setInstancesFormat("jsonl")
                        .setGcsSource(GcsSource.newBuilder()
                                .addUris(inputGcsUri)
                                .build())
                        .build())
                .setOutputConfig(OutputConfig.newBuilder()
                        .setPredictionsFormat("jsonl")
                        .setGcsDestination(GcsDestination.newBuilder()
                                .setOutputUriPrefix(outputGcsPrefix)
                                .build())
                        .build())
                .build();

        LocationName parent = LocationName.of(projectId, location);
        BatchPredictionJob createdJob = jobServiceClient.createBatchPredictionJob(parent, job);

        String jobName = createdJob.getName();
        log.info("Batch embedding job submitted: {}", jobName);

        return jobName;
    }

    /**
     * Submit a batch embedding job using default GCS paths from configuration.
     *
     * @param timestamp Timestamp string for unique paths
     * @param inputGcsUri GCS URI of input file
     * @return Job resource name
     */
    public String submitBatchEmbeddingJobWithTimestamp(String timestamp, String inputGcsUri) {
        String bucket = ragConfig.getBatchIndexing().getGcsBucket();
        String embeddingsPrefix = ragConfig.getBatchIndexing().getEmbeddingsPrefix();
        String outputGcsPrefix = String.format("gs://%s/%s/%s/", bucket, embeddingsPrefix, timestamp);

        return submitBatchEmbeddingJob(inputGcsUri, outputGcsPrefix);
    }

    /**
     * Get the status of a batch prediction job.
     *
     * @param jobName Full job resource name
     * @return Job state
     */
    public JobState getJobStatus(String jobName) {
        if (jobServiceClient == null) {
            throw new IllegalStateException("JobServiceClient not initialized");
        }

        BatchPredictionJob job = jobServiceClient.getBatchPredictionJob(jobName);
        return job.getState();
    }

    /**
     * Get full details of a batch prediction job.
     *
     * @param jobName Full job resource name
     * @return BatchPredictionJob details
     */
    public BatchPredictionJob getJobDetails(String jobName) {
        if (jobServiceClient == null) {
            throw new IllegalStateException("JobServiceClient not initialized");
        }

        return jobServiceClient.getBatchPredictionJob(jobName);
    }

    /**
     * Wait for a batch job to complete, polling at the specified interval.
     *
     * @param jobName Full job resource name
     * @param pollIntervalSeconds Seconds between status checks
     * @param timeoutSeconds Maximum time to wait
     * @return Final job state
     * @throws InterruptedException if interrupted while waiting
     */
    public JobState waitForJobCompletion(String jobName, int pollIntervalSeconds, int timeoutSeconds)
            throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000L;

        while (true) {
            JobState state = getJobStatus(jobName);
            log.info("Batch job {} state: {}", jobName, state);

            if (state == JobState.JOB_STATE_SUCCEEDED ||
                state == JobState.JOB_STATE_FAILED ||
                state == JobState.JOB_STATE_CANCELLED) {
                return state;
            }

            if (System.currentTimeMillis() - startTime > timeoutMs) {
                log.warn("Timeout waiting for batch job completion: {}", jobName);
                return state;
            }

            Thread.sleep(pollIntervalSeconds * 1000L);
        }
    }

    /**
     * Get the output GCS URI prefix for a completed job.
     *
     * @param jobName Full job resource name
     * @return GCS URI prefix where output files are stored
     */
    public String getJobOutputUri(String jobName) {
        BatchPredictionJob job = getJobDetails(jobName);
        return job.getOutputConfig().getGcsDestination().getOutputUriPrefix();
    }

    /**
     * Check if the service is available.
     */
    public boolean isAvailable() {
        return jobServiceClient != null && ragConfig.isEnabled();
    }

    /**
     * Close the client when done.
     */
    public void close() {
        if (jobServiceClient != null) {
            jobServiceClient.close();
        }
    }
}
