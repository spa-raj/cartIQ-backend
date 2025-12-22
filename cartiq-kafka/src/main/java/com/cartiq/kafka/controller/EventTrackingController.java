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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
public class EventTrackingController {

    private final EventProducer eventProducer;

    // Flink SQL TO_TIMESTAMP() expects this format: yyyy-MM-dd HH:mm:ss.SSS
    private static final DateTimeFormatter FLINK_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static String now() {
        return LocalDateTime.now(ZoneOffset.UTC).format(FLINK_TIMESTAMP_FORMAT);
    }

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
                .userId(request.getUserId() != null ? request.getUserId() : "")
                .sessionId(sessionId != null ? sessionId : UUID.randomUUID().toString())
                .eventType(request.getEventType() != null ? request.getEventType().name() : "")
                .pageType(request.getPageType() != null ? request.getPageType().name() : "")
                .pageUrl(request.getPageUrl() != null ? request.getPageUrl() : "")
                .deviceType(request.getDeviceType() != null ? request.getDeviceType().name() : "")
                .referrer(request.getReferrer() != null ? request.getReferrer() : "")
                .timestamp(now())
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
                .userId(request.getUserId() != null ? request.getUserId() : "")
                .sessionId(sessionId != null ? sessionId : UUID.randomUUID().toString())
                .productId(request.getProductId() != null ? request.getProductId() : "")
                .productName(request.getProductName() != null ? request.getProductName() : "")
                .category(request.getCategory() != null ? request.getCategory() : "")
                .price(request.getPrice() != null ? request.getPrice().doubleValue() : 0.0)
                .source(request.getSource() != null ? request.getSource().name() : "")
                .searchQuery(request.getSearchQuery() != null ? request.getSearchQuery() : "")
                .timestamp(now())
                .viewDurationMs(request.getViewDurationMs() != null ? request.getViewDurationMs() : 0L)
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
                .userId(request.getUserId() != null ? request.getUserId() : "")
                .sessionId(sessionId != null ? sessionId : UUID.randomUUID().toString())
                .action(request.getAction() != null ? request.getAction().name() : "")
                .productId(request.getProductId() != null ? request.getProductId() : "")
                .productName(request.getProductName() != null ? request.getProductName() : "")
                .category(request.getCategory() != null ? request.getCategory() : "")
                .quantity(request.getQuantity() != null ? request.getQuantity() : 0)
                .price(request.getPrice() != null ? request.getPrice().doubleValue() : 0.0)
                .cartTotal(request.getCartTotal() != null ? request.getCartTotal().doubleValue() : 0.0)
                .cartItemCount(request.getCartItemCount() != null ? request.getCartItemCount() : 0)
                .timestamp(now())
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
                            .productId(item.getProductId() != null ? item.getProductId() : "")
                            .productName(item.getProductName() != null ? item.getProductName() : "")
                            .category(item.getCategory() != null ? item.getCategory() : "")
                            .quantity(item.getQuantity() != null ? item.getQuantity() : 0)
                            .price(item.getPrice() != null ? item.getPrice().doubleValue() : 0.0)
                            .build())
                    .collect(Collectors.toList())
                : List.of();

        OrderEvent event = OrderEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .userId(request.getUserId() != null ? request.getUserId() : "")
                .orderId(request.getOrderId() != null ? request.getOrderId() : "")
                .items(items)
                .subtotal(request.getSubtotal() != null ? request.getSubtotal().doubleValue() : 0.0)
                .discount(request.getDiscount() != null ? request.getDiscount().doubleValue() : 0.0)
                .total(request.getTotal() != null ? request.getTotal().doubleValue() : 0.0)
                .paymentMethod(request.getPaymentMethod() != null ? request.getPaymentMethod().name() : "")
                .status(request.getStatus() != null ? request.getStatus().name() : "")
                .shippingCity(request.getShippingCity() != null ? request.getShippingCity() : "")
                .shippingState(request.getShippingState() != null ? request.getShippingState() : "")
                .timestamp(now())
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
