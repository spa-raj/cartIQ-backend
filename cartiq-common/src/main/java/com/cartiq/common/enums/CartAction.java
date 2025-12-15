package com.cartiq.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Types of cart actions.
 */
public enum CartAction {
    ADD,
    REMOVE,
    UPDATE_QUANTITY,
    CLEAR;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
