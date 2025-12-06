package com.cartiq.user.config;

import com.cartiq.user.service.JwtService;
import com.cartiq.user.service.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();

        // Skip filter for login only (register needs filter to check for admin token)
        if (path.equals("/api/auth/login")) {
            return true;
        }

        return path.startsWith("/api/events/") ||
               path.startsWith("/actuator/") ||
               path.startsWith("/h2-console/") ||
               path.equals("/error") ||
               (method.equals("GET") && (path.startsWith("/api/products/") || path.startsWith("/api/categories/")));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            final String jwt = authHeader.substring(7);

            // Check if token is blacklisted
            if (tokenBlacklistService.isBlacklisted(jwt)) {
                log.warn("Attempt to use blacklisted token");
                sendUnauthorizedResponse(response, "Token has been revoked");
                return;
            }

            final String userId = jwtService.extractUserId(jwt);
            final String role = jwtService.extractRole(jwt);

            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                if (jwtService.validateToken(jwt, userId)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userId,
                            jwt, // Store JWT as credentials for logout
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            log.error("JWT authentication failed: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private void sendUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write(String.format(
            "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\",\"errorCode\":\"TOKEN_REVOKED\"}",
            message
        ));
    }
}
