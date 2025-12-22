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
 * Fields match Flink SQL output from docs/flink-sql/03-user-profiles.sql
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

    // Preferences (computed by Flink from browsing + AI signals)
    private String pricePreference;             // BUDGET, MID_RANGE, PREMIUM

    // Cart State
    private Double currentCartTotal;
    private Long currentCartItems;
    private Long cartAdds;
    private List<String> cartProductIds;
    private List<String> cartCategories;

    // Session Info
    private String deviceType;
    private Long sessionDurationMs;
    private Long totalPageViews;
    private Long productPageViews;
    private Long cartPageViews;
    private Long checkoutPageViews;

    // AI Intent Signals (strong intent from chat interactions)
    private Long aiSearchCount;
    private List<String> aiSearchQueries;
    private List<String> aiSearchCategories;
    private Double aiMaxBudget;
    private Long aiProductSearches;
    private Long aiProductComparisons;

    // Chat Memory Context (for contextual follow-ups)
    private String lastViewedProductId;        // Last product viewed - for "accessories for this"
    private String lastViewedProductName;      // Product name for context
    private String lastViewedProductCategory;  // Category for finding related items
    private LastSearchContext lastSearchContext; // Last search params - for "show me cheaper"

    // Order History (HIGHEST intent - actual purchases)
    private Long totalOrders;
    private Double totalSpent;
    private Double avgOrderValue;
    private Double lastOrderTotal;
    private String preferredPaymentMethod;

    // Metadata
    private LocalDateTime lastUpdated;
}
