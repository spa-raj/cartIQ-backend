package com.cartiq.ai.service;

import com.cartiq.product.dto.CategoryDTO;
import com.cartiq.product.dto.ProductDTO;
import com.cartiq.product.service.CategoryService;
import com.cartiq.product.service.ProductService;
import com.cartiq.rag.dto.SearchResult;
import com.cartiq.rag.service.VectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service that provides tool functions for Gemini function calling.
 * These methods are invoked by Gemini when it determines a tool call is needed.
 *
 * Uses hybrid search: Vector Search for semantic queries + PostgreSQL for filters.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductToolService {

    private final ProductService productService;
    private final CategoryService categoryService;
    private final VectorSearchService vectorSearchService;

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int VECTOR_SEARCH_CANDIDATES = 50;

    /**
     * Search products with filters. Called by Gemini for queries like:
     * - "Show me laptops under Rs.50000"
     * - "Find headphones with rating above 4"
     * - "Electronics products"
     *
     * @param query Search text (product name, description, brand)
     * @param category Category name to filter by
     * @param minPrice Minimum price filter
     * @param maxPrice Maximum price filter
     * @param minRating Minimum rating filter (0-5)
     * @return JSON string of matching products
     */
    public static String searchProducts(
            String query,
            String category,
            Double minPrice,
            Double maxPrice,
            Double minRating) {

        log.info("Tool: searchProducts called with query={}, category={}, minPrice={}, maxPrice={}, minRating={}",
                query, category, minPrice, maxPrice, minRating);

        // This static method will be replaced by instance method call via wrapper
        return "[]"; // Placeholder - actual implementation uses instance method
    }

    /**
     * Instance method that performs the actual search.
     *
     * Uses HYBRID search strategy:
     * 1. If vector search is available → semantic search + metadata filters
     * 2. Fallback to PostgreSQL FTS + filters
     */
    public List<ProductDTO> executeSearchProducts(
            String query,
            String category,
            Double minPrice,
            Double maxPrice,
            Double minRating) {

        log.info("Executing searchProducts: query={}, category={}, minPrice={}, maxPrice={}, minRating={}",
                query, category, minPrice, maxPrice, minRating);

        // Try vector search first for semantic queries
        if (query != null && !query.isBlank() && vectorSearchService.isAvailable()) {
            List<ProductDTO> vectorResults = executeVectorSearch(query, category, minPrice, maxPrice, minRating);
            if (!vectorResults.isEmpty()) {
                log.info("Vector search returned {} products", vectorResults.size());
                return vectorResults;
            }
            log.debug("Vector search returned no results, falling back to FTS");
        }

        // Fallback to PostgreSQL FTS/LIKE search
        return executeFtsSearch(query, category, minPrice, maxPrice, minRating);
    }

    /**
     * Semantic search using Vertex AI Vector Search.
     * Returns products ranked by semantic similarity to the query.
     */
    private List<ProductDTO> executeVectorSearch(
            String query,
            String category,
            Double minPrice,
            Double maxPrice,
            Double minRating) {

        // Build metadata filters for vector search
        Map<String, Object> filters = new HashMap<>();
        if (minPrice != null) filters.put("minPrice", minPrice);
        if (maxPrice != null) filters.put("maxPrice", maxPrice);
        if (minRating != null) filters.put("minRating", minRating);

        // Find category ID if category name is provided
        if (category != null && !category.isBlank()) {
            UUID categoryId = findCategoryIdByName(category);
            if (categoryId != null) {
                filters.put("categoryId", categoryId.toString());
            }
        }

        // Execute vector search
        List<SearchResult> searchResults = vectorSearchService.search(
                query,
                VECTOR_SEARCH_CANDIDATES,
                filters.isEmpty() ? null : filters
        );

        if (searchResults.isEmpty()) {
            return List.of();
        }

        // Extract product IDs from search results
        List<UUID> productIds = searchResults.stream()
                .map(sr -> {
                    try {
                        return UUID.fromString(sr.getProductId());
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .limit(DEFAULT_PAGE_SIZE)
                .toList();

        if (productIds.isEmpty()) {
            return List.of();
        }

        // Fetch full product details from PostgreSQL
        List<ProductDTO> products = productService.getProductsByIds(productIds);

        // Preserve vector search ordering (by similarity score)
        Map<UUID, ProductDTO> productMap = products.stream()
                .collect(Collectors.toMap(ProductDTO::getId, p -> p));

        return productIds.stream()
                .map(productMap::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Full-text search using PostgreSQL.
     * Fallback when vector search is unavailable or returns no results.
     */
    private List<ProductDTO> executeFtsSearch(
            String query,
            String category,
            Double minPrice,
            Double maxPrice,
            Double minRating) {

        Pageable pageable = PageRequest.of(0, DEFAULT_PAGE_SIZE);
        Page<ProductDTO> results;

        // Convert Double to BigDecimal for service calls
        BigDecimal minPriceBD = minPrice != null ? BigDecimal.valueOf(minPrice) : null;
        BigDecimal maxPriceBD = maxPrice != null ? BigDecimal.valueOf(maxPrice) : null;
        BigDecimal minRatingBD = minRating != null ? BigDecimal.valueOf(minRating) : null;

        // Priority: text query > category > price-only > featured
        if (query != null && !query.isBlank()) {
            results = productService.searchWithFilters(query, minPriceBD, maxPriceBD, minRatingBD, pageable);
            log.debug("FTS search for '{}' returned {} results", query, results.getTotalElements());
        } else if (category != null && !category.isBlank()) {
            UUID categoryId = findCategoryIdByName(category);
            if (categoryId != null) {
                results = productService.getProductsByCategoryWithFilters(
                        categoryId, minPriceBD, maxPriceBD, minRatingBD, pageable);
            } else {
                results = productService.searchWithFilters(category, minPriceBD, maxPriceBD, minRatingBD, pageable);
            }
            log.debug("Category search for '{}' returned {} results", category, results.getTotalElements());
        } else if (minPrice != null || maxPrice != null) {
            results = productService.getProductsByPriceRange(minPriceBD, maxPriceBD, pageable);
            if (minRating != null) {
                List<ProductDTO> filtered = applyRatingFilter(results.getContent(), minRating);
                log.info("FTS searchProducts returned {} products", filtered.size());
                return filtered;
            }
        } else {
            results = productService.getFeaturedProducts(pageable);
        }

        List<ProductDTO> products = results.getContent();
        log.info("FTS searchProducts returned {} products", products.size());
        return products;
    }

    /**
     * Get details for a specific product. Called by Gemini for queries like:
     * - "Tell me about Sony WH-1000XM5"
     * - "Details of product ID xyz"
     *
     * @param productId Product UUID (optional)
     * @param productName Product name to search (optional)
     * @return Product details or null
     */
    public ProductDTO executeGetProductDetails(String productId, String productName) {
        log.info("Executing getProductDetails: productId={}, productName={}", productId, productName);

        try {
            if (productId != null && !productId.isBlank()) {
                return productService.getProductById(UUID.fromString(productId));
            } else if (productName != null && !productName.isBlank()) {
                Page<ProductDTO> results = productService.searchProducts(productName, PageRequest.of(0, 1));
                return results.hasContent() ? results.getContent().get(0) : null;
            }
        } catch (Exception e) {
            log.warn("Error getting product details: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Get all available categories. Called by Gemini for queries like:
     * - "What categories do you have?"
     * - "Show me all product categories"
     *
     * @return List of categories
     */
    public List<CategoryDTO> executeGetCategories() {
        log.info("Executing getCategories");
        return categoryService.getAllCategories();
    }

    /**
     * Get featured/popular products. Called by Gemini for queries like:
     * - "Show me popular products"
     * - "What are your best sellers?"
     *
     * @return List of featured products
     */
    public List<ProductDTO> executeGetFeaturedProducts() {
        log.info("Executing getFeaturedProducts");
        return productService.getFeaturedProducts(PageRequest.of(0, DEFAULT_PAGE_SIZE)).getContent();
    }

    /**
     * Compare multiple products. Called by Gemini for queries like:
     * - "Compare iPhone 15 vs Samsung S24"
     * - "Which is better: Sony XM5 or Bose QC45?"
     *
     * @param productNames List of product names to compare
     * @return List of products for comparison
     */
    public List<ProductDTO> executeCompareProducts(List<String> productNames) {
        log.info("Executing compareProducts: productNames={}", productNames);

        if (productNames == null || productNames.isEmpty()) {
            return List.of();
        }

        return productNames.stream()
                .map(name -> {
                    Page<ProductDTO> results = productService.searchProducts(name, PageRequest.of(0, 1));
                    return results.hasContent() ? results.getContent().get(0) : null;
                })
                .filter(p -> p != null)
                .toList();
    }

    /**
     * Get products by brand. Called by Gemini for queries like:
     * - "Show me Apple products"
     * - "What Samsung phones do you have?"
     *
     * @param brand Brand name
     * @return List of products from that brand
     */
    public List<ProductDTO> executeGetProductsByBrand(String brand) {
        log.info("Executing getProductsByBrand: brand={}", brand);

        if (brand == null || brand.isBlank()) {
            return List.of();
        }

        return productService.getProductsByBrand(brand, PageRequest.of(0, DEFAULT_PAGE_SIZE)).getContent();
    }

    // ==================== Helper Methods ====================

    private UUID findCategoryIdByName(String categoryName) {
        return categoryService.getAllCategories().stream()
                .filter(c -> c.getName().equalsIgnoreCase(categoryName) ||
                        c.getName().toLowerCase().contains(categoryName.toLowerCase()))
                .map(CategoryDTO::getId)
                .findFirst()
                .orElse(null);
    }

    private List<ProductDTO> applyRatingFilter(List<ProductDTO> products, Double minRating) {
        if (minRating == null) {
            return products;
        }
        return products.stream()
                .filter(p -> p.getRating() != null && p.getRating().doubleValue() >= minRating)
                .toList();
    }
}
