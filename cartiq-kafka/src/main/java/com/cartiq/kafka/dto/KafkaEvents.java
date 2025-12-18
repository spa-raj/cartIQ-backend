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
 * - user-profiles: UserProfileEvent (output from Flink)
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
    public static class SearchEvent {
        private String eventId;
        private String userId;
        private String sessionId;
        private String query;
        private String categoryFilter;
        private String priceRangeFilter;
        private Integer resultsCount;
        private List<String> clickedProductIds;
        private String timestamp;       // ISO-8601 format for Avro
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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageViewEvent {
        private String eventId;
        private String userId;
        private String sessionId;
        private String pageType;        // enum as string for Avro
        private String pageUrl;
        private String productId;
        private String category;
        private String deviceType;      // enum as string for Avro
        private String referrer;
        private String timestamp;       // ISO-8601 format for Avro
        private Long durationMs;
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
        private String category;                // Category filter used (if any)
        private Double minPrice;                // Price range filter (if any)
        private Double maxPrice;
        private Double minRating;               // Rating filter (if any)
        private Integer resultsCount;           // Number of products returned
        private List<String> returnedProductIds;
        private Long processingTimeMs;
        private String timestamp;               // ISO-8601 format
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatInputEvent {
        private String eventId;
        private String userId;
        private String sessionId;
        private String message;
        private List<String> recentlyViewedProductIds;
        private List<String> recentCategories;
        private List<String> cartProductIds;
        private Double cartTotal;
        private UserContext userContext;
        private String timestamp;       // ISO-8601 format for Avro
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserContext {
        private String pricePreference;     // budget, mid, premium
        private List<String> preferredCategories;
        private List<String> purchaseHistory;   // category summary
        private String currentPage;
        private Integer sessionDurationMinutes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatResponseEvent {
        private String eventId;
        private String userId;
        private String sessionId;
        private String originalQuery;
        private String response;
        private List<ProductRecommendation> recommendations;
        private Double confidenceScore;
        private Long processingTimeMs;
        private String modelUsed;
        private String timestamp;       // ISO-8601 format for Avro
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductRecommendation {
        private String productId;
        private String productName;
        private Double price;
        private String category;
        private Double relevanceScore;
        private String reason;
    }

    // ==================== USER PROFILE EVENTS ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserProfileUpdateEvent {
        private String eventId;
        private String userId;
        private List<String> topCategories;
        private String pricePreference;
        private Integer totalOrders;
        private Double totalSpent;
        private Integer sessionCount;
        private String lastActive;      // ISO-8601 format for Avro
        private String timestamp;       // ISO-8601 format for Avro
    }
}
