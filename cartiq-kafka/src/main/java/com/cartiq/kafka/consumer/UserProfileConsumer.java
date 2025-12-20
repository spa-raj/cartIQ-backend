package com.cartiq.kafka.consumer;

import com.cartiq.kafka.dto.KafkaEvents.UserProfileUpdateEvent;
import com.cartiq.kafka.dto.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.LocalDateTime;
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
    public void consumeUserProfile(UserProfileUpdateEvent event) {
        try {
            if (event.getUserId() == null || event.getUserId().isBlank()) {
                log.warn("Received user profile event with null userId, skipping");
                return;
            }

            String cacheKey = cachePrefix + event.getUserId();
            UserProfile profile = mapToUserProfile(event);

            redisTemplate.opsForValue().set(
                    cacheKey,
                    profile,
                    Duration.ofHours(cacheTtlHours)
            );

            log.info("Cached user profile: userId={}, categories={}, pricePreference={}, aiSearchCount={}",
                    event.getUserId(),
                    event.getRecentCategories(),
                    event.getPricePreference(),
                    event.getAiSearchCount()
            );

        } catch (Exception e) {
            log.error("Failed to process user profile event for userId={}: {}",
                    event != null ? event.getUserId() : "null",
                    e.getMessage(),
                    e
            );
        }
    }

    private UserProfile mapToUserProfile(UserProfileUpdateEvent event) {
        return UserProfile.builder()
                .userId(event.getUserId())
                .sessionId(event.getSessionId())
                // Product activity
                .recentProductIds(nullSafeList(event.getRecentProductIds()))
                .recentCategories(nullSafeList(event.getRecentCategories()))
                .recentSearchQueries(nullSafeList(event.getRecentSearchQueries()))
                .totalProductViews(event.getTotalProductViews())
                .avgViewDurationMs(event.getAvgViewDurationMs())
                .avgProductPrice(event.getAvgProductPrice())
                // Cart state
                .totalCartAdds(event.getTotalCartAdds())
                .currentCartTotal(event.getCurrentCartTotal())
                .currentCartItems(event.getCurrentCartItems())
                // Session info
                .deviceType(event.getDeviceType())
                .sessionDurationMs(event.getSessionDurationMs())
                // Preferences
                .pricePreference(event.getPricePreference())
                // AI intent signals
                .aiSearchCount(event.getAiSearchCount())
                .aiSearchQueries(nullSafeList(event.getAiSearchQueries()))
                .aiSearchCategories(nullSafeList(event.getAiSearchCategories()))
                .aiMaxBudget(event.getAiMaxBudget())
                .aiProductSearches(event.getAiProductSearches())
                .aiProductComparisons(event.getAiProductComparisons())
                // Metadata
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private List<String> nullSafeList(List<String> list) {
        return list != null ? list : List.of();
    }
}
