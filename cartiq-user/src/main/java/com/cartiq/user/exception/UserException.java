package com.cartiq.user.exception;

import org.springframework.http.HttpStatus;

public class UserException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public UserException(String message, HttpStatus status) {
        super(message);
        this.status = status;
        this.errorCode = null;
    }

    public UserException(String message, HttpStatus status, String errorCode) {
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

    // Common exceptions - generic messages to prevent user enumeration
    public static UserException emailAlreadyExists(String email) {
        return new UserException("Registration failed. Please try again or use a different email.", HttpStatus.CONFLICT, "REGISTRATION_FAILED");
    }

    public static UserException phoneAlreadyExists(String phone) {
        return new UserException("Registration failed. Please try again or use a different phone number.", HttpStatus.CONFLICT, "REGISTRATION_FAILED");
    }

    public static UserException invalidCredentials() {
        return new UserException("Invalid email or password", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    }

    public static UserException accountDisabled() {
        return new UserException("Account is disabled. Please contact support.", HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
    }

    public static UserException userNotFound(String identifier) {
        return new UserException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    public static UserException preferencesNotFound() {
        return new UserException("User preferences not found", HttpStatus.NOT_FOUND, "PREFERENCES_NOT_FOUND");
    }

    public static UserException tokenRevoked() {
        return new UserException("Token has been revoked. Please login again.", HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED");
    }

    public static UserException adminRequired() {
        return new UserException("Admin privileges required to perform this action", HttpStatus.FORBIDDEN, "ADMIN_REQUIRED");
    }

    public static UserException invalidPriceRange() {
        return new UserException("Minimum price cannot be greater than maximum price", HttpStatus.BAD_REQUEST, "INVALID_PRICE_RANGE");
    }
}
