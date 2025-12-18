package com.cartiq.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a search result from Vector Search.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {
    /** Product ID (UUID as string) */
    private String productId;

    /** Cosine similarity score (0-1, higher is more similar) */
    private double similarityScore;

    /** Optional: distance metric from vector search */
    private Double distance;
}
