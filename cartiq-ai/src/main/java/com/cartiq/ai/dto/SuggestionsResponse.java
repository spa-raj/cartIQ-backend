package com.cartiq.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for personalized product suggestions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestionsResponse {

    /** List of suggested products with recommendation context */
    private List<SuggestedProduct> products;

    /** Total number of products returned */
    private int totalCount;

    /** Whether the suggestions are personalized (user profile found) */
    private boolean personalized;

    /** Strategy name -> count of products from that strategy */
    private Map<String, String> strategies;

    /** User ID (null for anonymous users) */
    private String userId;

    /** Timestamp when user profile was last updated (null if not personalized) */
    private String lastUpdated;
}
