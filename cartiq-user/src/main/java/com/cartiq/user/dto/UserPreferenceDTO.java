package com.cartiq.user.dto;

import com.cartiq.user.entity.UserPreference;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferenceDTO {

    private UUID id;
    private BigDecimal minPricePreference;
    private BigDecimal maxPricePreference;
    private List<String> preferredCategories;
    private List<String> preferredBrands;
    private Boolean emailNotifications;
    private Boolean pushNotifications;
    private String currency;
    private String language;

    public static UserPreferenceDTO fromEntity(UserPreference preference) {
        return UserPreferenceDTO.builder()
                .id(preference.getId())
                .minPricePreference(preference.getMinPricePreference())
                .maxPricePreference(preference.getMaxPricePreference())
                .preferredCategories(preference.getPreferredCategories())
                .preferredBrands(preference.getPreferredBrands())
                .emailNotifications(preference.getEmailNotifications())
                .pushNotifications(preference.getPushNotifications())
                .currency(preference.getCurrency())
                .language(preference.getLanguage())
                .build();
    }
}
