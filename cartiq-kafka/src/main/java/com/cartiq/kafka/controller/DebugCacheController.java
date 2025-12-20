package com.cartiq.kafka.controller;

import com.cartiq.kafka.dto.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Debug controller for inspecting Redis cached user profiles.
 * Protected by internal API key - same pattern as BatchIndexingController.
 */
@RestController
@RequestMapping("/api/internal/debug")
@RequiredArgsConstructor
@Slf4j
public class DebugCacheController {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${cartiq.internal.api-key:}")
    private String internalApiKey;

    @Value("${cartiq.suggestions.cache.prefix:user-profile:}")
    private String cachePrefix;

    /**
     * Retrieve a cached user profile by userId.
     *
     * Usage:
     * curl -H "X-Internal-Api-Key: your-key" \
     *      http://localhost:8080/api/internal/debug/user-profiles/{userId}
     */
    @GetMapping("/user-profiles/{userId}")
    public ResponseEntity<?> getUserProfile(
            @PathVariable String userId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {

        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        String cacheKey = cachePrefix + userId;
        log.debug("Looking up cache key: {}", cacheKey);

        Object cached = redisTemplate.opsForValue().get(cacheKey);

        if (cached == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "message", "User profile not found in cache",
                            "userId", userId,
                            "cacheKey", cacheKey
                    ));
        }

        log.info("Found cached profile for userId={}", userId);
        return ResponseEntity.ok(cached);
    }

    /**
     * List all cached user profile keys (for debugging).
     * Note: Use with caution in production - KEYS command can be slow.
     */
    @GetMapping("/user-profiles")
    public ResponseEntity<?> listCachedProfiles(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {

        if (!isValidApiKey(apiKey)) {
            return unauthorizedResponse();
        }

        var keys = redisTemplate.keys(cachePrefix + "*");

        return ResponseEntity.ok(Map.of(
                "prefix", cachePrefix,
                "count", keys != null ? keys.size() : 0,
                "keys", keys != null ? keys : java.util.Set.of()
        ));
    }

    private boolean isValidApiKey(String apiKey) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            log.warn("Internal API key not configured - rejecting request");
            return false;
        }
        return internalApiKey.equals(apiKey);
    }

    private ResponseEntity<?> unauthorizedResponse() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Invalid or missing API key"));
    }
}
