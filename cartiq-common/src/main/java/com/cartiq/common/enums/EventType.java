package com.cartiq.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Types of user events tracked in the system.
 */
public enum EventType {
    LOGIN,
    LOGOUT,
    PAGE_VIEW,
    SESSION_START,
    SESSION_END;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
