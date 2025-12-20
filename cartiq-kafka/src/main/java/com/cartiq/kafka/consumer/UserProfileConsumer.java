package com.cartiq.kafka.consumer;

import com.cartiq.kafka.dto.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.generic.GenericRecord;
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

    @KafkaListener(
            topics = "user-profiles",
            groupId = "cartiq-suggestions-consumer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeUserProfile(GenericRecord record) {
        try {
            String userId = getStringField(record, "userId");
            if (userId == null || userId.isBlank()) {
                log.warn("Received user profile event with null userId, skipping");
                return;
            }

            String cacheKey = cachePrefix + userId;
            UserProfile profile = mapToUserProfile(record);

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
     * Field names match the Flink SQL schema in docs/flink-sql/02-user-profiles.sql
     */
    private UserProfile mapToUserProfile(GenericRecord record) {
        return UserProfile.builder()
                .userId(getStringField(record, "userId"))
                .sessionId(getStringField(record, "sessionId"))
                // Product activity
                .recentProductIds(getStringList(record, "recentProductIds"))
                .recentCategories(getStringList(record, "recentCategories"))
                .recentSearchQueries(getStringList(record, "recentSearchQueries"))
                .totalProductViews(getLongField(record, "totalProductViews"))
                .avgViewDurationMs(getLongField(record, "avgViewDurationMs"))
                .avgProductPrice(getDoubleField(record, "avgProductPrice"))
                // Cart state
                .totalCartAdds(getLongField(record, "totalCartAdds"))
                .currentCartTotal(getDoubleField(record, "currentCartTotal"))
                .currentCartItems(getLongField(record, "currentCartItems"))
                // Session info
                .deviceType(getStringField(record, "deviceType"))
                .sessionDurationMs(getLongField(record, "sessionDurationMs"))
                // Preferences
                .pricePreference(getStringField(record, "pricePreference"))
                // AI intent signals
                .aiSearchCount(getLongField(record, "aiSearchCount"))
                .aiSearchQueries(getStringList(record, "aiSearchQueries"))
                .aiSearchCategories(getStringList(record, "aiSearchCategories"))
                .aiMaxBudget(getDoubleField(record, "aiMaxBudget"))
                .aiProductSearches(getLongField(record, "aiProductSearches"))
                .aiProductComparisons(getLongField(record, "aiProductComparisons"))
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
