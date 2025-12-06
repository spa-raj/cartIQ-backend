package com.cartiq.user.config;

import com.cartiq.user.entity.User;
import com.cartiq.user.entity.UserPreference;
import com.cartiq.user.repository.UserPreferenceRepository;
import com.cartiq.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Value("${app.admin.first-name:Admin}")
    private String adminFirstName;

    @Value("${app.admin.last-name:User}")
    private String adminLastName;

    @Override
    @Transactional
    public void run(String... args) {
        if (!shouldCreateAdmin()) {
            return;
        }

        if (userRepository.existsByEmail(adminEmail)) {
            log.info("Admin user already exists with email: {}", adminEmail);
            return;
        }

        createAdminUser();
    }

    private static final String PASSWORD_PATTERN =
        "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$";

    private boolean shouldCreateAdmin() {
        if (adminEmail == null || adminEmail.isBlank()) {
            log.debug("ADMIN_EMAIL not set - skipping admin initialization");
            return false;
        }

        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("ADMIN_EMAIL is set but ADMIN_PASSWORD is empty - skipping admin initialization");
            return false;
        }

        if (adminPassword.length() < 8) {
            log.warn("ADMIN_PASSWORD must be at least 8 characters - skipping admin initialization");
            return false;
        }

        if (!adminPassword.matches(PASSWORD_PATTERN)) {
            log.warn("ADMIN_PASSWORD must contain uppercase, lowercase, digit, and special character (@$!%*?&) - skipping admin initialization");
            return false;
        }

        return true;
    }

    private void createAdminUser() {
        User admin = User.builder()
                .email(adminEmail)
                .password(passwordEncoder.encode(adminPassword))
                .firstName(adminFirstName)
                .lastName(adminLastName)
                .role(User.Role.ADMIN)
                .enabled(true)
                .build();

        admin = userRepository.save(admin);

        // Create default preferences for admin and link bidirectionally
        UserPreference preference = UserPreference.builder()
                .user(admin)
                .build();
        preference = preferenceRepository.save(preference);
        admin.setPreference(preference);

        log.info("==============================================");
        log.info("Admin user created successfully!");
        log.info("Email: {}", adminEmail);
        log.info("Role: ADMIN");
        log.info("==============================================");
    }
}
