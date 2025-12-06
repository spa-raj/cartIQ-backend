package com.cartiq.user.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory rate limiting filter for auth and chat endpoints.
 * Uses a sliding window approach with per-IP tracking.
 *
 * Limits:
 * - Auth endpoints (/api/auth/login, /api/auth/register): 10 requests per minute
 * - Chat endpoints (/api/chat/**): 30 requests per minute
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final int AUTH_RATE_LIMIT = 10;  // requests per minute
    private static final int CHAT_RATE_LIMIT = 30;  // requests per minute
    private static final long WINDOW_SIZE_MS = 60_000; // 1 minute

    private final Map<String, RateLimitEntry> authRateLimits = new ConcurrentHashMap<>();
    private final Map<String, RateLimitEntry> chatRateLimits = new ConcurrentHashMap<>();

    /**
     * Cleanup expired rate limit entries every 5 minutes to prevent memory leaks.
     */
    @Scheduled(fixedRate = 300_000)
    public void cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        authRateLimits.entrySet().removeIf(entry -> now - entry.getValue().windowStart > WINDOW_SIZE_MS);
        chatRateLimits.entrySet().removeIf(entry -> now - entry.getValue().windowStart > WINDOW_SIZE_MS);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // Only apply to auth and chat endpoints
        return !path.startsWith("/api/auth/login")
            && !path.startsWith("/api/auth/register")
            && !path.startsWith("/api/chat/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String clientIp = getClientIp(request);
        String path = request.getServletPath();

        boolean isAuthEndpoint = path.startsWith("/api/auth/");
        Map<String, RateLimitEntry> rateLimits = isAuthEndpoint ? authRateLimits : chatRateLimits;
        int limit = isAuthEndpoint ? AUTH_RATE_LIMIT : CHAT_RATE_LIMIT;

        if (!isAllowed(clientIp, rateLimits, limit)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Please try again later.\",\"errorCode\":\"RATE_LIMIT_EXCEEDED\"}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAllowed(String clientIp, Map<String, RateLimitEntry> rateLimits, int limit) {
        long now = System.currentTimeMillis();

        RateLimitEntry entry = rateLimits.compute(clientIp, (key, existing) -> {
            if (existing == null || now - existing.windowStart > WINDOW_SIZE_MS) {
                // Start new window
                return new RateLimitEntry(now, new AtomicInteger(1));
            }
            // Increment counter in current window
            existing.count.incrementAndGet();
            return existing;
        });

        return entry.count.get() <= limit;
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // Get the first IP in the chain (original client)
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static class RateLimitEntry {
        final long windowStart;
        final AtomicInteger count;

        RateLimitEntry(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
