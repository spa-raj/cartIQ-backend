package com.cartiq.rag.service;

import com.cartiq.rag.config.RagConfig;
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
 * Service for reranking search results using Vertex AI Ranking API.
 * Uses a Cross-Encoder model for higher precision scoring.
 *
 * This is Step A.6 in the RAG pipeline:
 * Vector Search (Top-50) → Re-Ranker (Top-10) → Gemini
 */
@Slf4j
@Service
public class RerankerService {

    private final RagConfig ragConfig;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String projectId;
    private final String location;
    private GoogleCredentials credentials;
    private boolean initialized = false;

    public RerankerService(
            RagConfig ragConfig,
            @Value("${vertex.ai.project-id:}") String projectId,
            @Value("${vertex.ai.location:us-central1}") String location) {
        this.ragConfig = ragConfig;
        this.projectId = projectId;
        this.location = location;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = new RestTemplate();

        initialize();
    }

    private void initialize() {
        if (projectId == null || projectId.isBlank()) {
            log.warn("Project ID not configured for reranking service");
            return;
        }

        try {
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped("https://www.googleapis.com/auth/cloud-platform");
            initialized = true;
            log.info("Initialized Reranker service: project={}, model={}",
                    projectId, ragConfig.getReranking().getModel());
        } catch (IOException e) {
            log.error("Failed to initialize Reranker service: {}", e.getMessage());
        }
    }

    /**
     * Rerank a list of documents based on relevance to the query.
     * Uses Vertex AI Ranking API (Cross-Encoder).
     *
     * @param query The user's search query
     * @param documents List of documents to rerank (product descriptions)
     * @param documentIds Corresponding IDs for each document
     * @param topN Number of top results to return
     * @return List of document IDs in reranked order (most relevant first)
     */
    public List<String> rerank(String query, List<String> documents, List<String> documentIds, int topN) {
        if (!isAvailable()) {
            log.warn("Reranker not available, returning original order");
            return documentIds.subList(0, Math.min(topN, documentIds.size()));
        }

        if (documents.isEmpty() || documents.size() != documentIds.size()) {
            return List.of();
        }

        try {
            // Build request for Vertex AI Ranking API
            // https://cloud.google.com/generative-ai-app-builder/docs/ranking
            String endpoint = String.format(
                    "https://discoveryengine.googleapis.com/v1/projects/%s/locations/%s/rankingConfigs/default_ranking_config:rank",
                    projectId, location);

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("query", query);
            requestBody.put("model", ragConfig.getReranking().getModel());
            requestBody.put("topN", topN);

            // Add records (documents to rank)
            ArrayNode records = objectMapper.createArrayNode();
            for (int i = 0; i < documents.size(); i++) {
                ObjectNode record = objectMapper.createObjectNode();
                record.put("id", documentIds.get(i));
                record.put("content", documents.get(i));
                records.add(record);
            }
            requestBody.set("records", records);

            // Get access token
            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();

            // Set headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(accessToken);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            // Execute request
            long startTime = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            long duration = System.currentTimeMillis() - startTime;

            // Parse response
            List<String> rerankedIds = parseRankingResponse(response.getBody());
            log.info("Reranked {} documents to top {} in {}ms", documents.size(), rerankedIds.size(), duration);

            return rerankedIds;

        } catch (Exception e) {
            log.error("Error during reranking: {}", e.getMessage(), e);
            // Fallback to original order
            return documentIds.subList(0, Math.min(topN, documentIds.size()));
        }
    }

    /**
     * Parse the ranking API response to extract reranked document IDs.
     */
    private List<String> parseRankingResponse(String responseBody) {
        List<String> rerankedIds = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode records = root.get("records");

            if (records != null && records.isArray()) {
                for (JsonNode record : records) {
                    String id = record.get("id").asText();
                    rerankedIds.add(id);
                }
            }
        } catch (Exception e) {
            log.error("Error parsing ranking response: {}", e.getMessage());
        }

        return rerankedIds;
    }

    /**
     * Check if reranking service is available and enabled.
     */
    public boolean isAvailable() {
        return initialized
                && ragConfig.isEnabled()
                && ragConfig.getReranking().isEnabled();
    }
}
