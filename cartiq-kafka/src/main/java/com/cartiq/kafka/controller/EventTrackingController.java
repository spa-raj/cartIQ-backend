package com.cartiq.kafka.controller;

import com.cartiq.common.enums.*;
import com.cartiq.kafka.dto.KafkaEvents.*;
import com.cartiq.kafka.producer.EventProducer;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for tracking user events.
 * Frontend calls these endpoints to stream behavior to Kafka.
 *
 * Endpoints map to Confluent Cloud topics:
 * - POST /api/events/user         → user-events topic
 * - POST /api/events/product-view → product-views topic
 * - POST /api/events/cart         → cart-events topic
 * - POST /api/events/order        → order-events topic
 * - POST /api/events/user-profile → user-profiles topic
 */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*") // Configure properly for production
public class EventTrackingController {

    private final EventProducer eventProducer;

    /**
     * Track user session events (login, logout, page navigation)
     * → user-events topic
     */
    @PostMapping("/user")
    public ResponseEntity<Map<String, String>> trackUserEvent(
            @RequestBody @Valid UserEventRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        UserEvent event = UserEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .userId(request.getUserId())
                .sessionId(sessionId != null ? sessionId : UUID.randomUUID().toString())
                .eventType(request.getEventType())
                .pageType(request.getPageType())
                .pageUrl(request.getPageUrl())
                .deviceType(request.getDeviceType())
                .referrer(request.getReferrer())
                .timestamp(Instant.now())
                .build();

        eventProducer.publishUserEvent(event);
        log.debug("Tracked user event: user={}, type={}", request.getUserId(), request.getEventType());

        return ResponseEntity.ok(Map.of("status", "tracked", "eventId", event.getEventId()));
    }

    /**
     * Track product view
     * Called when user views a product detail page
     * → product-views topic
     */
    @PostMapping("/product-view")
    public ResponseEntity<Map<String, String>> trackProductView(
            @RequestBody @Valid ProductViewRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        ProductViewEvent event = ProductViewEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .userId(request.getUserId())
                .sessionId(sessionId != null ? sessionId : UUID.randomUUID().toString())
                .productId(request.getProductId())
                .productName(request.getProductName())
                .category(request.getCategory())
                .price(request.getPrice())
                .source(request.getSource())
                .searchQuery(request.getSearchQuery())
                .timestamp(Instant.now())
                .viewDurationMs(request.getViewDurationMs())
                .build();

        eventProducer.publishProductView(event);
        log.debug("Tracked product view: user={}, product={}", request.getUserId(), request.getProductId());

        return ResponseEntity.ok(Map.of("status", "tracked", "eventId", event.getEventId()));
    }

    /**
     * Track cart event
     * Called when user adds/removes items from cart
     * → cart-events topic
     */
    @PostMapping("/cart")
    public ResponseEntity<Map<String, String>> trackCartEvent(
            @RequestBody @Valid CartRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        CartEvent event = CartEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .userId(request.getUserId())
                .sessionId(sessionId != null ? sessionId : UUID.randomUUID().toString())
                .action(request.getAction())
                .productId(request.getProductId())
                .productName(request.getProductName())
                .category(request.getCategory())
                .quantity(request.getQuantity())
                .price(request.getPrice())
                .cartTotal(request.getCartTotal())
                .cartItemCount(request.getCartItemCount())
                .timestamp(Instant.now())
                .build();

        eventProducer.publishCartEvent(event);
        log.debug("Tracked cart: user={}, action={}, product={}",
                request.getUserId(), request.getAction(), request.getProductId());

        return ResponseEntity.ok(Map.of("status", "tracked", "eventId", event.getEventId()));
    }

    /**
     * Track order event
     * Called when user places an order
     * → order-events topic
     */
    @PostMapping("/order")
    public ResponseEntity<Map<String, String>> trackOrderEvent(
            @RequestBody @Valid OrderRequest request) {

        OrderEvent event = OrderEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .userId(request.getUserId())
                .orderId(request.getOrderId())
                .items(request.getItems())
                .subtotal(request.getSubtotal())
                .discount(request.getDiscount())
                .total(request.getTotal())
                .paymentMethod(request.getPaymentMethod())
                .status(request.getStatus())
                .shippingCity(request.getShippingCity())
                .shippingState(request.getShippingState())
                .timestamp(Instant.now())
                .build();

        eventProducer.publishOrderEvent(event);
        log.debug("Tracked order: user={}, orderId={}, total={}",
                request.getUserId(), request.getOrderId(), request.getTotal());

        return ResponseEntity.ok(Map.of("status", "tracked", "eventId", event.getEventId()));
    }

    /**
     * Track user profile update
     * Called to send user profile snapshot to Kafka
     * → user-profiles topic
     */
    @PostMapping("/user-profile")
    public ResponseEntity<Map<String, String>> trackUserProfile(
            @RequestBody @Valid UserProfileRequest request) {

        UserProfileUpdateEvent event = UserProfileUpdateEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .userId(request.getUserId())
                .topCategories(request.getTopCategories())
                .pricePreference(request.getPricePreference())
                .totalOrders(request.getTotalOrders())
                .totalSpent(request.getTotalSpent())
                .sessionCount(request.getSessionCount())
                .lastActive(request.getLastActive())
                .timestamp(Instant.now())
                .build();

        eventProducer.publishUserProfileUpdate(event);
        log.debug("Tracked user profile: user={}", request.getUserId());

        return ResponseEntity.ok(Map.of("status", "tracked", "eventId", event.getEventId()));
    }

    // ==================== REQUEST DTOs ====================

    @Data
    public static class UserEventRequest {
        private String userId;
        private EventType eventType;
        private PageType pageType;
        private String pageUrl;
        private DeviceType deviceType;
        private String referrer;
    }

    @Data
    public static class ProductViewRequest {
        private String userId;
        private String productId;
        private String productName;
        private String category;
        private BigDecimal price;
        private ProductViewSource source;
        private String searchQuery;
        private Long viewDurationMs;
    }

    @Data
    public static class CartRequest {
        private String userId;
        private CartAction action;
        private String productId;
        private String productName;
        private String category;
        private Integer quantity;
        private BigDecimal price;
        private BigDecimal cartTotal;
        private Integer cartItemCount;
    }

    @Data
    public static class OrderRequest {
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
    }

    @Data
    public static class UserProfileRequest {
        private String userId;
        private List<String> topCategories;
        private String pricePreference;
        private Integer totalOrders;
        private BigDecimal totalSpent;
        private Integer sessionCount;
        private Instant lastActive;
    }
}
