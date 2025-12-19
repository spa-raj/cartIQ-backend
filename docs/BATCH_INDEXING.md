# Batch Indexing for Vector Search

This document describes the batch indexing pipeline for indexing products into Vertex AI Vector Search. This approach handles large datasets (35,000+ products) without rate limiting issues.

## Overview

The batch indexing pipeline runs as a **GitHub Actions workflow** that orchestrates a 4-step process to embed and index all products into Vector Search.

### Pipeline Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  1. Export      │     │  2. Batch        │     │  3. Transform   │     │  4. Update      │
│  Products to    │ ──► │  Embedding Job   │ ──► │  to Vector      │ ──► │  Vector Search  │
│  GCS (JSONL)    │     │  (Vertex AI)     │     │  Search Format  │     │  Index          │
└─────────────────┘     └──────────────────┘     └─────────────────┘     └─────────────────┘
        │                       │                        │                       │
        ▼                       ▼                        ▼                       ▼
   metadata.jsonl          ~15-20 min              products.json           ~20-30 min
   products.jsonl          (batch API)             (datapoints)            (async LRO)
```

### Key Benefits

| Aspect | Streaming (Old) | Batch (Current) |
|--------|-----------------|-----------------|
| Rate limits | 60 req/min | None |
| Time for 35K products | 10+ hours | ~45 minutes |
| Reliability | Low (Cloud Run timeout) | High (async polling) |
| Cost | Higher (online pricing) | Lower (batch pricing) |

## Running the Pipeline

### Automatic (Scheduled)

The pipeline runs automatically **every Sunday at 2 AM UTC** via cron schedule.

### Manual Trigger

1. Go to **GitHub Actions** → **"Batch Index Products to Vector Search"**
2. Click **"Run workflow"**
3. Choose:
   - `complete_overwrite: true` - Replace all vectors (default)
   - `complete_overwrite: false` - Delta update (merge with existing)

## Pipeline Steps

### Step 1: Export Products to GCS

**Endpoint:** `POST /api/internal/indexing/batch/export`

Exports all products from PostgreSQL to GCS in two files:

```
gs://cartiq-indexing-data/input/{timestamp}/
├── products.jsonl   # Embedding input ({"content": "product text"})
└── metadata.jsonl   # Product metadata for filtering
```

**Input Format (products.jsonl):**
```json
{"content": "Apple iPhone 15 Pro Max - 256GB, Natural Titanium. Flagship smartphone with A17 Pro chip, 48MP camera system, and titanium design. Brand: Apple. Category: Electronics. Price: 159900.00 INR. Rating: 4.8"}
```

**Metadata Format (metadata.jsonl):**
```json
{"id": "uuid-123", "categoryId": "cat-456", "brand": "Apple", "price": 159900.00, "rating": 4.8}
```

### Step 2: Batch Embedding Job

**Endpoint:** `POST /api/internal/indexing/batch/embed`

Submits a Vertex AI batch prediction job using `text-embedding-004` model.

- **Model:** `publishers/google/models/text-embedding-004`
- **Dimensions:** 768
- **Processing time:** ~15-20 minutes for 35K products

**Output:** Embedding predictions in GCS:
```
gs://cartiq-indexing-data/embeddings/{timestamp}/
└── prediction-results-*.jsonl
```

### Step 3: Transform to Vector Search Format

**Endpoint:** `POST /api/internal/indexing/batch/transform`

Transforms batch embeddings + metadata into Vector Search datapoint format.

**Output Format (products.json):**
```json
{
  "id": "uuid-123",
  "embedding": [0.0123, -0.0456, ...],
  "restricts": [
    {"namespace": "category_id", "allow": ["cat-456"]},
    {"namespace": "brand", "allow": ["apple"]}
  ],
  "numeric_restricts": [
    {"namespace": "price", "value_double": 159900.00},
    {"namespace": "rating", "value_double": 4.8}
  ]
}
```

### Step 4: Update Vector Search Index

**Endpoint:** `POST /api/internal/indexing/batch/update-index`

Triggers async index update from GCS. This is a long-running operation (LRO) that takes ~20-30 minutes.

The workflow polls for completion using:
`GET /api/internal/indexing/batch/update-index/status`

## GCS Bucket Structure

```
gs://cartiq-indexing-data/
├── input/
│   └── {timestamp}/
│       ├── products.jsonl      # Embedding input
│       └── metadata.jsonl      # Product metadata
├── embeddings/
│   └── {timestamp}/
│       └── prediction-*.jsonl  # Batch prediction output
└── vectors/
    └── {timestamp}/
        └── products.json       # Vector Search format
