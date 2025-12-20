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
import java.util.List;

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
            UserProfile profile = mapToUserProfile(key, value);

            redisTemplate.opsForValue().set(
                    cacheKey,
                    profile,
                    Duration.ofHours(cacheTtlHours)
            );

            log.info("Cached user profile: userId={}, categories={}, pricePreference={}, aiSearchCount={}",
                    profile.getUserId(),
                    profile.getRecentCategories(),
                    profile.getPricePreference(),
                    profile.getAiSearchCount()
            );

        } catch (Exception e) {
            log.error("Failed to process user profile event: {}",
                    e.getMessage(),
                    e
            );
        }
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
