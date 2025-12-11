package com.cartiq.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Types of pages in the e-commerce application.
 */
public enum PageType {
    HOME,
    CATEGORY,
    PRODUCT,
    CART,
    CHECKOUT;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
