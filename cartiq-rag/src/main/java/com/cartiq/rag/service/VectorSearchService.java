package com.cartiq.rag.service;

import com.cartiq.rag.config.RagConfig;
import com.cartiq.rag.dto.SearchResult;
import com.google.cloud.aiplatform.v1.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for querying Vertex AI Vector Search.
 * Performs approximate nearest neighbor (ANN) search for semantic similarity.
 */
@Slf4j
@Service
public class VectorSearchService {

    private final RagConfig ragConfig;
    private final EmbeddingService embeddingService;
    private final String projectId;
    private final String location;
    private MatchServiceClient matchServiceClient;

    public VectorSearchService(
            RagConfig ragConfig,
            EmbeddingService embeddingService,
            @Value("${vertex.ai.project-id:}") String projectId,
            @Value("${vertex.ai.location:us-central1}") String location) {
        this.ragConfig = ragConfig;
        this.embeddingService = embeddingService;
        this.projectId = projectId;
        this.location = location;

        initializeClient();
    }

    private void initializeClient() {
        String apiEndpoint = ragConfig.getVectorSearch().getApiEndpoint();
        if (apiEndpoint == null || apiEndpoint.isBlank()) {
            log.warn("Vector Search API endpoint not configured. Set cartiq.rag.vectorsearch.api-endpoint");
            return;
        }

        try {
            MatchServiceSettings settings = MatchServiceSettings.newBuilder()
                    .setEndpoint(apiEndpoint)
                    .build();
            this.matchServiceClient = MatchServiceClient.create(settings);
            log.info("Initialized Vector Search client with endpoint: {}", apiEndpoint);
        } catch (IOException e) {
            log.error("Failed to initialize Vector Search client: {}", e.getMessage());
        }
    }

