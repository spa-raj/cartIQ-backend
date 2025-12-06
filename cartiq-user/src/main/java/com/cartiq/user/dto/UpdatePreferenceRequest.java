package com.cartiq.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePreferenceRequest {

    @DecimalMin(value = "0.00", message = "Minimum price must be non-negative")
    private BigDecimal minPricePreference;

    @DecimalMin(value = "0.00", message = "Maximum price must be non-negative")
    private BigDecimal maxPricePreference;

    @Size(max = 20, message = "Cannot have more than 20 preferred categories")
    private List<String> preferredCategories;

    @Size(max = 20, message = "Cannot have more than 20 preferred brands")
    private List<String> preferredBrands;

    private Boolean emailNotifications;
    private Boolean pushNotifications;

    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a valid 3-letter ISO 4217 code (e.g., USD, EUR)")
    private String currency;

    @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$", message = "Language must be a valid ISO 639-1 code (e.g., en, en-US)")
    private String language;
}
