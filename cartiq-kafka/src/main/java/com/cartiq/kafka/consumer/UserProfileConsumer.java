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
                .recentProductIds((List<String>) map.get("recentProductIds"))
                .recentCategories((List<String>) map.get("recentCategories"))
                .recentSearchQueries((List<String>) map.get("recentSearchQueries"))
                .totalProductViews(toLong(map.get("totalProductViews")))
                .avgViewDurationMs(toLong(map.get("avgViewDurationMs")))
                .avgProductPrice(toDouble(map.get("avgProductPrice")))
                .totalCartAdds(toLong(map.get("totalCartAdds")))
                .currentCartTotal(toDouble(map.get("currentCartTotal")))
                .currentCartItems(toLong(map.get("currentCartItems")))
                .deviceType((String) map.get("deviceType"))
                .sessionDurationMs(toLong(map.get("sessionDurationMs")))
                .pricePreference((String) map.get("pricePreference"))
                .aiSearchCount(toLong(map.get("aiSearchCount")))
                .aiSearchQueries((List<String>) map.get("aiSearchQueries"))
                .aiSearchCategories((List<String>) map.get("aiSearchCategories"))
                .aiMaxBudget(toDouble(map.get("aiMaxBudget")))
                .aiProductSearches(toLong(map.get("aiProductSearches")))
                .aiProductComparisons(toLong(map.get("aiProductComparisons")))
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
                .totalProductViews(existing.getTotalProductViews() + incoming.getTotalProductViews())
                .totalCartAdds(existing.getTotalCartAdds() + incoming.getTotalCartAdds())

                // Averages - use incoming (latest window's average)
                .avgViewDurationMs(incoming.getAvgViewDurationMs())
                .avgProductPrice(incoming.getAvgProductPrice() > 0 ? incoming.getAvgProductPrice() : existing.getAvgProductPrice())

                // Cart state - use incoming (current state)
                .currentCartTotal(incoming.getCurrentCartTotal())
                .currentCartItems(incoming.getCurrentCartItems())

                // Session info - use incoming (latest)
                .deviceType(incoming.getDeviceType() != null ? incoming.getDeviceType() : existing.getDeviceType())
                .sessionDurationMs(Math.max(existing.getSessionDurationMs(), incoming.getSessionDurationMs()))

                // Price preference - use incoming if set, else existing
                .pricePreference(incoming.getPricePreference() != null && !incoming.getPricePreference().equals("UNKNOWN")
                        ? incoming.getPricePreference()
                        : existing.getPricePreference())

                // AI intent signals - accumulate and merge
                .aiSearchCount(existing.getAiSearchCount() + incoming.getAiSearchCount())
                .aiSearchQueries(mergeLists(incoming.getAiSearchQueries(), existing.getAiSearchQueries(), 15))
                .aiSearchCategories(mergeLists(incoming.getAiSearchCategories(), existing.getAiSearchCategories(), 10))
                .aiMaxBudget(Math.max(existing.getAiMaxBudget(), incoming.getAiMaxBudget()))
                .aiProductSearches(existing.getAiProductSearches() + incoming.getAiProductSearches())
                .aiProductComparisons(existing.getAiProductComparisons() + incoming.getAiProductComparisons())

                // Timestamp - always update
                .lastUpdated(LocalDateTime.now())
                .build();
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
     * Key contains: userId, sessionId, windowBucket
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
                // Cart state
                .totalCartAdds(getLongField(value, "totalCartAdds"))
                .currentCartTotal(getDoubleField(value, "currentCartTotal"))
                .currentCartItems(getLongField(value, "currentCartItems"))
                // Session info
                .deviceType(getStringField(value, "deviceType"))
                .sessionDurationMs(getLongField(value, "sessionDurationMs"))
                // Preferences
                .pricePreference(getStringField(value, "pricePreference"))
                // AI intent signals
                .aiSearchCount(getLongField(value, "aiSearchCount"))
                .aiSearchQueries(getStringList(value, "aiSearchQueries"))
                .aiSearchCategories(getStringList(value, "aiSearchCategories"))
                .aiMaxBudget(getDoubleField(value, "aiMaxBudget"))
                .aiProductSearches(getLongField(value, "aiProductSearches"))
                .aiProductComparisons(getLongField(value, "aiProductComparisons"))
                // Metadata
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private String getStringField(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        return value != null ? value.toString() : null;
    }

    private Long getLongField(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        if (value == null) return 0L;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        return 0L;
    }

    private Double getDoubleField(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        if (value == null) return 0.0;
        if (value instanceof Double) return (Double) value;
        if (value instanceof Number) return ((Number) value).doubleValue();
        return 0.0;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(GenericRecord record, String fieldName) {
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
    }
}
