package com.cartiq.order.service;

import com.cartiq.order.dto.CreateOrderRequest;
import com.cartiq.order.dto.OrderDTO;
import com.cartiq.order.dto.OrderSummaryDTO;
import com.cartiq.order.entity.*;
import com.cartiq.order.exception.OrderException;
import com.cartiq.order.repository.CartRepository;
import com.cartiq.order.repository.OrderRepository;
import com.cartiq.product.dto.ProductDTO;
import com.cartiq.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final ProductService productService;
    private final CartService cartService;

    private static final BigDecimal TAX_RATE = new BigDecimal("0.08");
    private static final BigDecimal SHIPPING_THRESHOLD = new BigDecimal("50.00");
    private static final BigDecimal STANDARD_SHIPPING = new BigDecimal("5.99");

    @Transactional
    public OrderDTO createOrder(UUID userId, CreateOrderRequest request) {
        Cart cart = cartRepository.findByUserIdWithItems(userId)
                .orElseThrow(() -> OrderException.cartNotFound(userId.toString()));

        if (cart.getItems().isEmpty()) {
            throw OrderException.emptyCart();
        }

        for (CartItem cartItem : cart.getItems()) {
            ProductDTO product = productService.getProductById(cartItem.getProductId());

            if (!product.getInStock()) {
                throw OrderException.productNotAvailable(product.getName());
            }

            if (product.getStockQuantity() < cartItem.getQuantity()) {
                throw OrderException.insufficientStock(
                        product.getName(),
                        product.getStockQuantity(),
                        cartItem.getQuantity()
                );
            }
        }

        BigDecimal subtotal = cart.getTotalAmount();
        BigDecimal shippingCost = subtotal.compareTo(SHIPPING_THRESHOLD) >= 0
                ? BigDecimal.ZERO
                : STANDARD_SHIPPING;
        BigDecimal tax = subtotal.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAmount = subtotal.add(shippingCost).add(tax);

        Order order = Order.builder()
                .orderNumber(generateOrderNumber())
                .userId(userId)
                .subtotal(subtotal)
                .shippingCost(shippingCost)
                .tax(tax)
                .totalAmount(totalAmount)
                .shippingAddress(request.getShippingAddress())
                .shippingCity(request.getShippingCity())
                .shippingState(request.getShippingState())
                .shippingZipCode(request.getShippingZipCode())
                .shippingCountry(request.getShippingCountry())
                .contactPhone(request.getContactPhone())
                .notes(request.getNotes())
                .status(OrderStatus.PENDING)
                .paymentStatus(PaymentStatus.PENDING)
                .build();

        for (CartItem cartItem : cart.getItems()) {
            ProductDTO product = productService.getProductById(cartItem.getProductId());

            OrderItem orderItem = OrderItem.builder()
                    .productId(cartItem.getProductId())
                    .productSku(product.getSku())
                    .productName(cartItem.getProductName())
                    .unitPrice(cartItem.getUnitPrice())
                    .quantity(cartItem.getQuantity())
                    .subtotal(cartItem.getSubtotal())
                    .thumbnailUrl(cartItem.getThumbnailUrl())
                    .build();

            order.addItem(orderItem);

            productService.updateStock(cartItem.getProductId(), -cartItem.getQuantity());
        }

        order = orderRepository.save(order);
        log.info("Order created: orderNumber={}, userId={}, total={}",
                order.getOrderNumber(), userId, totalAmount);

        cartService.clearCart(userId);

        return OrderDTO.fromEntity(order);
    }

    public OrderDTO getOrderById(UUID orderId, UUID userId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> OrderException.orderNotFound(orderId.toString()));

        if (!order.getUserId().equals(userId)) {
            throw OrderException.accessDenied();
        }

        return OrderDTO.fromEntity(order);
    }

    public OrderDTO getOrderByNumber(String orderNumber, UUID userId) {
        Order order = orderRepository.findByOrderNumberWithItems(orderNumber)
                .orElseThrow(() -> OrderException.orderNotFound(orderNumber));

        if (!order.getUserId().equals(userId)) {
            throw OrderException.accessDenied();
        }

        return OrderDTO.fromEntity(order);
    }

    public Page<OrderSummaryDTO> getUserOrders(UUID userId, Pageable pageable) {
        return orderRepository.findByUserId(userId, pageable)
                .map(OrderSummaryDTO::fromEntity);
    }

    public Page<OrderSummaryDTO> getUserOrdersByStatus(UUID userId, OrderStatus status, Pageable pageable) {
        return orderRepository.findByUserIdAndStatus(userId, status, pageable)
                .map(OrderSummaryDTO::fromEntity);
    }

    @Transactional
    public OrderDTO cancelOrder(UUID orderId, UUID userId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> OrderException.orderNotFound(orderId.toString()));

        if (!order.getUserId().equals(userId)) {
            throw OrderException.accessDenied();
        }

        if (!order.isCancellable()) {
            throw OrderException.orderNotCancellable(order.getOrderNumber());
        }

        for (OrderItem item : order.getItems()) {
            productService.updateStock(item.getProductId(), item.getQuantity());
        }

        order.setStatus(OrderStatus.CANCELLED);
        order = orderRepository.save(order);

        log.info("Order cancelled: orderNumber={}, userId={}", order.getOrderNumber(), userId);

        return OrderDTO.fromEntity(order);
    }

    // Admin methods
    public Page<OrderSummaryDTO> getAllOrders(Pageable pageable) {
        return orderRepository.findAll(pageable)
                .map(OrderSummaryDTO::fromEntity);
    }

    public Page<OrderSummaryDTO> getOrdersByStatus(OrderStatus status, Pageable pageable) {
        return orderRepository.findByStatus(status, pageable)
                .map(OrderSummaryDTO::fromEntity);
    }

    public OrderDTO getOrderByIdAdmin(UUID orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> OrderException.orderNotFound(orderId.toString()));
        return OrderDTO.fromEntity(order);
    }

    @Transactional
    public OrderDTO updateOrderStatus(UUID orderId, OrderStatus newStatus) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> OrderException.orderNotFound(orderId.toString()));

        order.setStatus(newStatus);
        order = orderRepository.save(order);

        log.info("Order status updated: orderNumber={}, newStatus={}",
                order.getOrderNumber(), newStatus);

        return OrderDTO.fromEntity(order);
    }

    @Transactional
    public OrderDTO updatePaymentStatus(UUID orderId, PaymentStatus newStatus) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> OrderException.orderNotFound(orderId.toString()));

        order.setPaymentStatus(newStatus);

        if (newStatus == PaymentStatus.PAID && order.getStatus() == OrderStatus.PENDING) {
            order.setStatus(OrderStatus.CONFIRMED);
        }

        order = orderRepository.save(order);

        log.info("Payment status updated: orderNumber={}, paymentStatus={}, orderStatus={}",
                order.getOrderNumber(), newStatus, order.getStatus());

        return OrderDTO.fromEntity(order);
    }

    private String generateOrderNumber() {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int random = ThreadLocalRandom.current().nextInt(1000, 9999);
        return "ORD-" + timestamp + "-" + random;
    }
}
