package com.cartiq.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@SpringBootApplication(
        scanBasePackages = "com.cartiq",
        exclude = {UserDetailsServiceAutoConfiguration.class}
)
@EntityScan(basePackages = "com.cartiq")
@EnableJpaRepositories(basePackages = "com.cartiq")
@EnableJpaAuditing
@EnableScheduling
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class CartiqApplication {
    public static void main(String[] args) {
        SpringApplication.run(CartiqApplication.class, args);
    }
}
