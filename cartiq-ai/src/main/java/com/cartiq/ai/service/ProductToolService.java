package com.cartiq.ai.service;

import com.cartiq.product.dto.CategoryDTO;
import com.cartiq.product.dto.ProductDTO;
import com.cartiq.product.service.CategoryService;
import com.cartiq.product.service.ProductService;
import com.cartiq.rag.dto.SearchResult;
import com.cartiq.rag.service.RerankerService;
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
    private final RerankerService rerankerService;

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int VECTOR_SEARCH_CANDIDATES = 50;
    private static final int HYBRID_CANDIDATES = 30; // Candidates from each source for hybrid search

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
     * Uses HYBRID search strategy with Cross-Encoder Reranking:
     * 1. Run Vector Search (semantic) → get candidates
     * 2. Run PostgreSQL FTS (keyword) → get candidates
     * 3. Combine and deduplicate candidates
     * 4. Use Cross-Encoder Reranker to select top-N most relevant results
     */
    public List<ProductDTO> executeSearchProducts(
            String query,
            String category,
            Double minPrice,
            Double maxPrice,
            Double minRating) {

        log.info("Executing searchProducts: query={}, category={}, minPrice={}, maxPrice={}, minRating={}",
                query, category, minPrice, maxPrice, minRating);

        // If no query, just use FTS/category search
        if (query == null || query.isBlank()) {
            return executeFtsSearch(query, category, minPrice, maxPrice, minRating);
        }

        // HYBRID SEARCH: Run both Vector Search and FTS
        List<ProductDTO> vectorResults = List.of();
        List<ProductDTO> ftsResults = List.of();

        // 1. Vector Search (semantic)
        if (vectorSearchService.isAvailable()) {
            vectorResults = executeVectorSearch(query, category, minPrice, maxPrice, minRating);
            log.debug("Vector search returned {} candidates", vectorResults.size());
        }

        // 2. FTS Search (keyword)
        ftsResults = executeFtsSearchWithLimit(query, category, minPrice, maxPrice, minRating, HYBRID_CANDIDATES);
        log.debug("FTS search returned {} candidates", ftsResults.size());

        // 3. Combine and deduplicate
        Map<UUID, ProductDTO> combinedMap = new LinkedHashMap<>();

        // Add vector results first (they have semantic relevance)
        for (ProductDTO product : vectorResults) {
            combinedMap.put(product.getId(), product);
        }

        // Add FTS results (keyword matches)
        for (ProductDTO product : ftsResults) {
            combinedMap.putIfAbsent(product.getId(), product);
        }

        List<ProductDTO> combinedResults = new ArrayList<>(combinedMap.values());
        log.info("Hybrid search: {} vector + {} FTS = {} unique candidates",
                vectorResults.size(), ftsResults.size(), combinedResults.size());

        // 4. Apply category filter if specified (post-filter since FTS doesn't support category+query)
        if (category != null && !category.isBlank()) {
            UUID categoryId = findCategoryIdByName(category);
            Set<String> allowedCategories = new HashSet<>();

            if (categoryId != null) {
                // Get category and all descendants
                allowedCategories = categoryService.expandCategoryNamesWithDescendants(List.of(category));
            }

            if (!allowedCategories.isEmpty()) {
                Set<String> finalAllowedCategories = allowedCategories;
                List<ProductDTO> filtered = combinedResults.stream()
                        .filter(p -> p.getCategoryName() != null &&
                                finalAllowedCategories.contains(p.getCategoryName()))
                        .toList();

                log.info("Category filter '{}': {} -> {} products (allowed categories: {})",
                        category, combinedResults.size(), filtered.size(), allowedCategories.size());

                combinedResults = new ArrayList<>(filtered);
            }
        }

        // If no results at all, return empty
        if (combinedResults.isEmpty()) {
            return List.of();
        }

        // If only a few results or reranker not available, return as-is
        if (combinedResults.size() <= DEFAULT_PAGE_SIZE || !rerankerService.isAvailable()) {
            return combinedResults.subList(0, Math.min(DEFAULT_PAGE_SIZE, combinedResults.size()));
        }

        // 5. Rerank using Cross-Encoder
        return rerankResults(query, combinedResults);
    }

    /**
     * Rerank combined results using Cross-Encoder model.
     */
    private List<ProductDTO> rerankResults(String query, List<ProductDTO> candidates) {
        try {
            // Prepare documents for reranking
            List<String> documents = candidates.stream()
                    .map(this::buildProductDescription)
                    .toList();

            List<String> documentIds = candidates.stream()
                    .map(p -> p.getId().toString())
                    .toList();

            // Call reranker
            List<String> rerankedIds = rerankerService.rerank(query, documents, documentIds, DEFAULT_PAGE_SIZE);

            // Map back to products in reranked order
            Map<UUID, ProductDTO> productMap = candidates.stream()
                    .collect(Collectors.toMap(ProductDTO::getId, p -> p));

            List<ProductDTO> rerankedProducts = rerankedIds.stream()
                    .map(id -> productMap.get(UUID.fromString(id)))
                    .filter(Objects::nonNull)
                    .toList();

            log.info("Reranker returned {} products from {} candidates", rerankedProducts.size(), candidates.size());
            return rerankedProducts;

        } catch (Exception e) {
            log.warn("Reranking failed, returning original order: {}", e.getMessage());
            return candidates.subList(0, Math.min(DEFAULT_PAGE_SIZE, candidates.size()));
        }
    }

    /**
     * Build a product description for reranking.
     */
    private String buildProductDescription(ProductDTO product) {
        StringBuilder sb = new StringBuilder();
        sb.append(product.getName());
        if (product.getBrand() != null) {
            sb.append(" by ").append(product.getBrand());
        }
        if (product.getCategoryName() != null) {
            sb.append(". Category: ").append(product.getCategoryName());
        }
        if (product.getDescription() != null) {
            String desc = product.getDescription();
            if (desc.length() > 200) {
                desc = desc.substring(0, 200) + "...";
            }
            sb.append(". ").append(desc);
        }
        return sb.toString();
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
     * Full-text search using PostgreSQL with custom limit.
     */
    private List<ProductDTO> executeFtsSearchWithLimit(
            String query,
            String category,
            Double minPrice,
            Double maxPrice,
            Double minRating,
            int limit) {

        Pageable pageable = PageRequest.of(0, limit);
        Page<ProductDTO> results;

        BigDecimal minPriceBD = minPrice != null ? BigDecimal.valueOf(minPrice) : null;
        BigDecimal maxPriceBD = maxPrice != null ? BigDecimal.valueOf(maxPrice) : null;
        BigDecimal minRatingBD = minRating != null ? BigDecimal.valueOf(minRating) : null;

        if (query != null && !query.isBlank()) {
            results = productService.searchWithFilters(query, minPriceBD, maxPriceBD, minRatingBD, pageable);
        } else if (category != null && !category.isBlank()) {
            UUID categoryId = findCategoryIdByName(category);
            if (categoryId != null) {
                results = productService.getProductsByCategoryWithFilters(
                        categoryId, minPriceBD, maxPriceBD, minRatingBD, pageable);
            } else {
                results = productService.searchWithFilters(category, minPriceBD, maxPriceBD, minRatingBD, pageable);
            }
        } else {
            results = productService.getFeaturedProducts(pageable);
        }

        return results.getContent();
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
