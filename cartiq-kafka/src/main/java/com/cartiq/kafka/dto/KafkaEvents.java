package com.cartiq.kafka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Event DTOs for Kafka streaming with Avro serialization.
 * Uses Avro-compatible types: String, Double, Long, Integer, Boolean, List.
 * Enums are serialized as Strings. Timestamps are ISO-8601 strings.
 *
 * Topics (matching Confluent Cloud):
 * - user-events: UserEvent
 * - product-views: ProductViewEvent
 * - cart-events: CartEvent
 * - order-events: OrderEvent
 * - ai-events: AISearchEvent
 *
 * Note: user-profiles topic output is consumed via UserProfile.java (not here)
 */
public class KafkaEvents {

    // ==================== USER EVENTS (user-events topic) ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserEvent {
        private String eventId;
        private String userId;
        private String sessionId;
        private String eventType;       // enum as string for Avro
        private String pageType;        // enum as string for Avro
        private String pageUrl;
        private String deviceType;      // enum as string for Avro
        private String referrer;
        private String timestamp;       // ISO-8601 format for Avro
    }

    // ==================== PRODUCT VIEW EVENTS (product-views topic) ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductViewEvent {
        private String eventId;
        private String userId;
        private String sessionId;
        private String productId;
        private String productName;
        private String category;
        private Double price;
        private String source;          // enum as string for Avro
        private String searchQuery;
        private String timestamp;       // ISO-8601 format for Avro
        private Long viewDurationMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartEvent {
        private String eventId;
        private String userId;
        private String sessionId;
        private String action;          // enum as string for Avro
        private String productId;
        private String productName;
        private String category;
        private Integer quantity;
        private Double price;
        private Double cartTotal;
        private Integer cartItemCount;
        private String timestamp;       // ISO-8601 format for Avro
    }

    // ==================== TRANSACTION EVENTS ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderEvent {
        private String eventId;
        private String userId;
        private String orderId;
        private List<OrderItemDto> items;
        private Double subtotal;
        private Double discount;
        private Double total;
        private String paymentMethod;   // enum as string for Avro
        private String status;          // enum as string for Avro
        private String shippingCity;
        private String shippingState;
        private String timestamp;       // ISO-8601 format for Avro
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemDto {
        private String productId;
        private String productName;
        private String category;
        private Integer quantity;
        private Double price;
    }

    // ==================== AI CHAT EVENTS ====================

    /**
     * Event emitted when AI uses tools to search/filter products.
     * Captures user intent from natural language queries.
     *
     * IMPORTANT: All fields must be non-null for Avro serialization.
     * Use primitive types where possible, or ensure defaults are set.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AISearchEvent {
        private String eventId;
        private String userId;
        private String sessionId;
        private String query;                   // Original user query
        private String searchType;              // TOOL_CALL, RAG, HYBRID
        private String toolName;                // searchProducts, getProductDetails, etc.
        private String category;                // Category filter used (extracted from AI query)
        private double minPrice;                // Price range filter (use primitive to ensure non-null)
        private double maxPrice;
        private double minRating;               // Rating filter
        private int resultsCount;               // Number of products returned
        private List<String> returnedProductIds;
        private long processingTimeMs;
        private String timestamp;               // ISO-8601 format
    }
}
