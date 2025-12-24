package com.cartiq.ai.controller;

import com.cartiq.ai.dto.SuggestionsResponse;
import com.cartiq.ai.service.SuggestionsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for personalized product suggestions.
 *
 * Provides recommendations based on:
 * - AI chat intent (categories, budget from conversations)
 * - Similar products (vector similarity to recently viewed)
 * - Category affinity (top-rated in browsed categories)
 * - Trending products (fallback for anonymous/new users)
 */
@RestController
@RequestMapping("/api/suggestions")
@RequiredArgsConstructor
@Slf4j
public class SuggestionsController {

    private final SuggestionsService suggestionsService;

    /**
     * Get personalized product suggestions for the current user.
     *
     * @param userId User ID from header (optional - if absent, returns trending products)
     * @param limit Maximum number of suggestions (default: 12, max: 50)
     * @return Personalized product suggestions with metadata
     */
    @GetMapping
    public ResponseEntity<SuggestionsResponse> getSuggestions(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(defaultValue = "12") int limit) {

        long startTime = System.currentTimeMillis();
        log.info("GET /api/suggestions - userId={}, limit={}", userId, limit);

        // Clamp limit to reasonable bounds
        limit = Math.max(1, Math.min(limit, 50));

        SuggestionsResponse response = suggestionsService.getSuggestions(userId, limit);

        long processingTimeMs = System.currentTimeMillis() - startTime;
        response.setProcessingTimeMs(processingTimeMs);

        log.info("Returning {} suggestions for userId={}, personalized={}, processingTimeMs={}",
                response.getTotalCount(), userId, response.isPersonalized(), processingTimeMs);

        return ResponseEntity.ok(response);
    }
}
