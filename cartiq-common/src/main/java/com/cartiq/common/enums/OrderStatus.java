package com.cartiq.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Order lifecycle statuses.
 */
public enum OrderStatus {
    PENDING,
    PLACED,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    REFUNDED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
