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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FAST version of ProductSeederService.
 * Uses batching + multi-threading for ~50-100x speedup.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductSeederServiceFast {

    private final ProductBatchServiceFast productBatchService;
    private final CategorySeederService categorySeederService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Tuning parameters - keep threads <= (pool size - 5) to leave room for category transactions
    private static final int BATCH_SIZE = 500;
    private static final int THREAD_COUNT = 8;

    /**
     * Fast seeding with batching and parallel processing.
     * Expected: 1000-2000 records/second (vs ~1/second before)
     *
     * @param ldjsonPath Path to the LDJSON file
     * @param offset Number of lines to skip from the beginning
     * @param limit Maximum lines to process (0 = all)
     */
    public int seedFromLdjsonFast(Path ldjsonPath, int offset, int limit) {
        log.info("🚀 Starting FAST product seeding from: {}", ldjsonPath);
        log.info("Config: batch_size={}, threads={}, offset={}, limit={}", BATCH_SIZE, THREAD_COUNT, offset, limit > 0 ? limit : "all");

        // Preload category cache
        categorySeederService.preloadCache();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger lineCount = new AtomicInteger(0);
        AtomicInteger processedCount = new AtomicInteger(0);

        // Thread pool for parallel batch processing
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        List<Future<BatchResult>> futures = new ArrayList<>();

        List<AmazonProductJson> currentBatch = new ArrayList<>(BATCH_SIZE);

        long startTime = System.currentTimeMillis();

        try (BufferedReader reader = Files.newBufferedReader(ldjsonPath, StandardCharsets.UTF_8)) {
            String line;

            // Skip offset lines
            if (offset > 0) {
                log.info("Skipping first {} lines...", offset);
                for (int i = 0; i < offset && (line = reader.readLine()) != null; i++) {
                    // Just skip
                }
                log.info("Skipped {} lines, starting processing", offset);
            }

            while ((line = reader.readLine()) != null) {
                if (limit > 0 && processedCount.get() >= limit) {
                    log.info("Reached limit of {} lines", limit);
                    break;
                }

                lineCount.incrementAndGet();
                processedCount.incrementAndGet();

                if (line.isBlank()) {
                    continue;
                }

                try {
                    AmazonProductJson jsonProduct = objectMapper.readValue(line, AmazonProductJson.class);

                    if (!jsonProduct.isValid()) {
                        skipCount.incrementAndGet();
                        continue;
                    }

                    currentBatch.add(jsonProduct);

                    // When batch is full, submit for parallel processing
                    if (currentBatch.size() >= BATCH_SIZE) {
                        List<AmazonProductJson> batchToProcess = new ArrayList<>(currentBatch);
                        futures.add(executor.submit(() -> productBatchService.importBatch(batchToProcess)));
                        currentBatch.clear();

                        // Log progress
                        logProgress(lineCount.get(), successCount.get(), skipCount.get(), errorCount.get(), startTime);
                    }

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    if (errorCount.get() <= 3) {
                        log.warn("Parse error at line {}: {}", lineCount.get(), e.getMessage());
                    }
                }
            }

            // Process remaining batch
            if (!currentBatch.isEmpty()) {
                futures.add(executor.submit(() -> productBatchService.importBatch(currentBatch)));
            }

            // Wait for all batches to complete
            log.info("Waiting for {} batches to complete...", futures.size());
            for (Future<BatchResult> future : futures) {
                try {
                    BatchResult result = future.get(5, TimeUnit.MINUTES);
                    successCount.addAndGet(result.success());
                    skipCount.addAndGet(result.skipped());
                    errorCount.addAndGet(result.errors());
                } catch (Exception e) {
                    log.error("Batch processing failed: {}", e.getMessage());
                    errorCount.addAndGet(BATCH_SIZE); // Estimate
                }
            }

        } catch (Exception e) {
            log.error("Failed to read LDJSON file: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to seed products", e);
        } finally {
            executor.shutdown();
            try {
                executor.awaitTermination(10, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long duration = (System.currentTimeMillis() - startTime) / 1000;
        double rate = duration > 0 ? (double) successCount.get() / duration : 0;

        log.info("✅ Seeding complete in {}s", duration);
        log.info("   {} imported, {} skipped, {} errors", successCount.get(), skipCount.get(), errorCount.get());
        log.info("   Rate: {} records/second", String.format("%.1f", rate));

        return successCount.get();
    }

    private void logProgress(int lines, int success, int skipped, int errors, long startTime) {
        if (lines % 5000 == 0) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            double rate = elapsed > 0 ? (double) success / elapsed : 0;
            log.info("Progress: {} lines | {} imported | {} rec/sec", lines, success, String.format("%.1f", rate));
        }
    }

    public record BatchResult(int success, int skipped, int errors) {}
}
