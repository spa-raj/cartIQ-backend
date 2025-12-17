package com.cartiq.ai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * AI Shopping Assistant Controller.
 *
 * Flow:
 * 1. Frontend sends chat message via POST /api/chat
 * 2. UserContext Cache provides browsing context
 * 3. Vertex AI (Gemini) generates personalized response
 * 4. Response returned to frontend
 *
 * Note: Chat events are not published to Kafka (only user behavior events are).
 * The AI uses cached user profiles from Flink-aggregated data.
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class AIChatController {

    private final RestTemplate restTemplate;

    @Value("${vertex.ai.endpoint:}")
    private String vertexAiEndpoint;

    @Value("${vertex.ai.project-id:}")
    private String projectId;

    @Value("${vertex.ai.location:us-central1}")
    private String location;

    /**
     * Send a chat message to the AI assistant.
     * The message is enriched with user context and sent to Vertex AI.
     */
    @PostMapping("/message")
    public ResponseEntity<ChatResponse> sendMessage(
            @RequestBody ChatMessageRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authToken) {

        String finalSessionId = sessionId != null ? sessionId : UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        log.debug("Chat request from user={}, message={}", request.getUserId(), request.getMessage());

        // Build user context for AI prompt
        UserContext context = UserContext.builder()
                .pricePreference(request.getPricePreference())
                .preferredCategories(request.getPreferredCategories())
                .purchaseHistory(request.getPurchaseHistory())
                .currentPage(request.getCurrentPage())
                .sessionDurationMinutes(request.getSessionDurationMinutes())
                .build();

        // Call Vertex AI for response
        String aiResponse = callVertexAI(request.getMessage(), context, request);

        // Build recommendations (in production, this comes from your product DB)
        List<ProductRecommendation> recommendations = generateRecommendations(request);

        long processingTime = System.currentTimeMillis() - startTime;
        log.debug("Chat response generated in {}ms", processingTime);

        // Return response to frontend
        return ResponseEntity.ok(ChatResponse.builder()
                .sessionId(finalSessionId)
                .message(aiResponse)
                .recommendations(recommendations)
                .processingTimeMs(processingTime)
                .build());
    }

    /**
     * Call Vertex AI (Gemini) for chat response.
     * In production, use the official Google Cloud client library.
     */
    private String callVertexAI(String userMessage, UserContext context, ChatMessageRequest request) {
        // Build the prompt with context
        String prompt = buildPrompt(userMessage, context, request);

        // If Vertex AI is not configured, return a mock response
        if (vertexAiEndpoint == null || vertexAiEndpoint.isEmpty()) {
            log.warn("Vertex AI not configured, returning mock response");
            return generateMockResponse(userMessage, request);
        }

        try {
            // Vertex AI REST API call
            String url = String.format(
                    "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/gemini-1.5-flash:generateContent",
                    location, projectId, location);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(Map.of(
                            "role", "user",
                            "parts", List.of(Map.of("text", prompt))
                    )),
                    "generationConfig", Map.of(
                            "temperature", 0.7,
                            "maxOutputTokens", 1024
                    )
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            // Parse response
            if (response.getBody() != null) {
                // Extract text from Gemini response structure
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
                if (candidates != null && !candidates.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                    if (parts != null && !parts.isEmpty()) {
                        return (String) parts.get(0).get("text");
                    }
                }
            }

            return generateMockResponse(userMessage, request);

        } catch (Exception e) {
            log.error("Error calling Vertex AI: {}", e.getMessage());
            return generateMockResponse(userMessage, request);
        }
    }

    private String buildPrompt(String userMessage, UserContext context, ChatMessageRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a helpful shopping assistant for CartIQ, an Indian e-commerce platform. ");
        prompt.append("Be friendly, concise, and helpful. Recommend products when relevant.\n\n");

        prompt.append("USER CONTEXT:\n");
        if (context.getPricePreference() != null) {
            prompt.append("- Price preference: ").append(context.getPricePreference()).append("\n");
        }
        if (context.getPreferredCategories() != null && !context.getPreferredCategories().isEmpty()) {
            prompt.append("- Interested in: ").append(String.join(", ", context.getPreferredCategories())).append("\n");
        }
        if (request.getRecentlyViewedProductIds() != null && !request.getRecentlyViewedProductIds().isEmpty()) {
            prompt.append("- Recently viewed ").append(request.getRecentlyViewedProductIds().size()).append(" products\n");
        }
        if (request.getCartTotal() != null) {
            prompt.append("- Current cart total: ₹").append(request.getCartTotal()).append("\n");
        }

        prompt.append("\nUSER MESSAGE: ").append(userMessage);
        prompt.append("\n\nProvide a helpful response. If recommending products, explain why they match the user's needs.");

        return prompt.toString();
    }

    private String generateMockResponse(String userMessage, ChatMessageRequest request) {
        String lowerMsg = userMessage.toLowerCase();

        if (lowerMsg.contains("laptop") || lowerMsg.contains("programming")) {
            return "Based on your interest in programming, I'd recommend looking at laptops with at least 16GB RAM and an SSD. " +
                    "The Dell XPS 15 and MacBook Pro are excellent choices for developers. " +
                    "Would you like me to show you options in a specific price range?";
        } else if (lowerMsg.contains("headphone") || lowerMsg.contains("audio")) {
            return "For headphones, it depends on your use case! For work calls, I'd suggest the Sony WH-1000XM5 for their excellent noise cancellation. " +
                    "For music, the Sennheiser HD 660S offers amazing sound quality. What's your budget?";
        } else if (lowerMsg.contains("gift")) {
            return "Gift shopping! I can help with that. Could you tell me a bit more about who the gift is for and their interests? " +
                    "That way I can suggest something they'll really love.";
        } else if (lowerMsg.contains("recommend") || lowerMsg.contains("suggest")) {
            return "I'd be happy to help! Based on your browsing history, you seem interested in electronics. " +
                    "Would you like recommendations for any specific category, or should I show you today's best deals?";
        } else {
            return "Thanks for your question! I'm here to help you find the perfect products. " +
                    "You can ask me about specific products, request recommendations, or get help comparing items. " +
                    "What are you looking for today?";
        }
    }

    private List<ProductRecommendation> generateRecommendations(ChatMessageRequest request) {
        // In production, query your product database based on context
        // For now, return mock recommendations
        return List.of(
                ProductRecommendation.builder()
                        .productId("prod-001")
                        .productName("Sony WH-1000XM5 Headphones")
                        .price(new java.math.BigDecimal("29990"))
                        .category("Electronics")
                        .relevanceScore(0.95)
                        .reason("Best-in-class noise cancellation, highly rated")
                        .build(),
                ProductRecommendation.builder()
                        .productId("prod-002")
                        .productName("Dell XPS 15 Laptop")
                        .price(new java.math.BigDecimal("149990"))
                        .category("Electronics")
                        .relevanceScore(0.88)
                        .reason("Popular choice for developers")
                        .build()
        );
    }

    // ==================== DTOs ====================

    @lombok.Data
    public static class ChatMessageRequest {
        private String userId;
        private String message;
        private List<String> recentlyViewedProductIds;
        private List<String> recentCategories;
        private List<String> cartProductIds;
        private BigDecimal cartTotal;
        private String pricePreference;
        private List<String> preferredCategories;
        private List<String> purchaseHistory;
        private String currentPage;
        private Integer sessionDurationMinutes;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ChatResponse {
        private String sessionId;
        private String message;
        private List<ProductRecommendation> recommendations;
        private Long processingTimeMs;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UserContext {
        private String pricePreference;
        private List<String> preferredCategories;
        private List<String> purchaseHistory;
        private String currentPage;
        private Integer sessionDurationMinutes;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProductRecommendation {
        private String productId;
        private String productName;
        private BigDecimal price;
        private String category;
        private Double relevanceScore;
        private String reason;
    }
}
