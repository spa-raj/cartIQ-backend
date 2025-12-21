package com.cartiq.kafka.consumer;

import com.cartiq.kafka.dto.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Kafka consumer for user-profiles topic.
 * Caches user profile data in Redis for the Suggestions API.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserProfileConsumer {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${cartiq.suggestions.cache.ttl-hours:1}")
    private int cacheTtlHours;

    @Value("${cartiq.suggestions.cache.prefix:user-profile:}")
    private String cachePrefix;

    @PostConstruct
    public void init() {
        log.info("UserProfileConsumer initialized - listening to 'user-profiles' topic, caching to Redis with TTL={}h", cacheTtlHours);
    }

    /**
     * Consumes user profile events from Flink.
     * Flink uses upsert mode with composite keys, so:
     * - Key contains: userId, sessionId, windowBucket (primary keys)
     * - Value contains: all other fields
     */
    @KafkaListener(
            topics = "user-profiles",
            groupId = "cartiq-suggestions-consumer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeUserProfile(ConsumerRecord<GenericRecord, GenericRecord> consumerRecord) {
        try {
            GenericRecord key = consumerRecord.key();
            GenericRecord value = consumerRecord.value();

            // Handle tombstone messages (value is null for deletes in upsert mode)
            if (value == null) {
                log.debug("Received tombstone message for key: {}", key);
                return;
            }

            // Extract userId from the KEY (not value)
            String userId = getStringField(key, "userId");
            if (userId == null || userId.isBlank()) {
                log.warn("Received user profile event with null userId in key, skipping");
                return;
            }

            String cacheKey = cachePrefix + userId;
            UserProfile incomingProfile = mapToUserProfile(key, value);

            // Fetch existing profile and merge (instead of overwriting)
            UserProfile existingProfile = getExistingProfile(cacheKey);
            UserProfile mergedProfile = mergeProfiles(existingProfile, incomingProfile);

            redisTemplate.opsForValue().set(
                    cacheKey,
                    mergedProfile,
                    Duration.ofHours(cacheTtlHours)
            );

            log.info("Merged user profile: userId={}, recentProducts={}, categories={}, aiCategories={}",
                    mergedProfile.getUserId(),
                    mergedProfile.getRecentProductIds() != null ? mergedProfile.getRecentProductIds().size() : 0,
                    mergedProfile.getRecentCategories(),
                    mergedProfile.getAiSearchCategories()
            );

        } catch (Exception e) {
            log.error("Failed to process user profile event: {}",
                    e.getMessage(),
                    e
            );
        }
    }

    /**
     * Fetch existing profile from Redis cache.
     */
    @SuppressWarnings("unchecked")
    private UserProfile getExistingProfile(String cacheKey) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof UserProfile userProfile) {
                return userProfile;
            } else if (cached instanceof LinkedHashMap) {
                return convertLinkedHashMapToUserProfile((LinkedHashMap<String, Object>) cached);
            }
        } catch (Exception e) {
            log.debug("No existing profile found for key {}: {}", cacheKey, e.getMessage());
        }
        return null;
    }

    /**
     * Convert LinkedHashMap (from Jackson deserialization) to UserProfile.
     */
    @SuppressWarnings("unchecked")
    private UserProfile convertLinkedHashMapToUserProfile(LinkedHashMap<String, Object> map) {
        return UserProfile.builder()
                .userId((String) map.get("userId"))
                .sessionId((String) map.get("sessionId"))
                // Product activity
                .recentProductIds((List<String>) map.get("recentProductIds"))
                .recentCategories((List<String>) map.get("recentCategories"))
                .recentSearchQueries((List<String>) map.get("recentSearchQueries"))
                .totalProductViews(toLong(map.get("totalProductViews")))
                .avgViewDurationMs(toLong(map.get("avgViewDurationMs")))
                .avgProductPrice(toDouble(map.get("avgProductPrice")))
                // Preferences
                .pricePreference((String) map.get("pricePreference"))
                // Cart state
                .currentCartTotal(toDouble(map.get("currentCartTotal")))
                .currentCartItems(toLong(map.get("currentCartItems")))
                .cartAdds(toLong(map.get("cartAdds")))
                .cartProductIds((List<String>) map.get("cartProductIds"))
                .cartCategories((List<String>) map.get("cartCategories"))
                // Session info
                .deviceType((String) map.get("deviceType"))
                .sessionDurationMs(toLong(map.get("sessionDurationMs")))
                .totalPageViews(toLong(map.get("totalPageViews")))
                .productPageViews(toLong(map.get("productPageViews")))
                .cartPageViews(toLong(map.get("cartPageViews")))
                .checkoutPageViews(toLong(map.get("checkoutPageViews")))
                // AI intent signals
                .aiSearchCount(toLong(map.get("aiSearchCount")))
                .aiSearchQueries((List<String>) map.get("aiSearchQueries"))
                .aiSearchCategories((List<String>) map.get("aiSearchCategories"))
                .aiMaxBudget(toDouble(map.get("aiMaxBudget")))
                .aiProductSearches(toLong(map.get("aiProductSearches")))
                .aiProductComparisons(toLong(map.get("aiProductComparisons")))
                // Order history
                .totalOrders(toLong(map.get("totalOrders")))
                .totalSpent(toDouble(map.get("totalSpent")))
                .avgOrderValue(toDouble(map.get("avgOrderValue")))
                .lastOrderTotal(toDouble(map.get("lastOrderTotal")))
                .preferredPaymentMethod((String) map.get("preferredPaymentMethod"))
                .build();
    }

    /**
     * Merge incoming profile with existing cached profile.
     * - Lists are prepended (new items first) and deduplicated, limited to max size
     * - Counts are accumulated
     * - Latest values win for single-value fields
     */
    private UserProfile mergeProfiles(UserProfile existing, UserProfile incoming) {
        if (existing == null) {
            return incoming;
        }

        return UserProfile.builder()
                // Identity - use incoming (latest)
                .userId(incoming.getUserId())
                .sessionId(incoming.getSessionId())

                // Lists - prepend new items, deduplicate, limit size
                .recentProductIds(mergeLists(incoming.getRecentProductIds(), existing.getRecentProductIds(), 20))
                .recentCategories(mergeLists(incoming.getRecentCategories(), existing.getRecentCategories(), 10))
                .recentSearchQueries(mergeLists(incoming.getRecentSearchQueries(), existing.getRecentSearchQueries(), 10))

                // Counts - accumulate (sum)
                .totalProductViews(safeAdd(existing.getTotalProductViews(), incoming.getTotalProductViews()))

                // Averages - use incoming (latest window's average)
                .avgViewDurationMs(incoming.getAvgViewDurationMs())
                .avgProductPrice(incoming.getAvgProductPrice() > 0 ? incoming.getAvgProductPrice() : existing.getAvgProductPrice())

                // Price preference - use incoming if set, else existing
                .pricePreference(incoming.getPricePreference() != null && !incoming.getPricePreference().equals("UNKNOWN")
                        ? incoming.getPricePreference()
                        : existing.getPricePreference())

                // Cart state - use incoming (current state)
                .currentCartTotal(incoming.getCurrentCartTotal())
                .currentCartItems(incoming.getCurrentCartItems())
                .cartAdds(safeAdd(existing.getCartAdds(), incoming.getCartAdds()))
                .cartProductIds(mergeLists(incoming.getCartProductIds(), existing.getCartProductIds(), 20))
                .cartCategories(mergeLists(incoming.getCartCategories(), existing.getCartCategories(), 10))

                // Session info - use incoming (latest)
                .deviceType(incoming.getDeviceType() != null ? incoming.getDeviceType() : existing.getDeviceType())
                .sessionDurationMs(Math.max(safeLong(existing.getSessionDurationMs()), safeLong(incoming.getSessionDurationMs())))
                .totalPageViews(safeAdd(existing.getTotalPageViews(), incoming.getTotalPageViews()))
                .productPageViews(safeAdd(existing.getProductPageViews(), incoming.getProductPageViews()))
                .cartPageViews(safeAdd(existing.getCartPageViews(), incoming.getCartPageViews()))
                .checkoutPageViews(safeAdd(existing.getCheckoutPageViews(), incoming.getCheckoutPageViews()))

                // AI intent signals - accumulate and merge
                .aiSearchCount(safeAdd(existing.getAiSearchCount(), incoming.getAiSearchCount()))
                .aiSearchQueries(mergeLists(incoming.getAiSearchQueries(), existing.getAiSearchQueries(), 15))
                .aiSearchCategories(mergeLists(incoming.getAiSearchCategories(), existing.getAiSearchCategories(), 10))
                .aiMaxBudget(Math.max(safeDouble(existing.getAiMaxBudget()), safeDouble(incoming.getAiMaxBudget())))
                .aiProductSearches(safeAdd(existing.getAiProductSearches(), incoming.getAiProductSearches()))
                .aiProductComparisons(safeAdd(existing.getAiProductComparisons(), incoming.getAiProductComparisons()))

                // Order history - use incoming (lifetime totals from Flink)
                .totalOrders(incoming.getTotalOrders() != null ? incoming.getTotalOrders() : existing.getTotalOrders())
                .totalSpent(incoming.getTotalSpent() != null ? incoming.getTotalSpent() : existing.getTotalSpent())
                .avgOrderValue(incoming.getAvgOrderValue() != null ? incoming.getAvgOrderValue() : existing.getAvgOrderValue())
                .lastOrderTotal(incoming.getLastOrderTotal() != null ? incoming.getLastOrderTotal() : existing.getLastOrderTotal())
                .preferredPaymentMethod(incoming.getPreferredPaymentMethod() != null
                        ? incoming.getPreferredPaymentMethod()
                        : existing.getPreferredPaymentMethod())

                // Timestamp - always update
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private Long safeAdd(Long a, Long b) {
        return safeLong(a) + safeLong(b);
    }

    private Long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    private Double safeDouble(Double value) {
        return value != null ? value : 0.0;
    }

    /**
     * Merge two lists: prepend new items, remove duplicates, limit size.
     * New items (from incoming) are placed first to maintain recency.
     */
    private List<String> mergeLists(List<String> incoming, List<String> existing, int maxSize) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();

        // Add incoming items first (most recent)
        if (incoming != null) {
            incoming.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .forEach(merged::add);
        }

        // Add existing items (older)
        if (existing != null) {
            existing.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .forEach(merged::add);
        }

        // Limit size and return as list
        return merged.stream()
                .limit(maxSize)
                .collect(Collectors.toList());
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

    /**
     * Maps GenericRecord from Flink-produced Avro to UserProfile DTO.
     * Key contains: userId, sessionId (primary keys)
     * Value contains: all other fields
     */
    private UserProfile mapToUserProfile(GenericRecord key, GenericRecord value) {
        return UserProfile.builder()
                // From KEY (primary keys)
                .userId(getStringField(key, "userId"))
                .sessionId(getStringField(key, "sessionId"))
                // From VALUE (all other fields)
                // Product activity
                .recentProductIds(getStringList(value, "recentProductIds"))
                .recentCategories(getStringList(value, "recentCategories"))
                .recentSearchQueries(getStringList(value, "recentSearchQueries"))
                .totalProductViews(getLongField(value, "totalProductViews"))
                .avgViewDurationMs(getLongField(value, "avgViewDurationMs"))
                .avgProductPrice(getDoubleField(value, "avgProductPrice"))
                // Preferences
                .pricePreference(getStringField(value, "pricePreference"))
                // Cart state
                .currentCartTotal(getDoubleField(value, "currentCartTotal"))
                .currentCartItems(getLongField(value, "currentCartItems"))
                .cartAdds(getLongField(value, "cartAdds"))
                .cartProductIds(getStringList(value, "cartProductIds"))
                .cartCategories(getStringList(value, "cartCategories"))
                // Session info
                .deviceType(getStringField(value, "deviceType"))
                .sessionDurationMs(getLongField(value, "sessionDurationMs"))
                .totalPageViews(getLongField(value, "totalPageViews"))
                .productPageViews(getLongField(value, "productPageViews"))
                .cartPageViews(getLongField(value, "cartPageViews"))
                .checkoutPageViews(getLongField(value, "checkoutPageViews"))
                // AI intent signals
                .aiSearchCount(getLongField(value, "aiSearchCount"))
                .aiSearchQueries(getStringList(value, "aiSearchQueries"))
                .aiSearchCategories(getStringList(value, "aiSearchCategories"))
                .aiMaxBudget(getDoubleField(value, "aiMaxBudget"))
                .aiProductSearches(getLongField(value, "aiProductSearches"))
                .aiProductComparisons(getLongField(value, "aiProductComparisons"))
                // Order history (HIGHEST intent)
                .totalOrders(getLongField(value, "totalOrders"))
                .totalSpent(getDoubleField(value, "totalSpent"))
                .avgOrderValue(getDoubleField(value, "avgOrderValue"))
                .lastOrderTotal(getDoubleField(value, "lastOrderTotal"))
                .preferredPaymentMethod(getStringField(value, "preferredPaymentMethod"))
                // Metadata
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    /**
     * Safely get a String field from GenericRecord.
     * Returns null if field doesn't exist in schema.
     */
    private String getStringField(GenericRecord record, String fieldName) {
        try {
            Object value = record.get(fieldName);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            // Field doesn't exist in schema - return default
            return null;
        }
    }

    /**
     * Safely get a Long field from GenericRecord.
     * Returns 0L if field doesn't exist in schema.
     */
    private Long getLongField(GenericRecord record, String fieldName) {
        try {
            Object value = record.get(fieldName);
            if (value == null) return 0L;
            if (value instanceof Long) return (Long) value;
            if (value instanceof Number) return ((Number) value).longValue();
            return 0L;
        } catch (Exception e) {
            // Field doesn't exist in schema - return default
            return 0L;
        }
    }

    /**
     * Safely get a Double field from GenericRecord.
     * Returns 0.0 if field doesn't exist in schema.
     */
    private Double getDoubleField(GenericRecord record, String fieldName) {
        try {
            Object value = record.get(fieldName);
            if (value == null) return 0.0;
            if (value instanceof Double) return (Double) value;
            if (value instanceof Number) return ((Number) value).doubleValue();
            return 0.0;
        } catch (Exception e) {
            // Field doesn't exist in schema - return default
            return 0.0;
        }
    }

    /**
     * Safely get a List<String> field from GenericRecord.
     * Returns empty list if field doesn't exist in schema.
     */
    @SuppressWarnings("unchecked")
    private List<String> getStringList(GenericRecord record, String fieldName) {
        try {
            Object value = record.get(fieldName);
            if (value == null) return List.of();
            if (value instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    if (item != null) {
                        result.add(item.toString());
                    }
                }
                return result;
            }
            return List.of();
        } catch (Exception e) {
            // Field doesn't exist in schema - return default
            return List.of();
        }
    }
}
