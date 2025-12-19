# Batch Indexing for Vector Search

This document describes the batch indexing approach for indexing products into Vertex AI Vector Search. This approach is recommended for large datasets (1000+ products) as it avoids rate limiting issues with the online embedding API.

## Problem with Online Indexing

The current streaming approach (`ProductIndexingService`) has limitations:

| Issue | Impact |
|-------|--------|
| Embedding API quota | ~60 requests/minute limit |
| Rate limiting (429 errors) | Indexing failures, retries |
| Cloud Run instance termination | Async tasks killed mid-execution |
| Time to index 38,000 products | 10+ hours at 1 req/sec |

## Batch Indexing Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Products DB    │ ──► │  Batch Embedding │ ──► │  Vector Search  │
│  (PostgreSQL)   │     │  Job (Vertex AI) │     │  Index Update   │
└─────────────────┘     └──────────────────┘     └─────────────────┘
        │                       │                        │
        ▼                       ▼                        ▼
   1. Export to GCS       2. Batch Predict         3. Update Index
   (JSONL format)         (no rate limits)         (from GCS)
```

### Benefits

- **No rate limiting** - Batch API processes thousands of requests without 429 errors
- **Fast** - Index 38,000 products in minutes, not hours
- **Reliable** - No Cloud Run instance termination issues
- **Cost effective** - Batch pricing is lower than online predictions

## Implementation Steps

### Step 1: Export Products to Cloud Storage

Export products as JSONL to a Cloud Storage bucket.

**Input Format** (`gs://cartiq-data/indexing/input/products.jsonl`):
```json
{"request":{"contents":[{"parts":[{"text":"iPhone 15 Pro. Latest flagship smartphone with A17 chip. Brand: Apple. Category: Electronics"}]}]},"metadata":{"id":"uuid-1","categoryId":"cat-1","brand":"apple","price":999.99,"rating":4.8}}
{"request":{"contents":[{"parts":[{"text":"Nike Air Max 90. Classic running shoes. Brand: Nike. Category: Footwear"}]}]},"metadata":{"id":"uuid-2","categoryId":"cat-2","brand":"nike","price":129.99,"rating":4.5}}
```

**Java Implementation**:
```java
@Service
public class BatchExportService {

    private final ProductService productService;
    private final Storage storage;

    public String exportProductsToGcs(String bucketName, String blobName) {
        StringBuilder jsonl = new StringBuilder();

        productService.getAllProducts(Pageable.unpaged()).forEach(product -> {
            String text = buildEmbeddingText(product);
            String line = String.format(
                "{\"request\":{\"contents\":[{\"parts\":[{\"text\":\"%s\"}]}]},\"metadata\":{\"id\":\"%s\",\"categoryId\":\"%s\",\"brand\":\"%s\",\"price\":%.2f,\"rating\":%.2f}}",
                escapeJson(text),
                product.getId(),
                product.getCategoryId(),
                product.getBrand().toLowerCase(),
                product.getPrice(),
                product.getRating()
            );
            jsonl.append(line).append("\n");
        });

        BlobId blobId = BlobId.of(bucketName, blobName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("application/jsonl").build();
        storage.create(blobInfo, jsonl.toString().getBytes(StandardCharsets.UTF_8));

        return String.format("gs://%s/%s", bucketName, blobName);
    }
}
```

### Step 2: Submit Batch Embedding Job

Use Vertex AI Batch Prediction API to generate embeddings.

**REST API**:
```bash
curl -X POST \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H "Content-Type: application/json" \
  "https://us-central1-aiplatform.googleapis.com/v1/projects/${PROJECT_ID}/locations/us-central1/batchPredictionJobs" \
  -d '{
    "displayName": "cartiq-product-embeddings-batch",
    "model": "publishers/google/models/text-embedding-004",
    "inputConfig": {
      "instancesFormat": "jsonl",
      "gcsSource": {
        "uris": ["gs://cartiq-data/indexing/input/products.jsonl"]
      }
    },
    "outputConfig": {
      "predictionsFormat": "jsonl",
      "gcsDestination": {
        "outputUriPrefix": "gs://cartiq-data/indexing/embeddings-output/"
      }
    }
  }'
```