```

## Configuration

### Application Properties

```yaml
cartiq:
  rag:
    enabled: true
    vectorsearch:
      index-id: "3388617870991687680"
      index-endpoint: "projects/.../indexEndpoints/..."
      deployed-index-id: "cartiq_products_public"
      api-endpoint: "1735428295.us-central1-886147182338.vdb.vertexai.goog:443"
    batch-indexing:
      gcs-bucket: "cartiq-indexing-data"
      input-prefix: "input"
      embeddings-prefix: "embeddings"
      vectors-prefix: "vectors"
      embedding-model: "text-embedding-004"
      complete-overwrite: true
```

### GitHub Secrets Required

| Secret | Description |
|--------|-------------|
| `GCP_PROJECT_ID` | Google Cloud project ID |
| `WIF_PROVIDER` | Workload Identity Federation provider |
| `WIF_SERVICE_ACCOUNT` | Service account for WIF |
| `INTERNAL_API_KEY` | API key for internal endpoints |

## Services

### BatchExportService
Exports products from PostgreSQL to GCS JSONL files.

### BatchEmbeddingService
Submits and monitors Vertex AI batch prediction jobs.

### EmbeddingTransformService
Transforms embeddings to Vector Search datapoint format.
- Uses **streaming output** to avoid OOM with large datasets
- Writes directly to GCS via `WritableByteChannel`

### BatchIndexUpdateService
Updates Vector Search index from GCS.
- Uses **async LRO** to avoid Cloud Run timeout
- Provides operation status polling

## How RAG Works with Chat

The chat API uses a **hybrid search** approach:

```
User Query
    │
    ▼
┌─────────────────┐
│ Gemini decides  │
│ to call tool    │ ──► Tool Calling (Function Calling)
└─────────────────┘
    │
    ▼
┌─────────────────┐
│ searchProducts  │
│ tool executes   │
└─────────────────┘
    │
    ├─► VectorSearchService.search()  ──► Semantic similarity (RAG)
    │         │
    │         ▼
    │   ┌─────────────┐
    │   │ Vertex AI   │
    │   │ Vector      │
    │   │ Search      │
    │   └─────────────┘
    │
    └─► Fallback: PostgreSQL FTS  ──► Keyword search (if Vector Search fails)
```

## Troubleshooting

### Check Pipeline Status
```bash
# List recent batch operations
curl -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  "https://us-central1-aiplatform.googleapis.com/v1/projects/{PROJECT_NUMBER}/locations/us-central1/indexes/{INDEX_ID}/operations"
```

### Check Index Vector Count
```bash
gcloud ai indexes describe {INDEX_ID} --region=us-central1 --project={PROJECT_ID}
```

### Test Vector Search
```bash
curl -X POST "https://{DOMAIN}/v1/projects/{PROJECT}/locations/us-central1/indexEndpoints/{ENDPOINT}:findNeighbors" \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H "Content-Type: application/json" \
  -d '{"deployed_index_id": "cartiq_products_public", "queries": [...]}'
```

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| Quota exceeded | Concurrent index update | Wait for previous operation to complete |
| OOM in transform | Large dataset | Fixed: uses streaming output |
| Timeout in update | Long-running operation | Fixed: uses async polling |
| Unknown file format | Wrong file extension | Fixed: uses `.json` not `.jsonl` |

## References

- [Vertex AI Batch Text Embeddings](https://cloud.google.com/vertex-ai/generative-ai/docs/embeddings/batch-prediction-genai-embeddings)
- [Vector Search Update Index](https://cloud.google.com/vertex-ai/docs/vector-search/update-rebuild-index)
- [Vector Search Data Format](https://cloud.google.com/vertex-ai/docs/vector-search/setup/format-structure)
