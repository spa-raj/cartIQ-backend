package com.cartiq.ai.dto;

import com.cartiq.product.dto.ProductDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for chat endpoint.
 * Contains both AI message and real products from the catalog.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /**
     * Session ID for conversation continuity.
     */
    private String sessionId;

    /**
     * AI-generated response message.
     */
    private String message;

    /**
     * Real products from the catalog.
     * These are actual ProductDTOs with full details for frontend to render.
     */
    private List<ProductDTO> products;

    /**
     * Whether products were found for this query.
     */
    private Boolean hasProducts;

    /**
     * Processing time in milliseconds.
     */
    private Long processingTimeMs;
}
