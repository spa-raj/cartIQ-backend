package com.cartiq.kafka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Cached user profile for personalized recommendations.
 * Stored in Redis with TTL, updated by Kafka consumer from user-profiles topic.
 * Fields match Flink SQL output from docs/flink-sql/02-user-profiles.sql
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile implements Serializable {

    private String userId;
    private String sessionId;

    // Product Browsing
    private List<String> recentProductIds;
    private List<String> recentCategories;
    private List<String> recentSearchQueries;
    private Long totalProductViews;
    private Long avgViewDurationMs;
    private Double avgProductPrice;

    // Cart State
    private Long totalCartAdds;
    private Double currentCartTotal;
    private Long currentCartItems;

    // Session Info
    private String deviceType;
    private Long sessionDurationMs;

    // Preferences (computed by Flink)
    private String pricePreference;             // BUDGET, MID_RANGE, PREMIUM

    // AI Intent Signals (strong intent from chat interactions)
    private Long aiSearchCount;
    private List<String> aiSearchQueries;
    private List<String> aiSearchCategories;
    private Double aiMaxBudget;
    private Long aiProductSearches;
    private Long aiProductComparisons;

    // Metadata
    private LocalDateTime lastUpdated;
}
