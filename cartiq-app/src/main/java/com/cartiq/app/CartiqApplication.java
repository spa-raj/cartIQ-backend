package com.cartiq.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
        scanBasePackages = "com.cartiq",
        exclude = {UserDetailsServiceAutoConfiguration.class}
)
@EntityScan(basePackages = "com.cartiq")
@EnableJpaRepositories(basePackages = "com.cartiq")
@EnableJpaAuditing
@EnableScheduling
public class CartiqApplication {
    public static void main(String[] args) {
        SpringApplication.run(CartiqApplication.class, args);
    }
}
