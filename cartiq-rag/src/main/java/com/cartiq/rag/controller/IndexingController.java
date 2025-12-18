package com.cartiq.rag.controller;

import com.cartiq.product.dto.ProductDTO;
import com.cartiq.product.service.ProductService;
import com.cartiq.rag.service.ProductIndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Internal controller for managing Vector Search index.
 * Protected by API key - only accessible from CI/CD workflows.
 *
 * Endpoints:
 * - POST /api/internal/indexing/products - Trigger full reindex
 * - POST /api/internal/indexing/products/{id} - Index single product
 * - GET /api/internal/indexing/status - Check service status
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/indexing")
@RequiredArgsConstructor
public class IndexingController {

    private final ProductIndexingService productIndexingService;
    private final ProductService productService;

    @Value("${cartiq.internal.api-key:}")
    private String internalApiKey;

    /**
     * Trigger full re-indexing of all products.
     * This is a long-running operation - returns immediately with status.
     *
     * POST /api/internal/indexing/products
     */
    @PostMapping("/products")
    public ResponseEntity<Map<String, Object>> indexAllProducts(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {

        if (!isValidApiKey(apiKey)) {
            log.warn("Unauthorized indexing attempt - invalid API key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "message", "Invalid or missing API key"
            ));
        }

        log.info("CI/CD triggered full product indexing");

        if (!productIndexingService.isAvailable()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Vector Search is not available. Check configuration."
            ));
        }

        // Run indexing asynchronously using the service's async method
        productIndexingService.indexAllProductsAsync();

        return ResponseEntity.accepted().body(Map.of(
                "success", true,
                "message", "Product indexing started in background. Check logs for progress."
        ));
    }

    /**
     * Index a single product.
     *
     * POST /api/internal/indexing/products/{productId}
     */
    @PostMapping("/products/{productId}")
    public ResponseEntity<Map<String, Object>> indexProduct(
            @PathVariable UUID productId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {

        if (!isValidApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "message", "Invalid or missing API key"
            ));
        }

        log.info("CI/CD triggered indexing for product: {}", productId);

        if (!productIndexingService.isAvailable()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Vector Search is not available. Check configuration."
            ));
        }

        // Fetch the product
        ProductDTO product = productService.getProductById(productId);
        if (product == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Product not found",
                    "productId", productId.toString()
            ));
        }

        boolean success = productIndexingService.indexProduct(product);

        if (success) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Product indexed successfully",
                    "productId", productId.toString()
            ));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Failed to index product. Check logs for details.",
                    "productId", productId.toString()
            ));
        }
    }

    /**
     * Check indexing service status.
     *
     * GET /api/internal/indexing/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {

        if (!isValidApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "message", "Invalid or missing API key"
            ));
        }

        boolean available = productIndexingService.isAvailable();
        boolean indexingInProgress = productIndexingService.isIndexingInProgress();

        return ResponseEntity.ok(Map.of(
                "vectorSearchAvailable", available,
                "indexingInProgress", indexingInProgress,
                "message", available
                        ? "Vector Search is configured and ready"
                        : "Vector Search is not available. Check VECTOR_SEARCH_* environment variables."
        ));
    }

    /**
     * Validate the internal API key.
     */
    private boolean isValidApiKey(String apiKey) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            log.warn("Internal API key not configured - rejecting request");
            return false;
        }
        return internalApiKey.equals(apiKey);
    }
}