    /**
     * Search for similar products using vector similarity.
     *
     * @param query User search query
     * @param topK Number of results to return
     * @param filters Optional metadata filters (price, category, etc.)
     * @return List of search results with product IDs and scores
     */
    public List<SearchResult> search(String query, int topK, Map<String, Object> filters) {
        if (!isAvailable()) {
            log.warn("Vector Search not available");
            return List.of();
        }

        try {
            // Generate query embedding
            List<Float> queryEmbedding = embeddingService.embedQuery(query);
            if (queryEmbedding.isEmpty()) {
                log.warn("Failed to generate query embedding");
                return List.of();
            }

            return searchByVector(queryEmbedding, topK, filters);

        } catch (Exception e) {
            log.error("Error during vector search: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Search by embedding vector directly.
     *
     * @param embedding Query embedding vector
     * @param topK Number of results
     * @param filters Optional metadata filters
     * @return List of search results
     */
    public List<SearchResult> searchByVector(List<Float> embedding, int topK, Map<String, Object> filters) {
        if (!isAvailable()) {
            return List.of();
        }

        String indexEndpoint = ragConfig.getVectorSearch().getIndexEndpoint();
        String deployedIndexId = ragConfig.getVectorSearch().getDeployedIndexId();

        if (indexEndpoint == null || deployedIndexId == null) {
            log.warn("Vector Search index endpoint or deployed index ID not configured");
            return List.of();
        }

        try {
            // Build the query datapoint
            IndexDatapoint.Builder datapointBuilder = IndexDatapoint.newBuilder()
                    .setDatapointId("query-" + UUID.randomUUID());

            // Add feature vector
            for (Float value : embedding) {
                datapointBuilder.addFeatureVector(value);
            }

            // Add numeric filters if provided
            if (filters != null) {
                if (filters.containsKey("maxPrice")) {
                    Double maxPrice = (Double) filters.get("maxPrice");
                    datapointBuilder.addNumericRestricts(
                            IndexDatapoint.NumericRestriction.newBuilder()
                                    .setNamespace("price")
                                    .setValueDouble(maxPrice)
                                    .setOp(IndexDatapoint.NumericRestriction.Operator.LESS_EQUAL)
                                    .build()
                    );
                }
                if (filters.containsKey("minPrice")) {
                    Double minPrice = (Double) filters.get("minPrice");
                    datapointBuilder.addNumericRestricts(
                            IndexDatapoint.NumericRestriction.newBuilder()
                                    .setNamespace("price")
                                    .setValueDouble(minPrice)
                                    .setOp(IndexDatapoint.NumericRestriction.Operator.GREATER_EQUAL)
                                    .build()
                    );
                }
                if (filters.containsKey("minRating")) {
                    Double minRating = (Double) filters.get("minRating");
                    datapointBuilder.addNumericRestricts(
                            IndexDatapoint.NumericRestriction.newBuilder()
                                    .setNamespace("rating")
                                    .setValueDouble(minRating)
                                    .setOp(IndexDatapoint.NumericRestriction.Operator.GREATER_EQUAL)
                                    .build()
                    );
                }

                // Add categorical filters
                if (filters.containsKey("categoryId")) {
                    String categoryId = (String) filters.get("categoryId");
                    datapointBuilder.addRestricts(
                            IndexDatapoint.Restriction.newBuilder()
                                    .setNamespace("category_id")
                                    .addAllowList(categoryId)
                                    .build()
                    );
                }
                if (filters.containsKey("brand")) {
                    String brand = (String) filters.get("brand");
                    datapointBuilder.addRestricts(
                            IndexDatapoint.Restriction.newBuilder()
                                    .setNamespace("brand")
                                    .addAllowList(brand)
                                    .build()
                    );
                }
            }

            // Build the query
            FindNeighborsRequest.Query neighborQuery = FindNeighborsRequest.Query.newBuilder()
                    .setDatapoint(datapointBuilder.build())
                    .setNeighborCount(topK)
                    .build();

            // Build the request
            FindNeighborsRequest request = FindNeighborsRequest.newBuilder()
                    .setIndexEndpoint(indexEndpoint)
                    .setDeployedIndexId(deployedIndexId)
                    .addQueries(neighborQuery)
                    .build();

            // Execute search
            long startTime = System.currentTimeMillis();
            FindNeighborsResponse response = matchServiceClient.findNeighbors(request);
            long searchTime = System.currentTimeMillis() - startTime;

            log.debug("Vector search completed in {}ms", searchTime);

            // Parse results
            List<SearchResult> results = new ArrayList<>();
            int totalNeighbors = 0;
            double minDistance = Double.MAX_VALUE;
            double maxDistance = 0;

            for (FindNeighborsResponse.NearestNeighbors neighbors : response.getNearestNeighborsList()) {
                log.debug("Received {} neighbors from Vector Search", neighbors.getNeighborsCount());
                for (FindNeighborsResponse.Neighbor neighbor : neighbors.getNeighborsList()) {
                    totalNeighbors++;
                    String datapointId = neighbor.getDatapoint().getDatapointId();
                    double distance = neighbor.getDistance();

                    minDistance = Math.min(minDistance, distance);
                    maxDistance = Math.max(maxDistance, distance);

                    // Convert distance to similarity score (1 - distance for cosine)
                    double similarity = 1.0 - distance;

                    // Filter by similarity threshold
                    if (similarity >= ragConfig.getRetrieval().getSimilarityThreshold()) {
                        results.add(SearchResult.builder()
                                .productId(datapointId)
                                .similarityScore(similarity)
                                .build());
                    }
                }
            }

            if (totalNeighbors > 0) {
                log.info("Vector search: {} neighbors returned, distance range [{}, {}], {} passed threshold (>={})",
                        totalNeighbors, String.format("%.4f", minDistance), String.format("%.4f", maxDistance),
                        results.size(), ragConfig.getRetrieval().getSimilarityThreshold());
            } else {
                log.warn("Vector search returned 0 neighbors from index");
            }

            return results;

        } catch (Exception e) {
            log.error("Error executing vector search: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Check if Vector Search is available and configured.
     */
    public boolean isAvailable() {
        return ragConfig.isEnabled()
                && matchServiceClient != null
                && ragConfig.getVectorSearch().getIndexEndpoint() != null
                && ragConfig.getVectorSearch().getDeployedIndexId() != null;
    }

    /**
     * Close the client when the service is destroyed.
     */
    public void close() {
        if (matchServiceClient != null) {
            matchServiceClient.close();
        }
    }
}
