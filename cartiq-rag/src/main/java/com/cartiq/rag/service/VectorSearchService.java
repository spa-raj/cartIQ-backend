package com.cartiq.rag.service;

import com.cartiq.rag.config.RagConfig;
import com.cartiq.rag.dto.SearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.*;

/**
 * Service for querying Vertex AI Vector Search using REST API.
 * Uses REST instead of gRPC because Cloud Run blocks gRPC port 10000.
 */
@Slf4j
@Service
public class VectorSearchService {

    private final RagConfig ragConfig;
    private final EmbeddingService embeddingService;
    private final QueryExpansionService queryExpansionService;
    private final String projectId;
    private final String location;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private GoogleCredentials credentials;
    private String restEndpointUrl;
    private boolean initialized = false;

    public VectorSearchService(
            RagConfig ragConfig,
            EmbeddingService embeddingService,
            QueryExpansionService queryExpansionService,
            @Value("${vertex.ai.project-id:}") String projectId,
            @Value("${vertex.ai.location:us-central1}") String location) {
        this.ragConfig = ragConfig;
        this.embeddingService = embeddingService;
        this.queryExpansionService = queryExpansionService;
        this.projectId = projectId;
        this.location = location;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = new RestTemplate();

        initializeClient();
    }

    private void initializeClient() {
        String apiEndpoint = ragConfig.getVectorSearch().getApiEndpoint();
        String indexEndpoint = ragConfig.getVectorSearch().getIndexEndpoint();

        if (apiEndpoint == null || apiEndpoint.isBlank()) {
            log.warn("Vector Search API endpoint not configured");
            return;
        }

        if (indexEndpoint == null || indexEndpoint.isBlank()) {
            log.warn("Vector Search index endpoint not configured");
            return;
        }

        try {
            // Get default credentials
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped("https://www.googleapis.com/auth/cloud-platform");

            // Extract hostname from endpoint (remove port if present)
            String hostname = apiEndpoint.contains(":")
                ? apiEndpoint.substring(0, apiEndpoint.indexOf(":"))
                : apiEndpoint;

            // Build REST endpoint URL
            // Format: https://{public-endpoint}/v1/{index-endpoint}:findNeighbors
            restEndpointUrl = String.format("https://%s/v1/%s:findNeighbors", hostname, indexEndpoint);

            initialized = true;
            log.info("Initialized Vector Search REST client: {}", restEndpointUrl);

        } catch (IOException e) {
            log.error("Failed to initialize Vector Search client: {}", e.getMessage());
        }
    }