**Java Implementation**:
```java
@Service
public class BatchEmbeddingService {

    private final JobServiceClient jobServiceClient;
    private final String projectId;
    private final String location;

    public String submitBatchEmbeddingJob(String inputGcsUri, String outputGcsPrefix) {
        BatchPredictionJob job = BatchPredictionJob.newBuilder()
            .setDisplayName("cartiq-product-embeddings-" + System.currentTimeMillis())
            .setModel("publishers/google/models/text-embedding-004")
            .setInputConfig(BatchPredictionJob.InputConfig.newBuilder()
                .setInstancesFormat("jsonl")
                .setGcsSource(GcsSource.newBuilder()
                    .addUris(inputGcsUri)
                    .build())
                .build())
            .setOutputConfig(BatchPredictionJob.OutputConfig.newBuilder()
                .setPredictionsFormat("jsonl")
                .setGcsDestination(GcsDestination.newBuilder()
                    .setOutputUriPrefix(outputGcsPrefix)
                    .build())
                .build())
            .build();

        LocationName parent = LocationName.of(projectId, location);
        BatchPredictionJob createdJob = jobServiceClient.createBatchPredictionJob(parent, job);

        return createdJob.getName(); // Job resource name for polling
    }

    public JobState getJobStatus(String jobName) {
        BatchPredictionJob job = jobServiceClient.getBatchPredictionJob(jobName);
        return job.getState();
    }
}
```

### Step 3: Transform Embeddings to Vector Search Format

The batch prediction output needs to be transformed to Vector Search format.

**Batch Prediction Output** (`gs://cartiq-data/indexing/embeddings-output/predictions.jsonl`):
```json
{"request":{"contents":[...]},"response":{"embeddings":[{"values":[0.1,0.2,...]}]},"metadata":{"id":"uuid-1",...}}
```

**Vector Search Format** (`gs://cartiq-data/indexing/vectors/products.jsonl`):
```json
{"id":"uuid-1","embedding":[0.1,0.2,...],"restricts":[{"namespace":"category_id","allow":["cat-1"]},{"namespace":"brand","allow":["apple"]}],"numeric_restricts":[{"namespace":"price","value_double":999.99},{"namespace":"rating","value_double":4.8}]}
```

**Java Transformation**:
```java
@Service
public class EmbeddingTransformService {

    public void transformToVectorSearchFormat(String inputGcsUri, String outputGcsUri) {
        // Read batch prediction output
        Blob inputBlob = storage.get(BlobId.fromGsUtilUri(inputGcsUri));
        String content = new String(inputBlob.getContent(), StandardCharsets.UTF_8);

        StringBuilder output = new StringBuilder();
        for (String line : content.split("\n")) {
            if (line.isBlank()) continue;

            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            JsonObject metadata = obj.getAsJsonObject("metadata");
            JsonArray embedding = obj.getAsJsonObject("response")
                .getAsJsonArray("embeddings").get(0).getAsJsonObject()
                .getAsJsonArray("values");

            // Build Vector Search datapoint
            JsonObject datapoint = new JsonObject();
            datapoint.addProperty("id", metadata.get("id").getAsString());
            datapoint.add("embedding", embedding);

            // Add restricts for filtering
            JsonArray restricts = new JsonArray();
            addRestrict(restricts, "category_id", metadata.get("categoryId").getAsString());
            addRestrict(restricts, "brand", metadata.get("brand").getAsString());
            datapoint.add("restricts", restricts);

            // Add numeric restricts
            JsonArray numericRestricts = new JsonArray();
            addNumericRestrict(numericRestricts, "price", metadata.get("price").getAsDouble());
            addNumericRestrict(numericRestricts, "rating", metadata.get("rating").getAsDouble());
            datapoint.add("numeric_restricts", numericRestricts);

            output.append(datapoint.toString()).append("\n");
        }

        // Write to GCS
        BlobId outputBlobId = BlobId.fromGsUtilUri(outputGcsUri);
        storage.create(BlobInfo.newBuilder(outputBlobId).build(),
            output.toString().getBytes(StandardCharsets.UTF_8));
    }
}
```

### Step 4: Update Vector Search Index

Update the index from Cloud Storage.

**Using gcloud CLI**:
```bash
# Create metadata file
cat > /tmp/update-metadata.json << EOF
{
  "contentsDeltaUri": "gs://cartiq-data/indexing/vectors/",
  "isCompleteOverwrite": true
}
EOF

# Update index
gcloud ai indexes update ${INDEX_ID} \
  --metadata-file=/tmp/update-metadata.json \
  --region=us-central1 \
  --project=${PROJECT_ID}
```

