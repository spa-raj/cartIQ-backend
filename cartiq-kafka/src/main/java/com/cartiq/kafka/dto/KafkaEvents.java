package com.cartiq.kafka.dto;

import com.cartiq.common.enums.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Event DTOs for Kafka streaming.
 * These are published to Kafka topics and consumed by Flink/AI services.
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
        private EventType eventType;
        private PageType pageType;
        private String pageUrl;
        private DeviceType deviceType;
        private String referrer;
        private Instant timestamp;
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
        private BigDecimal price;
        private ProductViewSource source;
        private String searchQuery;     // if came from search
        private Instant timestamp;
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
        private Instant timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartEvent {
        private String eventId;
        private String userId;
        private String sessionId;
        private CartAction action;
        private String productId;
        private String productName;
        private String category;
        private Integer quantity;
        private BigDecimal price;
        private BigDecimal cartTotal;
        private Integer cartItemCount;
        private Instant timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageViewEvent {
        private String eventId;
        private String userId;
        private String sessionId;
        private PageType pageType;
        private String pageUrl;
        private String productId;
        private String category;
        private DeviceType deviceType;
        private String referrer;
        private Instant timestamp;
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
        private BigDecimal subtotal;
        private BigDecimal discount;
        private BigDecimal total;
        private PaymentMethod paymentMethod;
        private OrderStatus status;
        private String shippingCity;
        private String shippingState;
        private Instant timestamp;
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
        private BigDecimal price;
    }

    // ==================== AI CHAT EVENTS ====================

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
        private BigDecimal cartTotal;
        private UserContext userContext;
        private Instant timestamp;
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
        private Instant timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductRecommendation {
        private String productId;
        private String productName;
        private BigDecimal price;
        private String category;
        private Double relevanceScore;
        private String reason;          // why this was recommended
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
        private BigDecimal totalSpent;
        private Integer sessionCount;
        private Instant lastActive;
        private Instant timestamp;
    }
}
