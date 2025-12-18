package com.cartiq.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for RAG (Retrieval-Augmented Generation).
 * Maps to cartiq.rag.* properties in application.properties.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "cartiq.rag")
public class RagConfig {

    private boolean enabled = true;

    private Embedding embedding = new Embedding();
    private VectorSearch vectorSearch = new VectorSearch();
    private Retrieval retrieval = new Retrieval();
    private Reranking reranking = new Reranking();
    private Cache cache = new Cache();
    private Indexing indexing = new Indexing();

    @Data
    public static class Embedding {
        /** Vertex AI embedding model (e.g., text-embedding-004) */
        private String model = "text-embedding-004";
        /** Embedding dimensions (768 for text-embedding-004) */
        private int dimensions = 768;
    }

    @Data
    public static class VectorSearch {
        /** Vertex AI Vector Search index endpoint (full resource name) */
        private String indexEndpoint;
        /** Deployed index ID within the endpoint (for querying) */
        private String deployedIndexId;
        /** API endpoint for Vector Search queries (public endpoint, e.g., 123456.us-central1-xxx.vdb.vertexai.goog:10000) */
        private String apiEndpoint;
        /** Index ID for upserting datapoints (different from deployed index ID) */
        private String indexId;
    }

    @Data
    public static class Retrieval {
        /** Number of initial candidates to fetch from vector search */
        private int initialCandidates = 50;
        /** Number of final results to return after filtering/reranking */
        private int finalResults = 10;
        /** Minimum similarity threshold (0-1) */
        private double similarityThreshold = 0.7;
    }

    @Data
    public static class Reranking {
        /** Whether to enable reranking of results */
        private boolean enabled = true;
        /** Reranking model */
        private String model = "semantic-ranker-512@latest";
    }

    @Data
    public static class Cache {
        /** TTL for product embeddings in Redis */
        private String productEmbeddingTtl = "24h";
        /** TTL for query embeddings in Redis */
        private String queryEmbeddingTtl = "1h";
    }

    @Data
    public static class Indexing {
        /** Batch size for indexing products */
        private int batchSize = 100;
        /** Whether to index products on application startup */
        private boolean onStartup = false;
    }
}
