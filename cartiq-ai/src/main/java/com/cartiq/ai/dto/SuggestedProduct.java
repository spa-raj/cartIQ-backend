package com.cartiq.ai.dto;

import com.cartiq.product.dto.ProductDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A product suggestion with recommendation context.
 * Wraps ProductDTO with information about why it was recommended.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestedProduct {

    /** The product details */
    private ProductDTO product;

    /** Human-readable reason for recommendation */
    private String reason;

    /** Strategy that generated this recommendation */
    private String strategy;

    /** Relevance score (optional, for ranking) */
    private Double score;

    /**
     * Create a suggested product with a strategy-based reason.
     */
    public static SuggestedProduct fromStrategy(ProductDTO product, String strategy, String context) {
        String reason = generateReason(strategy, context);
        return SuggestedProduct.builder()
                .product(product)
                .strategy(strategy)
                .reason(reason)
                .build();
    }

    private static String generateReason(String strategy, String context) {
        return switch (strategy) {
            case "ai_intent" -> context != null && !context.isBlank()
                    ? "Based on your search for " + context
                    : "Matches your search preferences";
            case "similar_products" -> context != null && !context.isBlank()
                    ? "Similar to " + context
                    : "Similar to items you viewed";
            case "category_affinity" -> context != null && !context.isBlank()
                    ? "Popular in " + context
                    : "Based on your interests";
            case "trending" -> "Trending now";
            default -> "Recommended for you";
        };
    }
}
