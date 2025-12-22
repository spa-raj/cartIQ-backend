package com.cartiq.ai.controller;

import com.cartiq.ai.dto.ChatRequest;
import com.cartiq.ai.dto.ChatResponse;
import com.cartiq.ai.service.GeminiService;
import com.cartiq.kafka.service.ChatContextService;
import com.cartiq.ai.service.GeminiService.ChatResult;
import com.cartiq.kafka.dto.LastSearchContext;
import com.cartiq.kafka.dto.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AI Shopping Assistant Controller.
 *
 * Provides chat endpoint with Gemini function calling support.
 * Gemini can query real products from the catalog based on user intent.
 *
 * Flow:
 * 1. Frontend sends chat message via POST /api/chat
 * 2. GeminiService processes with function calling
 * 3. If Gemini decides to search products, it calls ProductToolService
 * 4. Real products are returned along with AI response
 * 5. AI search events are published to Kafka
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class AIChatController {

    private final GeminiService geminiService;
    private final ChatContextService chatContextService;

    /**
     * Send a chat message to the AI assistant.
     *
     * The AI can use tools to search for real products based on user queries like:
     * - "Show me laptops under Rs.50000"
     * - "Compare iPhone 15 vs Samsung S24"
     * - "What headphones do you recommend for gym?"
     *
     * @param request Chat request containing message and optional context
     * @param sessionId Session ID for conversation continuity
     * @return AI response with real products when relevant
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {

        String finalSessionId = sessionId != null ? sessionId : UUID.randomUUID().toString();
        String userId = request.getUserId() != null ? request.getUserId() : userIdHeader;
        if (userId == null) {
            userId = "anonymous-" + UUID.randomUUID().toString().substring(0, 8);
        }

        log.info("Chat request: userId={}, sessionId={}, message={}",
                userId, finalSessionId, truncate(request.getMessage(), 100));

        // Build user context from request + Redis chat memory
        Map<String, Object> userContext = buildUserContext(request, userId);

        // Process with Gemini (includes function calling)
        ChatResult result = geminiService.chat(
                request.getMessage(),
                userId,
                finalSessionId,
                userContext
        );

        // Build response
        ChatResponse response = ChatResponse.builder()
                .sessionId(finalSessionId)
                .message(result.getMessage())
                .products(result.getProducts())
                .hasProducts(result.getProducts() != null && !result.getProducts().isEmpty())
                .processingTimeMs(result.getProcessingTimeMs())
                .build();

        log.info("Chat response: productsCount={}, processingTimeMs={}",
                response.getProducts() != null ? response.getProducts().size() : 0,
                response.getProcessingTimeMs());

        return ResponseEntity.ok(response);
    }

    /**
     * Legacy endpoint for backwards compatibility.
     * Redirects to the main chat endpoint.
     */
    @PostMapping("/message")
    public ResponseEntity<ChatResponse> sendMessage(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authToken) {

        return chat(request, sessionId, null);
    }

    /**
     * Health check endpoint for the AI service.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "CartIQ AI Chat");
        health.put("features", Map.of(
                "functionCalling", true,
                "productSearch", true,
                "productComparison", true
        ));
        return ResponseEntity.ok(health);
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> buildUserContext(ChatRequest request, String userId) {
        Map<String, Object> context = new HashMap<>();

        // Context from request
        if (request.getPricePreference() != null) {
            context.put("pricePreference", request.getPricePreference());
        }
        if (request.getPreferredCategories() != null && !request.getPreferredCategories().isEmpty()) {
            context.put("preferredCategories", String.join(", ", request.getPreferredCategories()));
        }
        if (request.getRecentlyViewedProductIds() != null && !request.getRecentlyViewedProductIds().isEmpty()) {
            context.put("recentlyViewed", true);
            context.put("recentlyViewedCount", request.getRecentlyViewedProductIds().size());
        }
        if (request.getRecentCategories() != null && !request.getRecentCategories().isEmpty()) {
            context.put("recentCategories", String.join(", ", request.getRecentCategories()));
        }
        if (request.getCartTotal() != null) {
            context.put("cartTotal", request.getCartTotal());
        }

        // Fetch chat memory context from Redis
        try {
            UserProfile profile = chatContextService.getUserProfile(userId);
            if (profile != null) {
                // Last viewed product context (for "accessories for this")
                if (profile.getLastViewedProductId() != null) {
                    context.put("lastViewedProductId", profile.getLastViewedProductId());
                    if (profile.getLastViewedProductName() != null) {
                        context.put("lastViewedProductName", profile.getLastViewedProductName());
                    }
                    if (profile.getLastViewedProductCategory() != null) {
                        context.put("lastViewedProductCategory", profile.getLastViewedProductCategory());
                    }
                }

                // Last search context (for "show me cheaper ones")
                LastSearchContext searchContext = profile.getLastSearchContext();
                if (searchContext != null) {
                    if (searchContext.getQuery() != null) {
                        context.put("lastSearchQuery", searchContext.getQuery());
                    }
                    if (searchContext.getCategory() != null) {
                        context.put("lastSearchCategory", searchContext.getCategory());
                    }
                    if (searchContext.getBrand() != null) {
                        context.put("lastSearchBrand", searchContext.getBrand());
                    }
                    if (searchContext.getMaxPrice() != null) {
                        context.put("lastSearchMaxPrice", searchContext.getMaxPrice());
                    }
                    if (searchContext.getMinPrice() != null) {
                        context.put("lastSearchMinPrice", searchContext.getMinPrice());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not fetch chat context for userId={}: {}", userId, e.getMessage());
        }

        return context;
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
    }
}
