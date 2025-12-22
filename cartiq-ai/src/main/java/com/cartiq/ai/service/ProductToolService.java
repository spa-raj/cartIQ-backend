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
     * 4. Apply brand filter if specified
     * 5. Use Cross-Encoder Reranker to select top-N most relevant results
     */
    public List<ProductDTO> executeSearchProducts(
            String query,
            String category,
            String brand,
            Double minPrice,
            Double maxPrice,
            Double minRating) {

        log.info("Executing searchProducts: query={}, category={}, brand={}, minPrice={}, maxPrice={}, minRating={}",
                query, category, brand, minPrice, maxPrice, minRating);

        // If no query AND no brand filter, just use FTS/category search
        // When brand is specified, we MUST go through hybrid search to apply brand filtering
        if ((query == null || query.isBlank()) && (brand == null || brand.isBlank())) {
            return executeFtsSearch(query, category, minPrice, maxPrice, minRating);
        }

        // HYBRID SEARCH: Vector + FTS + Category + Brand-specific
        List<ProductDTO> vectorResults = List.of();
        List<ProductDTO> ftsResults = List.of();
        List<ProductDTO> categoryResults = List.of();
        List<ProductDTO> brandResults = List.of();

        // Build separate queries for different search strategies:
        // - Vector search: full natural language query (semantic understanding)
        // - FTS search: brand name for exact keyword matching (avoids "mobile phones" breaking FTS)
        String vectorQuery = (query != null && !query.isBlank()) ? query : brand;
        String ftsQuery = (brand != null && !brand.isBlank()) ? brand : query;

        log.info("Search queries: vectorQuery='{}', ftsQuery='{}' (original query='{}', brand='{}')",
                vectorQuery, ftsQuery, query, brand);

        // 1. Vector Search (semantic) - understands natural language like "mobile phones"
        if (vectorSearchService.isAvailable() && vectorQuery != null) {
            vectorResults = executeVectorSearch(vectorQuery, null, minPrice, maxPrice, minRating);
            log.debug("Vector search returned {} candidates", vectorResults.size());
        }

        // 2. FTS Search (keyword) - uses brand for exact matching (robust)
        if (ftsQuery != null) {
            ftsResults = executeFtsSearchWithLimit(ftsQuery, null, minPrice, maxPrice, minRating, HYBRID_CANDIDATES);
            log.debug("FTS search returned {} candidates", ftsResults.size());
        }

        // 3. Category-specific search (fallback to ensure we have products from target category)
        if (category != null && !category.isBlank()) {
            UUID categoryId = findCategoryIdByName(category);
            if (categoryId != null) {
                categoryResults = productService.getProductsByCategoryWithFilters(
                        categoryId,
                        minPrice != null ? BigDecimal.valueOf(minPrice) : null,
                        maxPrice != null ? BigDecimal.valueOf(maxPrice) : null,
                        minRating != null ? BigDecimal.valueOf(minRating) : null,
                        PageRequest.of(0, HYBRID_CANDIDATES)
                ).getContent();
                log.debug("Category search returned {} candidates", categoryResults.size());
            }
        }

        // 4. Brand-specific search WITH price filters (ensures budget products are found)
        if (brand != null && !brand.isBlank()) {
            brandResults = productService.getProductsByBrandWithFilters(
                    brand,
                    minPrice != null ? BigDecimal.valueOf(minPrice) : null,
                    maxPrice != null ? BigDecimal.valueOf(maxPrice) : null,
                    minRating != null ? BigDecimal.valueOf(minRating) : null,
                    PageRequest.of(0, HYBRID_CANDIDATES)
            ).getContent();
            log.debug("Brand search for '{}' returned {} candidates", brand, brandResults.size());
        }

        // 5. Combine and deduplicate - brand results FIRST for highest priority
        Map<UUID, ProductDTO> combinedMap = new LinkedHashMap<>();
        brandResults.forEach(p -> combinedMap.put(p.getId(), p));  // Brand first!
        vectorResults.forEach(p -> combinedMap.putIfAbsent(p.getId(), p));
        ftsResults.forEach(p -> combinedMap.putIfAbsent(p.getId(), p));
        categoryResults.forEach(p -> combinedMap.putIfAbsent(p.getId(), p));


        List<ProductDTO> combinedResults = new ArrayList<>(combinedMap.values());
        log.info("Hybrid search: {} brand + {} vector + {} FTS + {} category = {} unique candidates",
                brandResults.size(), vectorResults.size(), ftsResults.size(), categoryResults.size(), combinedResults.size());

        // 5. Apply a strict, universal post-filter to guarantee all constraints are met.
        // This is the final safety net.
        List<ProductDTO> finalResults = applyUniversalFilter(
                combinedResults, query, category, brand, minPrice, maxPrice, minRating);

        // If no results at all, return empty
        if (finalResults.isEmpty()) {
            return List.of();
        }

        // If only a few results or reranker not available, return as-is
        if (finalResults.size() <= DEFAULT_PAGE_SIZE || !rerankerService.isAvailable()) {
            return finalResults.subList(0, Math.min(DEFAULT_PAGE_SIZE, finalResults.size()));
        }

        // 6. Rerank the clean, filtered list
        return rerankResults(query, finalResults);
    }

    /**
     * Applies a strict filter for all user-provided constraints. This is a safety net
     * to ensure that all returned products match the user's explicit request.
     */
    private List<ProductDTO> applyUniversalFilter(
            List<ProductDTO> products,
            String query,
            String category,
            String brand,
            Double minPrice,
            Double maxPrice,
            Double minRating) {

        // Prepare filters
        final Set<String> allowedCategories = (category != null && !category.isBlank())
                ? categoryService.expandCategoryNamesWithDescendants(List.of(category))
                : Collections.emptySet();

        // Log the expanded categories for debugging
        log.info("CATEGORY FILTER DEBUG: Requested category='{}', Expanded to: {}",
                category, allowedCategories);

        // If a brand is explicitly passed, use it. Otherwise, try to extract from query.
        final String brandFilter = (brand != null && !brand.isBlank()) ? brand : extractBrandFromQuery(query);

        // Log unique categories in candidates for debugging
        Set<String> candidateCategories = products.stream()
                .map(ProductDTO::getCategoryName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        log.info("CANDIDATE CATEGORIES DEBUG: {} unique categories in {} candidates: {}",
                candidateCategories.size(), products.size(), candidateCategories);

        // Build relaxed category set for brand+category queries
        // When brand is specified, also allow RELATED categories (same product domain)
        // e.g., "Puma running shoes" → also allow Puma sneakers, sports shoes
        // BUT "Samsung smartphones" → do NOT allow Samsung TVs (different domain)
        final Set<String> relaxedCategories = (brandFilter != null && !allowedCategories.isEmpty())
                ? expandToRelatedCategories(allowedCategories)
                : Collections.emptySet();

        List<ProductDTO> filtered = products.stream()
                .filter(p -> {
                    // Brand check (strict when specified)
                    boolean brandOk = (brandFilter == null) ||
                            (p.getBrand() != null && p.getBrand().equalsIgnoreCase(brandFilter));

                    // Category check - use relaxed categories when brand matches
                    boolean categoryOk;
                    if (allowedCategories.isEmpty()) {
                        categoryOk = true;
                    } else if (brandOk && brandFilter != null && !relaxedCategories.isEmpty()) {
                        // Brand matches - allow related categories from same product domain
                        categoryOk = (p.getCategoryName() != null &&
                                (allowedCategories.contains(p.getCategoryName()) ||
                                 relaxedCategories.contains(p.getCategoryName())));
                        if (categoryOk && !allowedCategories.contains(p.getCategoryName())) {
                            log.debug("RELAXED category for brand match: {} (actual={}, wanted={}, relaxed={})",
                                    p.getName(), p.getCategoryName(), allowedCategories, relaxedCategories);
                        }
                    } else {
                        // No brand filter OR brand doesn't match - enforce category strictly
                        categoryOk = (p.getCategoryName() != null && allowedCategories.contains(p.getCategoryName()));
                    }

                    // Price check
                    boolean maxPriceOk = (maxPrice == null) ||
                            (p.getPrice() != null && p.getPrice().doubleValue() <= maxPrice);
                    boolean minPriceOk = (minPrice == null) ||
                            (p.getPrice() != null && p.getPrice().doubleValue() >= minPrice);

                    // Rating check
                    boolean minRatingOk = (minRating == null) ||
                            (p.getRating() != null && p.getRating().doubleValue() >= minRating);

                    // Log rejected products for debugging
                    if (!categoryOk && brandOk && brandFilter != null) {
                        log.debug("REJECTED by category (not in domain): {} (category={}, allowed={}, relaxed={})",
                                p.getName(), p.getCategoryName(), allowedCategories, relaxedCategories);
                    }

                    return categoryOk && maxPriceOk && minPriceOk && minRatingOk && brandOk;
                })
                .toList();

        // Log which products passed the filter
        if (!filtered.isEmpty()) {
            log.info("FILTERED PRODUCTS DEBUG: {} products passed. Categories: {}",
                    filtered.size(),
                    filtered.stream().map(ProductDTO::getCategoryName).distinct().toList());
        }

        log.info("Universal Post-Filter: {} -> {} products. Filters: category={}, brand={}, minPrice={}, maxPrice={}, minRating={}",
                products.size(), filtered.size(), category, brandFilter, minPrice, maxPrice, minRating);

        return filtered;
    }

    /**
     * Simple heuristic to extract a known brand from the query string.
     */
    private String extractBrandFromQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String lowerQuery = query.toLowerCase();
        // This could be expanded or moved to a configuration
        if (lowerQuery.contains("samsung")) return "Samsung";
        if (lowerQuery.contains("apple") || lowerQuery.contains("iphone")) return "Apple";
        if (lowerQuery.contains("oneplus")) return "OnePlus";
        if (lowerQuery.contains("google") || lowerQuery.contains("pixel")) return "Google";
        if (lowerQuery.contains("sony")) return "Sony";
        if (lowerQuery.contains("jbl")) return "JBL";
        if (lowerQuery.contains("bose")) return "Bose";
        if (lowerQuery.contains("boat")) return "boAt";
        if (lowerQuery.contains("marshall")) return "Marshall";
        if (lowerQuery.contains("nike")) return "Nike";
        if (lowerQuery.contains("iqoo")) return "iQOO";
        return null;
    }

    /**
     * Expands category names to include related categories within the same product domain.
     * This allows "Puma running shoes" to match Puma sneakers, but prevents
     * "Samsung smartphones" from matching Samsung TVs.
     *
     * Category domains are defined based on ACTUAL database categories.
     * - Footwear: Running Shoes, Sneakers, Sports Shoes, Casual Shoes, etc.
     * - Phones: Smartphones only (strict - no tablets, TVs)
     * - Audio: Headphones, On-Ear, Over-Ear
     */
    private Set<String> expandToRelatedCategories(Set<String> requestedCategories) {
        Set<String> expanded = new HashSet<>();

        // Define related category groups based on ACTUAL database categories
        // Each group contains categories that are acceptable substitutes for each other
        List<Set<String>> categoryDomains = List.of(
                // Footwear domain - all shoe types are related (from actual DB)
                Set.of("Running Shoes", "Running Shoes (Sports & Outdoor Shoes)",
                       "Sneakers", "Sneakers (Casual Shoes)",
                       "Sports & Outdoor Shoes", "Sports & Outdoor Shoes (Women's Shoes)",
                       "Casual Shoes", "Casual Shoes (Women's Shoes)",
                       "Walking Shoes", "Walking Shoes (Sports & Outdoor Shoes)",
                       "Training Shoes", "Men's Shoes", "Women's Shoes",
                       "Flip-Flops & Slippers", "Flip-Flops & Slippers (Men's Shoes)",
                       "Fashion Sandals", "Fashion Slippers", "Sandals & Floaters",
                       "Athletic & Outdoor Sandals", "Ethnic Footwear",
                       "Boots", "Boots (Women's Shoes)", "Formal Shoes",
                       "Badminton Shoes", "Cricket Shoes", "Football Shoes",
                       "Loafers & Moccasins (Casual Shoes)", "Clogs & Mules (Casual Shoes)"),

                // Audio domain - headphones/earphones are related (from actual DB)
                Set.of("Headphones", "Headphones, Earbuds & Accessories",
                       "On-Ear", "Over-Ear"),

                // Clothing tops domain (from actual DB)
                Set.of("T-Shirts", "T-Shirts (Tops, T-Shirts & Shirts)",
                       "T-Shirts (Shirts & Tees)", "T-Shirts (Sports T-Shirts & Jerseys)",
                       "Shirts", "Shirts (Tops, T-Shirts & Shirts)", "Shirts & Tees",
                       "Polos (Tops, T-Shirts & Shirts)", "Polos (Sports T-Shirts & Jerseys)",
                       "Blouses", "Blouses (Shirts)", "Tank Tops", "Tank Tops (Shirts & Tees)",
                       "Tops, T-Shirts & Shirts", "T-shirts, Polos & Shirts", "Button-Down Shirts"),

                // Clothing bottoms domain (from actual DB)
                Set.of("Trousers", "Casual Trousers", "Formal Trousers",
                       "Suit Trousers", "Trousers (Sportswear)",
                       "Jeans & Jeggings", "Pants", "Sweatpants")

                // NOTE: These are intentionally NOT grouped (strict category matching):
                // - Smartphones, Smartphones & Basic Mobiles (phones only)
                // - Televisions, Smart Televisions (TVs only)
                // - Laptops, 2 in 1 Laptops, Traditional Laptops (laptops only)
        );

        for (String requested : requestedCategories) {
            for (Set<String> domain : categoryDomains) {
                if (domain.stream().anyMatch(cat -> cat.equalsIgnoreCase(requested) ||
                        requested.toLowerCase().contains(cat.toLowerCase()) ||
                        cat.toLowerCase().contains(requested.toLowerCase()))) {
                    expanded.addAll(domain);
                    break;
                }
            }
        }

        if (!expanded.isEmpty()) {
            log.debug("Expanded categories {} to related domain: {}", requestedCategories, expanded);
        }

        return expanded;
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

    /**
     * Find accessories or complementary products for a specific product.
     * Called by Gemini for queries like:
     * - "What accessories go with this?"
     * - "Suggest accessories for iPhone 15"
     * - "What goes well with these headphones?"
     *
     * Uses a mapping of product categories to accessory categories.
     *
     * @param productId Product UUID (optional, from context)
     * @param productName Product name (optional, for search)
     * @param category Product category (optional, for direct accessory lookup)
     * @return List of accessory products
     */
    public List<ProductDTO> executeFindAccessories(String productId, String productName, String category) {
        log.info("Executing findAccessories: productId={}, productName={}, category={}", productId, productName, category);

        // 1. Determine the source product's category
        String productCategory = category;
        String sourceProductName = productName;

        if (productCategory == null && productId != null && !productId.isBlank()) {
            try {
                ProductDTO product = productService.getProductById(UUID.fromString(productId));
                if (product != null) {
                    productCategory = product.getCategoryName();
                    sourceProductName = product.getName();
                }
            } catch (Exception e) {
                log.warn("Could not fetch product by ID: {}", e.getMessage());
            }
        }

        if (productCategory == null && productName != null && !productName.isBlank()) {
            Page<ProductDTO> results = productService.searchProducts(productName, PageRequest.of(0, 1));
            if (results.hasContent()) {
                ProductDTO product = results.getContent().get(0);
                productCategory = product.getCategoryName();
                sourceProductName = product.getName();
            }
        }

        if (productCategory == null) {
            log.warn("Could not determine product category for accessories search");
            return List.of();
        }

        // 2. Map the product category to accessory categories
        List<String> accessoryCategories = getAccessoryCategories(productCategory);

        if (accessoryCategories.isEmpty()) {
            log.info("No accessory mapping found for category: {}", productCategory);
            // Fallback: use vector search for semantically related products
            return findRelatedProducts(sourceProductName, productCategory);
        }

        log.info("Finding accessories for category '{}': {}", productCategory, accessoryCategories);

        // 3. Search for products in accessory categories
        List<ProductDTO> accessories = new ArrayList<>();

        for (String accessoryCategory : accessoryCategories) {
            UUID categoryId = findCategoryIdByName(accessoryCategory);
            if (categoryId != null) {
                Page<ProductDTO> categoryProducts = productService.getProductsByCategoryWithFilters(
                        categoryId, null, null, null, PageRequest.of(0, 5));
                accessories.addAll(categoryProducts.getContent());
            }
        }

        // Limit to 10 products
        return accessories.stream()
                .limit(DEFAULT_PAGE_SIZE)
                .toList();
    }

    /**
     * Maps product categories to their typical accessory categories.
     * Based on common e-commerce accessory relationships.
     */
    private List<String> getAccessoryCategories(String productCategory) {
        if (productCategory == null) {
            return List.of();
        }

        String lower = productCategory.toLowerCase();

        // Smartphones → Cases, Screen Protectors, Chargers, Earbuds
        if (lower.contains("smartphone") || lower.contains("mobile") || lower.contains("phone")) {
            return List.of("Mobile Cases & Covers", "Screen Protectors", "Chargers & Cables",
                    "Power Banks", "Headphones", "Earbuds");
        }

        // Laptops → Bags, Mouse, Keyboard, USB Hubs
        if (lower.contains("laptop") || lower.contains("notebook")) {
            return List.of("Laptop Bags", "Mouse", "Keyboards", "USB Hubs",
                    "Laptop Stands", "External Hard Drives");
        }

        // Headphones → Cases, Cables, Ear Cushions
        if (lower.contains("headphone") || lower.contains("earphone") || lower.contains("earbud")) {
            return List.of("Headphone Cases", "Audio Cables", "Ear Cushions");
        }

        // Cameras → Lenses, Bags, Tripods, Memory Cards
        if (lower.contains("camera") || lower.contains("dslr")) {
            return List.of("Camera Lenses", "Camera Bags", "Tripods", "Memory Cards");
        }

        // TVs → Soundbars, Wall Mounts, HDMI Cables
        if (lower.contains("television") || lower.contains("tv")) {
            return List.of("Soundbars", "TV Wall Mounts", "HDMI Cables", "Streaming Devices");
        }

        // Gaming → Controllers, Headsets, Gaming Chairs
        if (lower.contains("gaming") || lower.contains("console")) {
            return List.of("Gaming Controllers", "Gaming Headsets", "Gaming Chairs");
        }

        // Watches → Watch Bands, Chargers
        if (lower.contains("watch") || lower.contains("smartwatch")) {
            return List.of("Watch Bands", "Watch Chargers", "Watch Cases");
        }

        // Shoes → Socks, Shoe Care, Insoles
        if (lower.contains("shoe") || lower.contains("sneaker") || lower.contains("footwear")) {
            return List.of("Socks", "Shoe Care", "Insoles", "Shoe Bags");
        }

        return List.of();
    }

    /**
     * Find related products using vector search when no accessory mapping exists.
     */
    private List<ProductDTO> findRelatedProducts(String productName, String excludeCategory) {
        if (productName == null || productName.isBlank()) {
            return List.of();
        }

        String query = "accessories for " + productName;
        List<ProductDTO> results = executeVectorSearch(query, null, null, null, null);

        // Filter out products from the same category (we want accessories, not similar products)
        return results.stream()
                .filter(p -> !p.getCategoryName().equalsIgnoreCase(excludeCategory))
                .limit(DEFAULT_PAGE_SIZE)
                .toList();
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
