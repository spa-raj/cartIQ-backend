package com.cartiq.user.controller;

import com.cartiq.user.dto.*;
import com.cartiq.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserDTO> getCurrentUser() {
        UUID userId = getCurrentUserId();
        UserDTO user = userService.getUserById(userId);
        return ResponseEntity.ok(user);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserDTO> getUserById(@PathVariable UUID userId) {
        UserDTO user = userService.getUserById(userId);
        return ResponseEntity.ok(user);
    }

    @PutMapping("/me")
    public ResponseEntity<UserDTO> updateCurrentUser(@Valid @RequestBody UpdateUserRequest request) {
        UUID userId = getCurrentUserId();
        UserDTO user = userService.updateUser(userId, request);
        return ResponseEntity.ok(user);
    }

    @GetMapping("/me/preferences")
    public ResponseEntity<UserPreferenceDTO> getCurrentUserPreferences() {
        UUID userId = getCurrentUserId();
        UserPreferenceDTO preferences = userService.getUserPreferences(userId);
        return ResponseEntity.ok(preferences);
    }

    @PutMapping("/me/preferences")
    public ResponseEntity<UserPreferenceDTO> updateCurrentUserPreferences(
            @Valid @RequestBody UpdatePreferenceRequest request) {
        UUID userId = getCurrentUserId();
        UserPreferenceDTO preferences = userService.updateUserPreferences(userId, request);
        return ResponseEntity.ok(preferences);
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteCurrentUser() {
        UUID userId = getCurrentUserId();
        userService.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString((String) authentication.getPrincipal());
    }
}
