package com.cartiq.user.controller;

import com.cartiq.user.dto.AuthResponse;
import com.cartiq.user.dto.LoginRequest;
import com.cartiq.user.dto.RegisterRequest;
import com.cartiq.user.entity.User;
import com.cartiq.user.exception.UserException;
import com.cartiq.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        // If admin role is requested, verify caller is an authenticated admin
        if (request.getRole() == User.Role.ADMIN) {
            if (!isAuthenticatedAdmin()) {
                log.warn("Unauthorized attempt to create admin user: {}", request.getEmail());
                throw UserException.adminRequired();
            }
            log.info("Admin user creating new admin: {}", request.getEmail());
        }

        AuthResponse response = userService.register(request);
        return ResponseEntity.ok(response);
    }

    private boolean isAuthenticatedAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(auth -> auth.equals("ROLE_ADMIN"));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = userService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getCredentials() != null) {
            String token = (String) authentication.getCredentials();
            userService.logout(token);
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}
