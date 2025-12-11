package com.cartiq.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Types of devices used to access the application.
 */
public enum DeviceType {
    MOBILE,
    DESKTOP,
    TABLET;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
