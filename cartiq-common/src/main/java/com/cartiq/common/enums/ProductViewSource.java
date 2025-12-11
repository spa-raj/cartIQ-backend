package com.cartiq.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Source of product view - how the user arrived at the product page.
 */
public enum ProductViewSource {
    SEARCH,
    RECOMMENDATION,
    CATEGORY,
    DIRECT;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
