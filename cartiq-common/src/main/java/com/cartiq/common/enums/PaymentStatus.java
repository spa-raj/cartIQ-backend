package com.cartiq.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Payment statuses.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
