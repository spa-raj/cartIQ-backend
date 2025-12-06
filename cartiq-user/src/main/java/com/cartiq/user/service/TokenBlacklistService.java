package com.cartiq.user.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token blacklist service.
 * Tokens are stored with their expiration time and automatically cleaned up.
 *
 * Note: This implementation resets on application restart.
 * For production, consider using Redis or a database.
 */
@Service
public class TokenBlacklistService {

    // Map of token -> expiration timestamp (epoch millis)
    private final Map<String, Long> blacklistedTokens = new ConcurrentHashMap<>();

    /**
     * Blacklist a token until its expiration time.
     *
     * @param token The JWT token to blacklist
     * @param expirationTime Expiration timestamp in milliseconds
     */
    public void blacklistToken(String token, long expirationTime) {
        blacklistedTokens.put(token, expirationTime);
    }

    /**
     * Check if a token is blacklisted.
     *
     * @param token The JWT token to check
     * @return true if the token is blacklisted, false otherwise
     */
    public boolean isBlacklisted(String token) {
        Long expirationTime = blacklistedTokens.get(token);
        if (expirationTime == null) {
            return false;
        }
        // If token has expired, remove it from blacklist and return false
        if (System.currentTimeMillis() > expirationTime) {
            blacklistedTokens.remove(token);
            return false;
        }
        return true;
    }

    /**
     * Cleanup expired tokens every 5 minutes.
     * This prevents memory leaks from accumulated expired tokens.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void cleanupExpiredTokens() {
        long now = Instant.now().toEpochMilli();
        blacklistedTokens.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    /**
     * Get the current number of blacklisted tokens.
     * Useful for monitoring.
     */
    public int getBlacklistSize() {
        return blacklistedTokens.size();
    }
}
