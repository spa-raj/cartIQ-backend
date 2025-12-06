package com.cartiq.user.service;

import com.cartiq.user.dto.*;
import com.cartiq.user.entity.User;
import com.cartiq.user.entity.UserPreference;
import com.cartiq.user.exception.UserException;
import com.cartiq.user.repository.UserPreferenceRepository;
import com.cartiq.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.getEmail().toLowerCase().trim();

        if (userRepository.existsByEmail(email)) {
            log.warn("Registration attempt with existing email: {}", email);
            throw UserException.emailAlreadyExists(email);
        }

        if (request.getPhone() != null && !request.getPhone().isBlank()
                && userRepository.existsByPhone(request.getPhone())) {
            log.warn("Registration attempt with existing phone");
            throw UserException.phoneAlreadyExists(request.getPhone());
        }

        User.Role role = request.getRole() != null ? request.getRole() : User.Role.USER;

        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .phone(request.getPhone() != null && !request.getPhone().isBlank()
                        ? request.getPhone().trim() : null)
                .role(role)
                .build();

        user = userRepository.save(user);

        // Create default preferences for new user and link bidirectionally
        UserPreference preference = UserPreference.builder()
                .user(user)
                .build();
        preference = preferenceRepository.save(preference);
        user.setPreference(preference);

        log.info("New user registered: userId={}", user.getId());

        String token = jwtService.generateToken(user);
        return AuthResponse.of(token, jwtService.getExpirationTime(), UserDTO.fromEntity(user));
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail().toLowerCase().trim();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("Login attempt with non-existent email: {}", email);
                    return UserException.invalidCredentials();
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("Failed login attempt for userId={}: invalid password", user.getId());
            throw UserException.invalidCredentials();
        }

        if (!user.getEnabled()) {
            log.warn("Login attempt for disabled account: userId={}", user.getId());
            throw UserException.accountDisabled();
        }

        log.info("User logged in: userId={}", user.getId());

        String token = jwtService.generateToken(user);
        return AuthResponse.of(token, jwtService.getExpirationTime(), UserDTO.fromEntity(user));
    }

    public void logout(String token) {
        try {
            long expirationTime = jwtService.extractExpiration(token).getTime();
            tokenBlacklistService.blacklistToken(token, expirationTime);
            String userId = jwtService.extractUserId(token);
            log.info("User logged out: userId={}", userId);
        } catch (Exception e) {
            log.warn("Logout attempted with invalid token");
        }
    }

    public UserDTO getUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> UserException.userNotFound(userId.toString()));
        return UserDTO.fromEntity(user);
    }

    public UserDTO getUserByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> UserException.userNotFound(email));
        return UserDTO.fromEntity(user);
    }

    @Transactional
    public UserDTO updateUser(UUID userId, UpdateUserRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> UserException.userNotFound(userId.toString()));

        if (request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }
        if (request.getPhone() != null) {
            // Convert empty string to null for clearing phone
            String phone = request.getPhone().isBlank() ? null : request.getPhone();
            if (phone != null && !phone.equals(user.getPhone()) && userRepository.existsByPhone(phone)) {
                throw UserException.phoneAlreadyExists(phone);
            }
            user.setPhone(phone);
        }

        user = userRepository.save(user);
        return UserDTO.fromEntity(user);
    }

    public UserPreferenceDTO getUserPreferences(UUID userId) {
        UserPreference preference = preferenceRepository.findByUserId(userId)
                .orElseThrow(UserException::preferencesNotFound);
        return UserPreferenceDTO.fromEntity(preference);
    }

    @Transactional
    public UserPreferenceDTO updateUserPreferences(UUID userId, UpdatePreferenceRequest request) {
        UserPreference preference = preferenceRepository.findByUserId(userId)
                .orElseThrow(UserException::preferencesNotFound);

        if (request.getMinPricePreference() != null) {
            preference.setMinPricePreference(request.getMinPricePreference());
        }
        if (request.getMaxPricePreference() != null) {
            preference.setMaxPricePreference(request.getMaxPricePreference());
        }

        // Validate min <= max price
        if (preference.getMinPricePreference() != null && preference.getMaxPricePreference() != null
                && preference.getMinPricePreference().compareTo(preference.getMaxPricePreference()) > 0) {
            throw UserException.invalidPriceRange();
        }

        if (request.getPreferredCategories() != null) {
            preference.setPreferredCategories(request.getPreferredCategories());
        }
        if (request.getPreferredBrands() != null) {
            preference.setPreferredBrands(request.getPreferredBrands());
        }
        if (request.getEmailNotifications() != null) {
            preference.setEmailNotifications(request.getEmailNotifications());
        }
        if (request.getPushNotifications() != null) {
            preference.setPushNotifications(request.getPushNotifications());
        }
        if (request.getCurrency() != null) {
            preference.setCurrency(request.getCurrency());
        }
        if (request.getLanguage() != null) {
            preference.setLanguage(request.getLanguage());
        }

        preference = preferenceRepository.save(preference);
        return UserPreferenceDTO.fromEntity(preference);
    }

    @Transactional
    public void deleteUser(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw UserException.userNotFound(userId.toString());
        }
        userRepository.deleteById(userId);
        log.info("User account deleted: userId={}", userId);
    }
}