**Java Implementation**:
```java
@Service
public class BatchIndexUpdateService {

    private final IndexServiceClient indexServiceClient;

    public void updateIndexFromGcs(String indexId, String vectorsGcsUri, boolean fullOverwrite) {
        String indexName = String.format("projects/%s/locations/%s/indexes/%s",
            projectId, location, indexId);

        Index index = Index.newBuilder()
            .setName(indexName)
            .setMetadata(Value.newBuilder()
                .setStructValue(Struct.newBuilder()
                    .putFields("contentsDeltaUri", Value.newBuilder()
                        .setStringValue(vectorsGcsUri)
                        .build())
                    .putFields("isCompleteOverwrite", Value.newBuilder()
                        .setBoolValue(fullOverwrite)
                        .build())
                    .build())
                .build())
            .build();

        FieldMask updateMask = FieldMask.newBuilder()
            .addPaths("metadata")
            .build();

        // This is a long-running operation
        OperationFuture<Index, UpdateIndexOperationMetadata> operation =
            indexServiceClient.updateIndexAsync(index, updateMask);

        // Wait for completion (or poll status)
        Index updatedIndex = operation.get();
        log.info("Index updated: {}", updatedIndex.getName());
    }
}
```

## Complete Batch Indexing Workflow

### Option A: Manual Workflow (for occasional reindexing)

```bash
#!/bin/bash
# batch-index.sh

PROJECT_ID="cartiq-480815"
REGION="us-central1"
BUCKET="cartiq-indexing-data"
INDEX_ID="3388617870991687680"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)

echo "Step 1: Export products to GCS..."
curl -X POST "https://cartiq-backend-xxx.run.app/api/internal/indexing/export" \
  -H "X-Internal-Api-Key: ${INTERNAL_API_KEY}" \
  -d "{\"outputUri\": \"gs://${BUCKET}/input/${TIMESTAMP}/products.jsonl\"}"

echo "Step 2: Submit batch embedding job..."
JOB_NAME=$(gcloud ai batch-prediction-jobs create \
  --model="publishers/google/models/text-embedding-004" \
  --input-path="gs://${BUCKET}/input/${TIMESTAMP}/products.jsonl" \
  --output-path="gs://${BUCKET}/embeddings/${TIMESTAMP}/" \
  --region=${REGION} \
  --format="value(name)")

echo "Step 3: Wait for job completion..."
while true; do
  STATE=$(gcloud ai batch-prediction-jobs describe ${JOB_NAME} \
    --region=${REGION} --format="value(state)")
  echo "Job state: ${STATE}"
  if [ "${STATE}" == "JOB_STATE_SUCCEEDED" ]; then break; fi
  if [ "${STATE}" == "JOB_STATE_FAILED" ]; then exit 1; fi
  sleep 30
done

echo "Step 4: Transform embeddings to Vector Search format..."
curl -X POST "https://cartiq-backend-xxx.run.app/api/internal/indexing/transform" \
  -H "X-Internal-Api-Key: ${INTERNAL_API_KEY}" \
  -d "{
    \"inputUri\": \"gs://${BUCKET}/embeddings/${TIMESTAMP}/\",
    \"outputUri\": \"gs://${BUCKET}/vectors/${TIMESTAMP}/\"
  }"

echo "Step 5: Update Vector Search index..."
gcloud ai indexes update ${INDEX_ID} \
  --metadata-file=<(echo "{
    \"contentsDeltaUri\": \"gs://${BUCKET}/vectors/${TIMESTAMP}/\",
    \"isCompleteOverwrite\": true
  }") \
  --region=${REGION}

echo "Done! Index update initiated. Check status with:"
echo "gcloud ai indexes describe ${INDEX_ID} --region=${REGION}"
```

### Option B: Automated GitHub Actions Workflow

