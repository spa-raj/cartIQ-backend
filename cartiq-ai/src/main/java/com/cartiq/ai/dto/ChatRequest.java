package com.cartiq.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO for chat endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /**
     * The user's chat message.
     */
    private String message;

    /**
     * User ID (optional, can come from auth token).
     */
    private String userId;

    /**
     * Recently viewed product IDs for context.
     */
    private List<String> recentlyViewedProductIds;

    /**
     * Recent categories browsed.
     */
    private List<String> recentCategories;

    /**
     * Products currently in cart.
     */
    private List<String> cartProductIds;

    /**
     * Current cart total.
     */
    private BigDecimal cartTotal;

    /**
     * User's price preference (budget, mid-range, premium).
     */
    private String pricePreference;

    /**
     * User's preferred categories.
     */
    private List<String> preferredCategories;
}
