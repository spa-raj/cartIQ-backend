package com.cartiq.order.controller;

import com.cartiq.order.dto.CreateOrderRequest;
import com.cartiq.order.dto.OrderDTO;
import com.cartiq.order.dto.OrderSummaryDTO;
import com.cartiq.common.enums.OrderStatus;
import com.cartiq.common.enums.PaymentStatus;
import com.cartiq.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderDTO> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        UUID userId = getCurrentUserId();
        OrderDTO order = orderService.createOrder(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping
    public ResponseEntity<Page<OrderSummaryDTO>> getUserOrders(
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        UUID userId = getCurrentUserId();

        Page<OrderSummaryDTO> orders = status != null
                ? orderService.getUserOrdersByStatus(userId, status, pageable)
                : orderService.getUserOrders(userId, pageable);

        return ResponseEntity.ok(orders);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDTO> getOrderById(@PathVariable UUID orderId) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(orderService.getOrderById(orderId, userId));
    }

    @GetMapping("/number/{orderNumber}")
    public ResponseEntity<OrderDTO> getOrderByNumber(@PathVariable String orderNumber) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(orderService.getOrderByNumber(orderNumber, userId));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderDTO> cancelOrder(@PathVariable UUID orderId) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(orderService.cancelOrder(orderId, userId));
    }

    // Admin endpoints
    @GetMapping("/admin/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<OrderSummaryDTO>> getAllOrders(
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<OrderSummaryDTO> orders = status != null
                ? orderService.getOrdersByStatus(status, pageable)
                : orderService.getAllOrders(pageable);

        return ResponseEntity.ok(orders);
    }

    @GetMapping("/admin/{orderId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrderDTO> getOrderByIdAdmin(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getOrderByIdAdmin(orderId));
    }

    @PatchMapping("/admin/{orderId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrderDTO> updateOrderStatus(
            @PathVariable UUID orderId,
            @RequestParam OrderStatus status) {
        return ResponseEntity.ok(orderService.updateOrderStatus(orderId, status));
    }

    @PatchMapping("/admin/{orderId}/payment")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrderDTO> updatePaymentStatus(
            @PathVariable UUID orderId,
            @RequestParam PaymentStatus status) {
        return ResponseEntity.ok(orderService.updatePaymentStatus(orderId, status));
    }

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString((String) authentication.getPrincipal());
    }
}