    /**
     * Search for similar products using vector similarity.
     */
    public List<SearchResult> search(String query, int topK, Map<String, Object> filters) {
        if (!isAvailable()) {
            log.warn("Vector Search not available");
            return List.of();
        }

        try {
            List<Float> queryEmbedding = embeddingService.embedQuery(query);
            log.debug("Generated query embedding with {} dimensions for query: {}, first values: [{}, {}, {}]",
                    queryEmbedding.size(), query.length() > 50 ? query.substring(0, 50) + "..." : query,
                    queryEmbedding.size() > 0 ? queryEmbedding.get(0) : "N/A",
                    queryEmbedding.size() > 1 ? queryEmbedding.get(1) : "N/A",
                    queryEmbedding.size() > 2 ? queryEmbedding.get(2) : "N/A");
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
     * Search with query expansion for improved recall.
     * Generates multiple semantically similar queries using Gemini and merges results.
     *
     * @param query Original search query
     * @param topK Number of results to return
     * @param filters Optional filters (maxPrice, minPrice, minRating)
     * @param numVariations Number of query variations to generate (2-4)
     * @return Merged and deduplicated search results
     */
    public List<SearchResult> searchWithExpansion(String query, int topK, Map<String, Object> filters, int numVariations) {
        if (!isAvailable()) {
            log.warn("Vector Search not available");
            return List.of();
        }

        try {
            // Generate query variations
            List<String> queryVariations = queryExpansionService.expandQuery(query, numVariations);

            if (queryVariations.isEmpty()) {
                return List.of();
            }

            // If only original query (expansion failed/disabled), fall back to regular search
            if (queryVariations.size() == 1) {
                return search(query, topK, filters);
            }

            long startTime = System.currentTimeMillis();

            // Search with each variation and collect results
            Map<String, SearchResult> resultMap = new LinkedHashMap<>(); // Preserve order, dedupe by productId
            int perQueryLimit = Math.max(3, (int) Math.ceil((double) topK / queryVariations.size()) + 1);

            for (String variation : queryVariations) {
                List<SearchResult> variationResults = search(variation, perQueryLimit, filters);

                for (SearchResult result : variationResults) {
                    // Keep highest similarity score if duplicate
                    resultMap.merge(result.getProductId(), result, (existing, newer) ->
                            existing.getSimilarityScore() >= newer.getSimilarityScore() ? existing : newer);
                }

                // Stop early if we have enough results
                if (resultMap.size() >= topK * 2) {
                    break;
                }
            }

            // Sort by similarity score and limit
            List<SearchResult> mergedResults = resultMap.values().stream()
                    .sorted((a, b) -> Double.compare(b.getSimilarityScore(), a.getSimilarityScore()))
                    .limit(topK)
                    .toList();

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Expanded search: query='{}', variations={}, total_candidates={}, returned={}, time={}ms",
                    query, queryVariations.size(), resultMap.size(), mergedResults.size(), elapsed);

            return mergedResults;

        } catch (Exception e) {
            log.error("Error during expanded search: {}", e.getMessage(), e);
            // Fall back to regular search
            return search(query, topK, filters);
        }
    }

    /**
     * Search with query expansion using default 3 variations.
     */
    public List<SearchResult> searchWithExpansion(String query, int topK, Map<String, Object> filters) {
        return searchWithExpansion(query, topK, filters, 3);
    }

    /**
     * Search by embedding vector using REST API.
     * Accepts List of any Number type (Float or Double) to handle Redis cache deserialization.
     */
    public List<SearchResult> searchByVector(List<? extends Number> embedding, int topK, Map<String, Object> filters) {
        if (!isAvailable()) {
            return List.of();
        }

        String deployedIndexId = ragConfig.getVectorSearch().getDeployedIndexId();
        if (deployedIndexId == null) {
            log.warn("Deployed index ID not configured");
            return List.of();
        }

        try {
            // Build request body
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("deployed_index_id", deployedIndexId);

            // Build query
            ArrayNode queries = objectMapper.createArrayNode();
            ObjectNode query = objectMapper.createObjectNode();
            query.put("neighbor_count", topK);

            // Build datapoint
            ObjectNode datapoint = objectMapper.createObjectNode();
            datapoint.put("datapoint_id", "query-" + UUID.randomUUID());

            // Add feature vector - handle both Float and Double (Redis deserializes as Double)
            ArrayNode featureVector = objectMapper.createArrayNode();
            for (Number value : embedding) {
                featureVector.add(value.doubleValue());
            }
            datapoint.set("feature_vector", featureVector);

            // Add numeric restricts if provided
            if (filters != null && !filters.isEmpty()) {
                ArrayNode numericRestricts = objectMapper.createArrayNode();

                if (filters.containsKey("maxPrice")) {
                    ObjectNode restrict = objectMapper.createObjectNode();
                    restrict.put("namespace", "price");
                    restrict.put("value_double", ((Number) filters.get("maxPrice")).doubleValue());
                    restrict.put("op", "LESS_EQUAL");
                    numericRestricts.add(restrict);
                }
                if (filters.containsKey("minPrice")) {
                    ObjectNode restrict = objectMapper.createObjectNode();
                    restrict.put("namespace", "price");
                    restrict.put("value_double", ((Number) filters.get("minPrice")).doubleValue());
                    restrict.put("op", "GREATER_EQUAL");
                    numericRestricts.add(restrict);
                }
                if (filters.containsKey("minRating")) {
                    ObjectNode restrict = objectMapper.createObjectNode();
                    restrict.put("namespace", "rating");
                    restrict.put("value_double", ((Number) filters.get("minRating")).doubleValue());
                    restrict.put("op", "GREATER_EQUAL");
                    numericRestricts.add(restrict);
                }

                if (!numericRestricts.isEmpty()) {
                    datapoint.set("numeric_restricts", numericRestricts);
                }

                // Enable categorical restricts
                ArrayNode allowRestricts = objectMapper.createArrayNode();
                if (filters.containsKey("categoryId")) {
                    ObjectNode allow = objectMapper.createObjectNode();
                    allow.put("namespace", "category_id");
                    ArrayNode allowTokens = objectMapper.createArrayNode();
                    allowTokens.add((String) filters.get("categoryId"));
                    allow.set("allow", allowTokens);
                    allowRestricts.add(allow);
                }
                if (filters.containsKey("brand")) {
                    ObjectNode allow = objectMapper.createObjectNode();
                    allow.put("namespace", "brand");
                    ArrayNode allowTokens = objectMapper.createArrayNode();
                    allowTokens.add((String) filters.get("brand"));
                    allow.set("allow", allowTokens);
                    allowRestricts.add(allow);
                }
                if (!allowRestricts.isEmpty()) {
                    datapoint.set("restricts", allowRestricts);
                }
            }

            query.set("datapoint", datapoint);
            queries.add(query);
            requestBody.set("queries", queries);

            // Refresh credentials and get access token
            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();
            log.debug("Using access token (length={}, prefix={})",
                    accessToken != null ? accessToken.length() : "null",
                    accessToken != null && accessToken.length() > 10 ? accessToken.substring(0, 10) + "..." : "null");

            // Set headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(accessToken);

            String requestJson = objectMapper.writeValueAsString(requestBody);

            // Log request structure for debugging
            log.debug("Vector Search request to {}: deployed_index_id={}, embedding_size={}, first_3_values=[{},{},{}]",
                    restEndpointUrl, deployedIndexId, embedding.size(),
                    embedding.size() > 0 ? embedding.get(0) : "N/A",
                    embedding.size() > 1 ? embedding.get(1) : "N/A",
                    embedding.size() > 2 ? embedding.get(2) : "N/A");

            // Log JSON structure (first 300 chars to verify format)
            log.debug("Request JSON structure: {}", requestJson.substring(0, Math.min(300, requestJson.length())));

            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

            // Execute request
            long startTime = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(
                    restEndpointUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            long searchTime = System.currentTimeMillis() - startTime;

            String responseBody = response.getBody();
            log.debug("Vector search REST API completed in {}ms, status={}, responseLength={}",
                    searchTime, response.getStatusCode(), responseBody != null ? responseBody.length() : 0);

            // Parse response
            return parseResponse(responseBody, searchTime);

        } catch (Exception e) {
            log.error("Error executing vector search: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Parse the REST API response.
     */
    private List<SearchResult> parseResponse(String responseBody, long searchTime) {
        List<SearchResult> results = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode nearestNeighbors = root.get("nearestNeighbors");

            if (nearestNeighbors == null || !nearestNeighbors.isArray()) {
                log.warn("No nearestNeighbors in response. Raw response: {}",
                        responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody);
                return results;
            }

            int totalNeighbors = 0;
            double minDistance = Double.MAX_VALUE;
            double maxDistance = 0;

            for (JsonNode nn : nearestNeighbors) {
                JsonNode neighbors = nn.get("neighbors");
                if (neighbors == null || !neighbors.isArray()) continue;

                for (JsonNode neighbor : neighbors) {
                    totalNeighbors++;

                    JsonNode datapointNode = neighbor.get("datapoint");
                    if (datapointNode == null) continue;

                    String datapointId = datapointNode.get("datapointId").asText();
                    double distance = neighbor.get("distance").asDouble();

                    minDistance = Math.min(minDistance, distance);
                    maxDistance = Math.max(maxDistance, distance);

                    // Convert distance to similarity (1 - distance for cosine)
                    double similarity = 1.0 - distance;

                    if (similarity >= ragConfig.getRetrieval().getSimilarityThreshold()) {
                        results.add(SearchResult.builder()
                                .productId(datapointId)
                                .similarityScore(similarity)
                                .build());
                    }
                }
            }

            if (totalNeighbors > 0) {
                log.info("Vector search (REST): {} neighbors, distance [{}, {}], {} passed threshold (>={}) in {}ms",
                        totalNeighbors, String.format("%.4f", minDistance), String.format("%.4f", maxDistance),
                        results.size(), ragConfig.getRetrieval().getSimilarityThreshold(), searchTime);
            } else {
                log.warn("Vector search returned 0 neighbors");
            }

        } catch (Exception e) {
            log.error("Error parsing vector search response: {}", e.getMessage());
        }

        return results;
    }

    /**
     * Check if Vector Search is available.
     */
    public boolean isAvailable() {
        return ragConfig.isEnabled()
                && initialized
                && ragConfig.getVectorSearch().getIndexEndpoint() != null
                && ragConfig.getVectorSearch().getDeployedIndexId() != null;
    }

    /**
     * Close resources (no-op for REST client).
     */
    public void close() {
        // No resources to close for REST client
    }
}
