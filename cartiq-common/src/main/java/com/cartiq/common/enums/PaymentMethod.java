package com.cartiq.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Payment methods supported in the platform.
 */
public enum PaymentMethod {
    UPI,
    CARD,
    NETBANKING,
    COD,
    WALLET;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
