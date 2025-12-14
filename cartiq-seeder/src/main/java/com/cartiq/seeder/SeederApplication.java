package com.cartiq.seeder;

import com.cartiq.seeder.service.ProductSeederServiceFast;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Standalone application for seeding the CartIQ database with sample data.
 *
 * Usage:
 *   mvn spring-boot:run -pl cartiq-seeder
 *
 *   # With environment variables
 *   SEEDER_LDJSON_OFFSET=15000 mvn spring-boot:run -pl cartiq-seeder
 *
 * Dataset Format:
 *   LDJSON (Line-Delimited JSON) - one JSON object per line
 */
@SpringBootApplication(
    scanBasePackages = {"com.cartiq.seeder", "com.cartiq.product", "com.cartiq.common"},
    exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class}
)
@EntityScan(basePackages = "com.cartiq.product")
@EnableJpaRepositories(basePackages = "com.cartiq.product")
@RequiredArgsConstructor
@Slf4j
public class SeederApplication implements CommandLineRunner {

    private final ProductSeederServiceFast productSeederService;

    @Value("${seeder.ldjson.path}")
    private String ldjsonPath;

    @Value("${seeder.ldjson.limit:0}")
    private int limit;

    @Value("${seeder.ldjson.offset:0}")
    private int offset;

    public static void main(String[] args) {
        SpringApplication.run(SeederApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        if (ldjsonPath == null || ldjsonPath.isBlank()) {
            log.error("LDJSON file path is required. Set seeder.ldjson.path in application.properties or SEEDER_LDJSON_PATH env var.");
            printUsage();
            System.exit(1);
        }

        Path path = Paths.get(ldjsonPath);
        if (!path.toFile().exists()) {
            log.error("LDJSON file not found: {}", ldjsonPath);
            System.exit(1);
        }

        log.info("=".repeat(60));
        log.info("CartIQ Data Seeder");
        log.info("=".repeat(60));
        log.info("LDJSON File: {}", ldjsonPath);
        log.info("Offset: {} (skipping first {} lines)", offset, offset);
        log.info("Limit: {}", limit > 0 ? limit : "all");
        log.info("=".repeat(60));

        long startTime = System.currentTimeMillis();

        int imported = productSeederService.seedFromLdjsonFast(path, offset, limit);

        long duration = System.currentTimeMillis() - startTime;
        double rate = duration > 0 ? (imported / (duration / 1000.0)) : 0;

        log.info("=".repeat(60));
        log.info("Seeding completed!");
        log.info("Products imported: {}", imported);
        log.info("Time taken: {} seconds", String.format("%.1f", duration / 1000.0));
        log.info("Rate: {} records/second", String.format("%.1f", rate));
        log.info("=".repeat(60));
    }

    private void printUsage() {
        System.out.println("""

            CartIQ Data Seeder - Usage
            ==========================

            Configuration (application.properties):
              seeder.ldjson.path    Path to the LDJSON file
              seeder.ldjson.limit   Max products to import (0 = all)
              seeder.ldjson.offset  Lines to skip from beginning (for resuming)

            Environment Variables:
              SEEDER_LDJSON_PATH    Path to LDJSON file
              SEEDER_LDJSON_LIMIT   Max products (default: 0)
              SEEDER_LDJSON_OFFSET  Lines to skip (default: 0)

            Examples:
              # Using defaults from application.properties
              mvn spring-boot:run -pl cartiq-seeder

              # Resume from line 15000 (skip first 15000 lines)
              SEEDER_LDJSON_OFFSET=15000 mvn spring-boot:run -pl cartiq-seeder
            """);
    }
}
