package com.cartiq.kafka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Stores the last search context for contextual follow-up queries.
 * Enables queries like "show me cheaper ones" or "anything above 50000?"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LastSearchContext implements Serializable {

    private String query;           // Original search query (e.g., "Samsung phones")
    private String category;        // Category searched (e.g., "Smartphones")
    private String brand;           // Brand if specified (e.g., "Samsung")
    private BigDecimal minPrice;    // Min price filter used
    private BigDecimal maxPrice;    // Max price filter used
    private BigDecimal minRating;   // Min rating filter used
    private Long timestamp;         // When the search was made (epoch ms)
}
