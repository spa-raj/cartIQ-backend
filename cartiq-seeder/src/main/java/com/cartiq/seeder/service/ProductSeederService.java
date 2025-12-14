package com.cartiq.seeder.service;

import com.cartiq.seeder.dto.AmazonProductJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for seeding products from Amazon LDJSON dataset.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductSeederService {

    private final ProductBatchService productBatchService;
    private final CategorySeederService categorySeederService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Seeds products from an LDJSON file (Line-Delimited JSON).
     * Each product is imported in its own transaction for reliability.
     */
    public int seedFromLdjson(Path ldjsonPath, int limit) {
        log.info("Starting product seeding from: {}", ldjsonPath);

        categorySeederService.preloadCache();
        log.info("Starting to process LDJSON lines...");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger lineCount = new AtomicInteger(0);

        try (BufferedReader reader = Files.newBufferedReader(ldjsonPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (limit > 0 && successCount.get() >= limit) {
                    log.info("Reached import limit of {}", limit);
                    break;
                }

                lineCount.incrementAndGet();

                if (line.isBlank()) {
                    continue;
                }

                try {
                    AmazonProductJson jsonProduct = objectMapper.readValue(line, AmazonProductJson.class);

                    if (!jsonProduct.isValid()) {
                        skipCount.incrementAndGet();
                        continue;
                    }

                    // Import in separate transaction
                    int result = productBatchService.importProductInTransaction(jsonProduct);
                    if (result == 1) {
                        successCount.incrementAndGet();
                    } else if (result == 0) {
                        skipCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    // Log first few errors at INFO level to diagnose
                    if (errorCount.get() <= 5) {
                        log.info("Parse error at line {}: {}", lineCount.get(), e.getMessage());
                    }
                }

                // Log progress every 100 lines
                if (lineCount.get() % 100 == 0) {
                    log.info("Progress: {} lines ({} imported, {} skipped, {} errors)",
                            lineCount.get(), successCount.get(), skipCount.get(), errorCount.get());
                }
            }
        } catch (Exception e) {
            log.error("Failed to read LDJSON file: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to seed products from LDJSON", e);
        }

        log.info("Seeding complete: {} imported, {} skipped, {} errors (from {} lines)",
                successCount.get(), skipCount.get(), errorCount.get(), lineCount.get());
        log.info("Categories created: {}", categorySeederService.getCacheSize());

        return successCount.get();
    }
}
