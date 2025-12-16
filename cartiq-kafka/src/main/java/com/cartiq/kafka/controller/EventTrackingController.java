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
import java.util.stream.Collectors;

/**
 * REST controller for tracking user events.
 * Frontend calls these endpoints to stream behavior to Kafka.
 *
 * Endpoints map to Confluent Cloud topics:
 * - POST /api/events/user         → user-events topic
 * - POST /api/events/product-view → product-views topic
 * - POST /api/events/cart         → cart-events topic
 * - POST /api/events/order        → order-events topic
 *
 * Note: user-profiles topic is populated by Flink aggregations, not this controller
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
                .eventType(request.getEventType() != null ? request.getEventType().name() : null)
                .pageType(request.getPageType() != null ? request.getPageType().name() : null)
                .pageUrl(request.getPageUrl())
                .deviceType(request.getDeviceType() != null ? request.getDeviceType().name() : null)
                .referrer(request.getReferrer())
                .timestamp(Instant.now().toString())
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
                .price(request.getPrice() != null ? request.getPrice().doubleValue() : null)
                .source(request.getSource() != null ? request.getSource().name() : null)
                .searchQuery(request.getSearchQuery())
                .timestamp(Instant.now().toString())
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
                .action(request.getAction() != null ? request.getAction().name() : null)
                .productId(request.getProductId())
                .productName(request.getProductName())
                .category(request.getCategory())
                .quantity(request.getQuantity())
                .price(request.getPrice() != null ? request.getPrice().doubleValue() : null)
                .cartTotal(request.getCartTotal() != null ? request.getCartTotal().doubleValue() : null)
                .cartItemCount(request.getCartItemCount())
                .timestamp(Instant.now().toString())
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

        // Convert items to Avro-compatible DTOs
        List<OrderItemDto> items = request.getItems() != null
                ? request.getItems().stream()
                    .map(item -> OrderItemDto.builder()
                            .productId(item.getProductId())
                            .productName(item.getProductName())
                            .category(item.getCategory())
                            .quantity(item.getQuantity())
                            .price(item.getPrice() != null ? item.getPrice().doubleValue() : null)
                            .build())
                    .collect(Collectors.toList())
                : null;

        OrderEvent event = OrderEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .userId(request.getUserId())
                .orderId(request.getOrderId())
                .items(items)
                .subtotal(request.getSubtotal() != null ? request.getSubtotal().doubleValue() : null)
                .discount(request.getDiscount() != null ? request.getDiscount().doubleValue() : null)
                .total(request.getTotal() != null ? request.getTotal().doubleValue() : null)
                .paymentMethod(request.getPaymentMethod() != null ? request.getPaymentMethod().name() : null)
                .status(request.getStatus() != null ? request.getStatus().name() : null)
                .shippingCity(request.getShippingCity())
                .shippingState(request.getShippingState())
                .timestamp(Instant.now().toString())
                .build();

        eventProducer.publishOrderEvent(event);
        log.debug("Tracked order: user={}, orderId={}, total={}",
                request.getUserId(), request.getOrderId(), request.getTotal());

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
        private List<OrderItemRequest> items;
        private BigDecimal subtotal;
        private BigDecimal discount;
        private BigDecimal total;
        private PaymentMethod paymentMethod;
        private OrderStatus status;
        private String shippingCity;
        private String shippingState;
    }

    @Data
    public static class OrderItemRequest {
        private String productId;
        private String productName;
        private String category;
        private Integer quantity;
        private BigDecimal price;
    }
}