```yaml
# .github/workflows/batch-index-products.yml
name: Batch Index Products

on:
  workflow_dispatch:
  schedule:
    - cron: '0 2 * * 0'  # Weekly on Sunday at 2 AM

env:
  PROJECT_ID: ${{ secrets.GCP_PROJECT_ID }}
  REGION: us-central1
  BUCKET: cartiq-indexing-data
  INDEX_ID: ${{ secrets.VECTOR_SEARCH_INDEX_ID }}

jobs:
  batch-index:
    runs-on: ubuntu-latest
    environment: prod

    steps:
      - name: Authenticate to Google Cloud
        uses: google-github-actions/auth@v2
        with:
          workload_identity_provider: ${{ secrets.WIF_PROVIDER }}
          service_account: ${{ secrets.WIF_SERVICE_ACCOUNT }}

      - name: Set up Cloud SDK
        uses: google-github-actions/setup-gcloud@v2

      - name: Export products to GCS
        run: |
          # Call backend API to export products
          curl -X POST "${{ env.SERVICE_URL }}/api/internal/indexing/batch/export" \
            -H "X-Internal-Api-Key: ${{ secrets.INTERNAL_API_KEY }}"

      - name: Submit batch embedding job
        id: batch-job
        run: |
          JOB_NAME=$(gcloud ai batch-prediction-jobs create \
            --model="publishers/google/models/text-embedding-004" \
            --input-path="gs://${{ env.BUCKET }}/input/products.jsonl" \
            --output-path="gs://${{ env.BUCKET }}/embeddings/" \
            --region=${{ env.REGION }} \
            --format="value(name)")
          echo "job_name=${JOB_NAME}" >> $GITHUB_OUTPUT

      - name: Wait for batch job
        run: |
          while true; do
            STATE=$(gcloud ai batch-prediction-jobs describe ${{ steps.batch-job.outputs.job_name }} \
              --region=${{ env.REGION }} --format="value(state)")
            echo "Job state: ${STATE}"
            if [ "${STATE}" == "JOB_STATE_SUCCEEDED" ]; then break; fi
            if [ "${STATE}" == "JOB_STATE_FAILED" ]; then exit 1; fi
            sleep 60
          done

      - name: Update Vector Search index
        run: |
          gcloud ai indexes update ${{ env.INDEX_ID }} \
            --metadata-file=<(echo '{"contentsDeltaUri":"gs://${{ env.BUCKET }}/vectors/","isCompleteOverwrite":true}') \
            --region=${{ env.REGION }}
```

## Configuration

### Required GCS Bucket Structure

```
gs://cartiq-indexing-data/
├── input/
│   └── products.jsonl          # Exported products for embedding
├── embeddings/
│   └── predictions-*.jsonl     # Batch prediction output
└── vectors/
    └── products.jsonl          # Vector Search format
```

### Required Permissions

The service account needs:
- `roles/storage.objectAdmin` on the indexing bucket
- `roles/aiplatform.user` for batch prediction jobs
- `roles/aiplatform.indexAdmin` for index updates

```bash
# Grant permissions
gcloud projects add-iam-policy-binding ${PROJECT_ID} \
  --member="serviceAccount:${SERVICE_ACCOUNT}" \
  --role="roles/storage.objectAdmin" \
  --condition="expression=resource.name.startsWith('projects/_/buckets/cartiq-indexing-data'),title=indexing-bucket"

gcloud projects add-iam-policy-binding ${PROJECT_ID} \
  --member="serviceAccount:${SERVICE_ACCOUNT}" \
  --role="roles/aiplatform.user"
```

## Comparison: Streaming vs Batch Indexing

| Aspect | Streaming (Current) | Batch (Recommended) |
|--------|---------------------|---------------------|
| Rate limits | 60 req/min | None |
| Time for 38K products | 10+ hours | ~15-30 minutes |
| Reliability | Low (instance termination) | High |
| Cost | Higher (online pricing) | Lower (batch pricing) |
| Real-time updates | Yes | No (scheduled) |
| Implementation complexity | Simple | Moderate |

## When to Use Each Approach

**Use Streaming Indexing:**
- Single product updates (product created/updated)
- Small catalogs (< 1000 products)
- Real-time requirements

**Use Batch Indexing:**
- Initial full catalog index
- Periodic full reindex (weekly/monthly)
- Large catalogs (1000+ products)
- Data migrations

## References

- [Vertex AI Batch Text Embeddings](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/embeddings/batch-prediction-genai-embeddings)
- [Vector Search Update Index](https://docs.cloud.google.com/vertex-ai/docs/vector-search/update-rebuild-index)
- [Vector Search Input Format](https://cloud.google.com/vertex-ai/docs/vector-search/setup/format-structure)
- [Vector Search Overview](https://docs.cloud.google.com/vertex-ai/docs/vector-search/overview)
