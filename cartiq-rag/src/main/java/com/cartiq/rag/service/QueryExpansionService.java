package com.cartiq.rag.service;

import com.cartiq.rag.config.RagConfig;
import com.google.genai.Client;
import com.google.genai.types.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

/**
 * Service for expanding search queries using Gemini.
 * Generates semantically similar query variations to improve vector search recall.
 */
@Slf4j
@Service
public class QueryExpansionService {

    private final Client client;
    private final String modelName;
    private final RagConfig ragConfig;

    private static final int DEFAULT_VARIATIONS = 3;
    private static final Pattern QUERY_PATTERN = Pattern.compile("\\d+\\.\\s*[\"']?([^\"'\\n]+)[\"']?");

    public QueryExpansionService(
            RagConfig ragConfig,
            @Value("${vertex.ai.project-id:}") String projectId,
            @Value("${vertex.ai.location:us-central1}") String location,
            @Value("${vertex.ai.model:gemini-2.0-flash}") String modelName) {
        this.ragConfig = ragConfig;
        this.modelName = modelName;

        Client tempClient = null;
        if (projectId != null && !projectId.isBlank()) {
            try {
                tempClient = Client.builder()
                        .project(projectId)
                        .location(location)
                        .vertexAI(true)
                        .build();
                log.info("Initialized QueryExpansionService with Gemini: model={}", modelName);
            } catch (Exception e) {
                log.error("Failed to initialize QueryExpansionService: {}", e.getMessage());
            }
        } else {
            log.warn("QueryExpansionService not configured - projectId is empty");
        }
        this.client = tempClient;
    }

    /**
     * Expand a search query into multiple semantically similar variations.
     *
     * @param originalQuery The original search query
     * @param numVariations Number of variations to generate (2-4 recommended)
     * @return List of query variations including the original
     */
    public List<String> expandQuery(String originalQuery, int numVariations) {
        if (!isAvailable()) {
            log.debug("Query expansion not available, returning original query");
            return List.of(originalQuery);
        }

        if (originalQuery == null || originalQuery.isBlank()) {
            return List.of();
        }

        // Cap variations to reasonable range
        numVariations = Math.max(2, Math.min(4, numVariations));

        try {
            String prompt = buildExpansionPrompt(originalQuery, numVariations);

            List<Content> contents = List.of(
                Content.builder()
                    .role("user")
                    .parts(List.of(Part.builder().text(prompt).build()))
                    .build()
            );

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .temperature(0.7f)  // Some creativity for variations
                    .maxOutputTokens(200)
                    .build();

            long startTime = System.currentTimeMillis();
            GenerateContentResponse response = client.models.generateContent(
                    modelName,
                    contents,
                    config
            );

            List<String> variations = parseVariations(response, originalQuery);
            long elapsed = System.currentTimeMillis() - startTime;

            log.debug("Query expansion: '{}' -> {} variations in {}ms: {}",
                    originalQuery, variations.size(), elapsed, variations);

            return variations;

        } catch (Exception e) {
            log.warn("Query expansion failed for '{}': {}", originalQuery, e.getMessage());
            return List.of(originalQuery);
        }
    }

    /**
     * Expand query with default number of variations (3).
     */
    public List<String> expandQuery(String originalQuery) {
        return expandQuery(originalQuery, DEFAULT_VARIATIONS);
    }

    /**
     * Build the prompt for query expansion.
     */
    private String buildExpansionPrompt(String query, int numVariations) {
        return String.format("""
            Generate %d alternative search queries for an e-commerce product search.

            Original query: "%s"

            Requirements:
            - Each variation should have the same shopping intent
            - Use different words, synonyms, or phrasings
            - Keep queries concise (2-5 words)
            - Focus on product discovery
            - Include the original query as #1

            Output format (numbered list only, no explanations):
            1. [original query]
            2. [variation]
            3. [variation]
            ...
            """, numVariations + 1, query);
    }

    /**
     * Parse Gemini response to extract query variations.
     */
    private List<String> parseVariations(GenerateContentResponse response, String originalQuery) {
        Set<String> variations = new LinkedHashSet<>();
        variations.add(originalQuery.trim()); // Always include original first

        try {
            Optional<List<Candidate>> candidatesOpt = response.candidates();
            if (candidatesOpt.isEmpty() || candidatesOpt.get().isEmpty()) {
                return new ArrayList<>(variations);
            }

            Candidate candidate = candidatesOpt.get().get(0);
            Optional<Content> contentOpt = candidate.content();
            if (contentOpt.isEmpty()) {
                return new ArrayList<>(variations);
            }

            Content content = contentOpt.get();
            Optional<List<Part>> partsOpt = content.parts();
            if (partsOpt.isEmpty()) {
                return new ArrayList<>(variations);
            }

            for (Part part : partsOpt.get()) {
                Optional<String> textOpt = part.text();
                if (textOpt.isPresent()) {
                    String text = textOpt.get();

                    // Parse numbered list format
                    Matcher matcher = QUERY_PATTERN.matcher(text);
                    while (matcher.find()) {
                        String variation = matcher.group(1).trim();
                        // Clean up any remaining quotes or artifacts
                        variation = variation.replaceAll("[\"']", "").trim();
                        if (!variation.isBlank() && variation.length() < 100) {
                            variations.add(variation);
                        }
                    }

                    // Fallback: split by newlines if pattern didn't match
                    if (variations.size() == 1) {
                        for (String line : text.split("\n")) {
                            line = line.replaceAll("^\\d+\\.\\s*", "").trim();
                            line = line.replaceAll("[\"']", "").trim();
                            if (!line.isBlank() && line.length() < 100 && !line.contains(":")) {
                                variations.add(line);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error parsing query variations: {}", e.getMessage());
        }

        return new ArrayList<>(variations);
    }

    /**
     * Check if query expansion is available.
     */
    public boolean isAvailable() {
        return client != null && ragConfig.isEnabled();
    }
}
