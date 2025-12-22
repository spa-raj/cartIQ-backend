package com.cartiq.kafka.service;

import com.cartiq.kafka.dto.LastSearchContext;
import com.cartiq.kafka.dto.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;

/**
 * Service to manage chat context directly in Redis.
 * Updates lastViewedProductId and lastSearchContext for contextual follow-ups.
 *
 * These fields bypass Kafka/Flink because:
 * - They're real-time (need immediate update)
 * - They're ephemeral (short-term context)
 * - They don't need stream aggregation
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatContextService {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${cartiq.suggestions.cache.ttl-hours:1}")
    private int cacheTtlHours;

    @Value("${cartiq.suggestions.cache.prefix:user-profile:}")
    private String cachePrefix;

    /**
     * Update lastViewedProduct fields when user views a product.
     */
    public void updateLastViewedProduct(String userId, String productId, String productName, String categoryName) {
        if (userId == null || userId.isBlank() || productId == null) {
            return;
        }

        String cacheKey = cachePrefix + userId;
        UserProfile profile = getOrCreateProfile(userId, cacheKey);

        profile.setLastViewedProductId(productId);
        profile.setLastViewedProductName(productName);
        profile.setLastViewedProductCategory(categoryName);

        saveProfile(cacheKey, profile);
        log.debug("Updated lastViewedProduct: userId={}, productId={}, name={}",
                userId, productId, productName);
    }

    /**
     * Update lastSearchContext when user performs a search.
     */
    public void updateLastSearchContext(String userId, String query, String category,
                                         String brand, BigDecimal minPrice, BigDecimal maxPrice,
                                         BigDecimal minRating) {
        if (userId == null || userId.isBlank()) {
            return;
        }

        String cacheKey = cachePrefix + userId;
        UserProfile profile = getOrCreateProfile(userId, cacheKey);

        LastSearchContext context = LastSearchContext.builder()
                .query(query)
                .category(category)
                .brand(brand)
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .minRating(minRating)
                .timestamp(System.currentTimeMillis())
                .build();

        profile.setLastSearchContext(context);

        saveProfile(cacheKey, profile);
        log.debug("Updated lastSearchContext: userId={}, query={}, category={}, brand={}, price={}-{}",
                userId, query, category, brand, minPrice, maxPrice);
    }

    /**
     * Get the current user profile from Redis.
     */
    public UserProfile getUserProfile(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return getOrCreateProfile(userId, cachePrefix + userId);
    }

    /**
     * Get or create a UserProfile from Redis cache.
     */
    @SuppressWarnings("unchecked")
    private UserProfile getOrCreateProfile(String userId, String cacheKey) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof UserProfile userProfile) {
                return userProfile;
            } else if (cached instanceof LinkedHashMap) {
                return convertLinkedHashMapToUserProfile((LinkedHashMap<String, Object>) cached);
            }
        } catch (Exception e) {
            log.debug("No existing profile for userId={}, creating new: {}", userId, e.getMessage());
        }

        // Create minimal profile if none exists
        return UserProfile.builder()
                .userId(userId)
                .build();
    }

    /**
     * Save profile to Redis with TTL.
     */
    private void saveProfile(String cacheKey, UserProfile profile) {
        redisTemplate.opsForValue().set(cacheKey, profile, Duration.ofHours(cacheTtlHours));
    }

    /**
     * Convert LinkedHashMap (from Jackson deserialization) to UserProfile.
     */
    @SuppressWarnings("unchecked")
    private UserProfile convertLinkedHashMapToUserProfile(LinkedHashMap<String, Object> map) {
        UserProfile.UserProfileBuilder builder = UserProfile.builder()
                .userId((String) map.get("userId"))
                .sessionId((String) map.get("sessionId"))
                .recentProductIds((java.util.List<String>) map.get("recentProductIds"))
                .recentCategories((java.util.List<String>) map.get("recentCategories"))
                .recentSearchQueries((java.util.List<String>) map.get("recentSearchQueries"))
                .totalProductViews(toLong(map.get("totalProductViews")))
                .avgViewDurationMs(toLong(map.get("avgViewDurationMs")))
                .avgProductPrice(toDouble(map.get("avgProductPrice")))
                .pricePreference((String) map.get("pricePreference"))
                .currentCartTotal(toDouble(map.get("currentCartTotal")))
                .currentCartItems(toLong(map.get("currentCartItems")))
                .cartAdds(toLong(map.get("cartAdds")))
                .cartProductIds((java.util.List<String>) map.get("cartProductIds"))
                .cartCategories((java.util.List<String>) map.get("cartCategories"))
                .deviceType((String) map.get("deviceType"))
                .sessionDurationMs(toLong(map.get("sessionDurationMs")))
                .totalPageViews(toLong(map.get("totalPageViews")))
                .productPageViews(toLong(map.get("productPageViews")))
                .cartPageViews(toLong(map.get("cartPageViews")))
                .checkoutPageViews(toLong(map.get("checkoutPageViews")))
                .aiSearchCount(toLong(map.get("aiSearchCount")))
                .aiSearchQueries((java.util.List<String>) map.get("aiSearchQueries"))
                .aiSearchCategories((java.util.List<String>) map.get("aiSearchCategories"))
                .aiMaxBudget(toDouble(map.get("aiMaxBudget")))
                .aiProductSearches(toLong(map.get("aiProductSearches")))
                .aiProductComparisons(toLong(map.get("aiProductComparisons")))
                // Chat memory fields
                .lastViewedProductId((String) map.get("lastViewedProductId"))
                .lastViewedProductName((String) map.get("lastViewedProductName"))
                .lastViewedProductCategory((String) map.get("lastViewedProductCategory"))
                // Order history
                .totalOrders(toLong(map.get("totalOrders")))
                .totalSpent(toDouble(map.get("totalSpent")))
                .avgOrderValue(toDouble(map.get("avgOrderValue")))
                .lastOrderTotal(toDouble(map.get("lastOrderTotal")))
                .preferredPaymentMethod((String) map.get("preferredPaymentMethod"));

        // Handle lastSearchContext
        Object contextObj = map.get("lastSearchContext");
        if (contextObj instanceof LinkedHashMap) {
            LinkedHashMap<String, Object> ctxMap = (LinkedHashMap<String, Object>) contextObj;
            LastSearchContext context = LastSearchContext.builder()
                    .query((String) ctxMap.get("query"))
                    .category((String) ctxMap.get("category"))
                    .brand((String) ctxMap.get("brand"))
                    .minPrice(toBigDecimal(ctxMap.get("minPrice")))
                    .maxPrice(toBigDecimal(ctxMap.get("maxPrice")))
                    .minRating(toBigDecimal(ctxMap.get("minRating")))
                    .timestamp(toLong(ctxMap.get("timestamp")))
                    .build();
            builder.lastSearchContext(context);
        }

        return builder.build();
    }

    private Long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        return 0L;
    }

    private Double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Double d) return d;
        if (value instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }
}
