package com.cartiq.order.exception;

import org.springframework.http.HttpStatus;

public class OrderException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public OrderException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    // Cart-related exceptions
    public static OrderException cartNotFound(String userId) {
        return new OrderException(
                "Cart not found for user",
                HttpStatus.NOT_FOUND,
                "CART_NOT_FOUND"
        );
    }

    public static OrderException cartItemNotFound(String itemId) {
        return new OrderException(
                "Cart item not found",
                HttpStatus.NOT_FOUND,
                "CART_ITEM_NOT_FOUND"
        );
    }

    public static OrderException emptyCart() {
        return new OrderException(
                "Cannot place order with empty cart",
                HttpStatus.BAD_REQUEST,
                "EMPTY_CART"
        );
    }

    public static OrderException productNotInCart(String productId) {
        return new OrderException(
                "Product not found in cart",
                HttpStatus.NOT_FOUND,
                "PRODUCT_NOT_IN_CART"
        );
    }

    // Order-related exceptions
    public static OrderException orderNotFound(String orderId) {
        return new OrderException(
                "Order not found",
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND"
        );
    }

    public static OrderException orderNotCancellable(String orderNumber) {
        return new OrderException(
                String.format("Order %s cannot be cancelled in its current state", orderNumber),
                HttpStatus.BAD_REQUEST,
                "ORDER_NOT_CANCELLABLE"
        );
    }

    public static OrderException invalidOrderStatus(String currentStatus, String expectedStatus) {
        return new OrderException(
                String.format("Invalid order status. Current: %s, Expected: %s", currentStatus, expectedStatus),
                HttpStatus.BAD_REQUEST,
                "INVALID_ORDER_STATUS"
        );
    }

    // Product-related exceptions (within order context)
    public static OrderException productNotFound(String productId) {
        return new OrderException(
                "Product not found",
                HttpStatus.NOT_FOUND,
                "PRODUCT_NOT_FOUND"
        );
    }

    public static OrderException productNotAvailable(String productName) {
        return new OrderException(
                String.format("Product '%s' is not available", productName),
                HttpStatus.BAD_REQUEST,
                "PRODUCT_NOT_AVAILABLE"
        );
    }

    public static OrderException insufficientStock(String productName, int available, int requested) {
        return new OrderException(
                String.format("Insufficient stock for '%s'. Available: %d, Requested: %d",
                        productName, available, requested),
                HttpStatus.BAD_REQUEST,
                "INSUFFICIENT_STOCK"
        );
    }

    // Access-related exceptions
    public static OrderException accessDenied() {
        return new OrderException(
                "You do not have permission to access this resource",
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED"
        );
    }
}
