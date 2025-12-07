package com.cartiq.product.exception;

import org.springframework.http.HttpStatus;

public class ProductException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public ProductException(String message, HttpStatus status, String errorCode) {
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

    public static ProductException productNotFound(String identifier) {
        return new ProductException("Product not found", HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }

    public static ProductException categoryNotFound(String identifier) {
        return new ProductException("Category not found", HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND");
    }

    public static ProductException skuAlreadyExists(String sku) {
        return new ProductException("Product with SKU already exists", HttpStatus.CONFLICT, "SKU_EXISTS");
    }

    public static ProductException categoryNameExists(String name) {
        return new ProductException("Category with this name already exists", HttpStatus.CONFLICT, "CATEGORY_NAME_EXISTS");
    }

    public static ProductException invalidPriceRange() {
        return new ProductException("Minimum price cannot exceed maximum price", HttpStatus.BAD_REQUEST, "INVALID_PRICE_RANGE");
    }

    public static ProductException insufficientStock(String productName, int available) {
        return new ProductException(
                String.format("Insufficient stock for %s. Available: %d", productName, available),
                HttpStatus.BAD_REQUEST,
                "INSUFFICIENT_STOCK"
        );
    }

    public static ProductException batchSizeExceeded(int maxSize) {
        return new ProductException(
                String.format("Batch size cannot exceed %d items", maxSize),
                HttpStatus.BAD_REQUEST,
                "BATCH_SIZE_EXCEEDED"
        );
    }
}
