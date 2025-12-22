package com.cartiq.ai.service;

import com.cartiq.ai.dto.SuggestedProduct;
import com.cartiq.ai.dto.SuggestionsResponse;
import com.cartiq.kafka.dto.UserProfile;
import com.cartiq.product.dto.ProductDTO;
import com.cartiq.product.service.CategoryService;
import com.cartiq.product.service.ProductService;
import com.cartiq.rag.dto.SearchResult;
import com.cartiq.rag.service.VectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for generating personalized product suggestions.
 *
 * Recommendation Strategies (in priority order):
 * 1. AI Intent - Products matching explicit AI chat queries (strongest signal)
 * 2. Similar Products - Vector search based on recently viewed products
 * 3. Category Affinity - Top-rated products in browsed categories
 * 4. Cold Start - Trending/featured products (fallback)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SuggestionsService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ProductService productService;
    private final CategoryService categoryService;
    private final VectorSearchService vectorSearchService;

    @Value("${cartiq.suggestions.cache.prefix:user-profile:}")
    private String cachePrefix;

    /**
     * Get personalized product suggestions for a user.
     *
     * @param userId User ID (can be null for anonymous users)
     * @param limit Maximum number of suggestions to return
     * @return SuggestionsResponse with products and metadata
     */
    public SuggestionsResponse getSuggestions(String userId, int limit) {
        log.debug("Getting suggestions for userId={}, limit={}", userId, limit);

        UserProfile profile = getUserProfile(userId);

        List<SuggestedProduct> suggestions = new ArrayList<>();
        Map<String, String> strategyUsed = new LinkedHashMap<>();

        int remaining = limit;

        // Strategy 1: AI Intent (strongest signal - 40% of results)
        if (profile != null && hasAIIntent(profile)) {
            int aiLimit = Math.min(remaining, (int) Math.ceil(limit * 0.4));
            List<SuggestedProduct> aiProducts = getAIIntentProducts(profile, aiLimit);
            suggestions.addAll(aiProducts);
            remaining -= aiProducts.size();
            strategyUsed.put("ai_intent", String.valueOf(aiProducts.size()));
            log.debug("AI intent strategy returned {} products", aiProducts.size());
        }

        // Strategy 2: Similar to recently viewed (30% of results)
        if (profile != null && hasRecentViews(profile) && remaining > 0) {
            int similarLimit = Math.min(remaining, (int) Math.ceil(limit * 0.3));
            List<SuggestedProduct> similarProducts = getSimilarProducts(profile, similarLimit, suggestions);
            suggestions.addAll(similarProducts);
            remaining -= similarProducts.size();
            strategyUsed.put("similar_products", String.valueOf(similarProducts.size()));
            log.debug("Similar products strategy returned {} products", similarProducts.size());
        }

        // Strategy 3: Category affinity (fills remaining slots)
        // No cap - fill all remaining with personalized category products
        if (profile != null && hasCategories(profile) && remaining > 0) {
            List<SuggestedProduct> categoryProducts = getCategoryProducts(profile, remaining, suggestions);
            suggestions.addAll(categoryProducts);
            strategyUsed.put("category_affinity", String.valueOf(categoryProducts.size()));
            log.debug("Category affinity strategy returned {} products", categoryProducts.size());
        }

        // Final deduplication and limit
        List<SuggestedProduct> finalSuggestions = deduplicateAndLimit(suggestions, limit);

        log.info("Returning {} suggestions for userId={}, personalized={}, strategies={}",
                finalSuggestions.size(), userId, profile != null, strategyUsed);

        return SuggestionsResponse.builder()
                .products(finalSuggestions)
                .totalCount(finalSuggestions.size())
                .personalized(profile != null)
                .strategies(strategyUsed)
                .userId(userId)
                .lastUpdated(profile != null && profile.getLastUpdated() != null
                        ? profile.getLastUpdated().toString()
                        : null)
                .build();
    }

    /**
     * Fetch user profile from Redis cache.
     */
    private UserProfile getUserProfile(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }

        try {
            String cacheKey = cachePrefix + userId;
            Object cached = redisTemplate.opsForValue().get(cacheKey);

            if (cached instanceof UserProfile userProfile) {
                return userProfile;
            } else if (cached instanceof LinkedHashMap) {
                // Handle Jackson deserialization as LinkedHashMap
                return convertToUserProfile((LinkedHashMap<?, ?>) cached);
            }
        } catch (Exception e) {
            log.warn("Failed to get user profile from cache for userId={}: {}", userId, e.getMessage());
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private UserProfile convertToUserProfile(LinkedHashMap<?, ?> map) {
        try {
            return UserProfile.builder()
                    .userId((String) map.get("userId"))
                    .sessionId((String) map.get("sessionId"))
                    // Product activity
                    .recentProductIds((List<String>) map.get("recentProductIds"))
                    .recentCategories((List<String>) map.get("recentCategories"))
                    .recentSearchQueries((List<String>) map.get("recentSearchQueries"))
                    .totalProductViews(toLong(map.get("totalProductViews")))
                    .avgViewDurationMs(toLong(map.get("avgViewDurationMs")))
                    .avgProductPrice(toDouble(map.get("avgProductPrice")))
                    // Preferences
                    .pricePreference((String) map.get("pricePreference"))
                    // Cart state
                    .currentCartTotal(toDouble(map.get("currentCartTotal")))
                    .currentCartItems(toLong(map.get("currentCartItems")))
                    .cartAdds(toLong(map.get("cartAdds")))
                    .cartProductIds((List<String>) map.get("cartProductIds"))
                    .cartCategories((List<String>) map.get("cartCategories"))
                    // Session info
                    .deviceType((String) map.get("deviceType"))
                    .sessionDurationMs(toLong(map.get("sessionDurationMs")))
                    .totalPageViews(toLong(map.get("totalPageViews")))
                    .productPageViews(toLong(map.get("productPageViews")))
                    .cartPageViews(toLong(map.get("cartPageViews")))
                    .checkoutPageViews(toLong(map.get("checkoutPageViews")))
                    // AI intent signals
                    .aiSearchCount(toLong(map.get("aiSearchCount")))
                    .aiSearchQueries((List<String>) map.get("aiSearchQueries"))
                    .aiSearchCategories((List<String>) map.get("aiSearchCategories"))
                    .aiMaxBudget(toDouble(map.get("aiMaxBudget")))
                    .aiProductSearches(toLong(map.get("aiProductSearches")))
                    .aiProductComparisons(toLong(map.get("aiProductComparisons")))
                    // Order history
                    .totalOrders(toLong(map.get("totalOrders")))
                    .totalSpent(toDouble(map.get("totalSpent")))
                    .avgOrderValue(toDouble(map.get("avgOrderValue")))
                    .lastOrderTotal(toDouble(map.get("lastOrderTotal")))
                    .preferredPaymentMethod((String) map.get("preferredPaymentMethod"))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to convert LinkedHashMap to UserProfile: {}", e.getMessage());
            return null;
        }
    }

    private Long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        return 0L;
    }

    private Double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Double d) return d;
        if (value instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private boolean hasAIIntent(UserProfile profile) {
        // Consider AI intent present if we have ANY of these signals:
        // 1. AI search queries (explicit queries like "recommend me headphones under 6000")
        // 2. Recent search queries (search bar queries like "iphone", "kurta")
        // 3. Category signals (cart, recent, AI categories)
        // 4. Budget information
        return hasValidStrings(profile.getAiSearchQueries())
                || hasValidStrings(profile.getRecentSearchQueries())
                || hasValidCategories(profile.getAiSearchCategories())
                || hasValidCategories(profile.getCartCategories())
                || hasValidCategories(profile.getRecentCategories())
                || (profile.getAiMaxBudget() != null && profile.getAiMaxBudget() > 0);
    }

    private boolean hasValidStrings(List<String> strings) {
        return strings != null && !strings.isEmpty()
                && !strings.stream().allMatch(s -> s == null || s.isBlank());
    }

    private boolean hasValidCategories(List<String> categories) {
        return categories != null && !categories.isEmpty()
                && !categories.stream().allMatch(c -> c == null || c.isBlank());
    }

    private boolean hasRecentViews(UserProfile profile) {
        return profile.getRecentProductIds() != null && !profile.getRecentProductIds().isEmpty();
    }

    private boolean hasCategories(UserProfile profile) {
        return profile.getRecentCategories() != null && !profile.getRecentCategories().isEmpty()
                && !profile.getRecentCategories().stream().allMatch(String::isBlank);
    }

    /**
     * Strategy 1: AI Intent Products
     * Uses search queries and category signals to find relevant products via vector search.
     *
     * Search priority (based on reliability):
     * 1. AI search queries (most reliable - user's explicit natural language intent)
     *    e.g., "Recommend me headphones under 6000", "Compare top-rated laptops"
     * 2. Recent search queries (reliable - user's search bar queries)
     *    e.g., "iphone", "kurta", "bluetooth earbuds"
     * 3. Categories as fallback (cart > recent > AI categories)
     */
    private List<SuggestedProduct> getAIIntentProducts(UserProfile profile, int limit) {
        try {
            Double maxBudget = profile.getAiMaxBudget();

            // Build price filter
            Map<String, Object> filters = new HashMap<>();
            if (maxBudget != null && maxBudget > 0) {
                filters.put("maxPrice", maxBudget);
            }

            // Build allowed categories from AI search categories + recent categories
            // This ensures vector search results are relevant to what the user is interested in
            Set<String> baseCategories = new HashSet<>();
            if (profile.getAiSearchCategories() != null) {
                profile.getAiSearchCategories().stream()
                        .filter(c -> c != null && !c.isBlank())
                        .forEach(baseCategories::add);
            }
            if (profile.getRecentCategories() != null) {
                profile.getRecentCategories().stream()
                        .filter(c -> c != null && !c.isBlank())
                        .forEach(baseCategories::add);
            }

            // Expand categories to include all descendants
            // E.g., "Clothing" -> ["Clothing", "Salwar Suits", "Kurtas", ...]
            Set<String> allowedCategories = categoryService.expandCategoryNamesWithDescendants(baseCategories);

            log.debug("AI intent: filtering by categories: {} (expanded from {})",
                    allowedCategories.size(), baseCategories.size());

            List<SuggestedProduct> allResults = new ArrayList<>();
            Set<UUID> seenProductIds = new HashSet<>();

            // Priority 1: Use AI search queries directly (user's explicit intent)
            // These are natural language queries like "Recommend me headphones under 6000"
            if (hasValidStrings(profile.getAiSearchQueries())) {
                List<String> aiQueries = profile.getAiSearchQueries().stream()
                        .filter(q -> q != null && !q.isBlank())
                        .limit(2) // Limit to 2 most recent AI queries
                        .toList();

                log.info("AI intent: using aiSearchQueries for vector search: {}", aiQueries);

                for (String query : aiQueries) {
                    if (allResults.size() >= limit) break;

                    // Request more results since we'll filter by category
                    int queryLimit = Math.max(10, (limit - allResults.size()) * 3);
                    List<SearchResult> results = vectorSearchService.searchWithExpansion(query, queryLimit, filters, 2);

                    addSearchResultsToSuggestions(results, allResults, seenProductIds, limit,
                            "ai_intent", truncateContext(query, 40), allowedCategories);
                }
            }

            // Priority 2: Use recent search queries (search bar queries)
            // These are simpler queries like "iphone", "kurta", "headphones"
            if (allResults.size() < limit && hasValidStrings(profile.getRecentSearchQueries())) {
                List<String> searchQueries = profile.getRecentSearchQueries().stream()
                        .filter(q -> q != null && !q.isBlank())
                        .limit(3) // Limit to 3 most recent search queries
                        .toList();

                log.info("AI intent: using recentSearchQueries for vector search: {}", searchQueries);

                for (String query : searchQueries) {
                    if (allResults.size() >= limit) break;

                    // Request more results since we'll filter by category
                    int queryLimit = Math.max(10, (limit - allResults.size()) * 3);
                    List<SearchResult> results = vectorSearchService.search(query, queryLimit, filters);

                    addSearchResultsToSuggestions(results, allResults, seenProductIds, limit,
                            "ai_intent", truncateContext(query, 30), allowedCategories);
                }
            }

            log.debug("AI intent: collected {} products (filtered by {} categories)",
                    allResults.size(), allowedCategories.size());
            return allResults;

        } catch (Exception e) {
            log.warn("AI intent strategy failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Helper: Add search results to suggestions list, avoiding duplicates.
     */
    private void addSearchResultsToSuggestions(List<SearchResult> results,
                                                List<SuggestedProduct> allResults,
                                                Set<UUID> seenProductIds,
                                                int limit,
                                                String strategy,
                                                String context) {
        addSearchResultsToSuggestions(results, allResults, seenProductIds, limit, strategy, context, null);
    }

    /**
     * Helper: Add search results to suggestions list, with optional category filtering.
     * Uses fallback: if category filter gives 0 results, returns top unfiltered results.
     */
    private void addSearchResultsToSuggestions(List<SearchResult> results,
                                                List<SuggestedProduct> allResults,
                                                Set<UUID> seenProductIds,
                                                int limit,
                                                String strategy,
                                                String context,
                                                Set<String> allowedCategories) {
        List<UUID> productIds = results.stream()
                .map(r -> UUID.fromString(r.getProductId()))
                .filter(id -> !seenProductIds.contains(id))
                .toList();

        if (productIds.isEmpty()) return;

        List<ProductDTO> products = productService.getProductsByIds(productIds);

        // First pass: try with category filter
        List<ProductDTO> filteredProducts = new ArrayList<>();
        List<ProductDTO> unfilteredProducts = new ArrayList<>();

        for (ProductDTO product : products) {
            if (seenProductIds.contains(product.getId())) continue;
            unfilteredProducts.add(product);

            // Check category filter
            if (allowedCategories == null || allowedCategories.isEmpty()) {
                filteredProducts.add(product);
            } else {
                String productCategory = product.getCategoryName();
                if (productCategory != null && allowedCategories.contains(productCategory)) {
                    filteredProducts.add(product);
                }
            }
        }

        // Fallback: if filtering gave 0 results, use unfiltered top results
        List<ProductDTO> finalProducts;
        if (filteredProducts.isEmpty() && !unfilteredProducts.isEmpty()) {
            log.debug("Category filter returned 0/{} results, using unfiltered as fallback", unfilteredProducts.size());
            finalProducts = unfilteredProducts;
        } else {
            finalProducts = filteredProducts;
        }

        for (ProductDTO product : finalProducts) {
            if (allResults.size() >= limit) break;
            if (seenProductIds.add(product.getId())) {
                allResults.add(SuggestedProduct.fromStrategy(product, strategy, context));
            }
        }
    }

    /**
     * Helper: Get combined categories with priority ordering.
     * Cart > Recent > AI (based on reliability).
     */
    private List<String> getCombinedCategories(UserProfile profile) {
        List<String> combinedCategories = new ArrayList<>();

        // Priority 1: Cart categories (strongest - user is ready to buy these)
        if (profile.getCartCategories() != null) {
            profile.getCartCategories().stream()
                    .filter(c -> c != null && !c.isBlank())
                    .forEach(combinedCategories::add);
        }

        // Priority 2: Recent categories (strong - user actively browsed these)
        if (profile.getRecentCategories() != null) {
            profile.getRecentCategories().stream()
                    .filter(c -> c != null && !c.isBlank())
                    .filter(c -> !combinedCategories.contains(c))
                    .forEach(combinedCategories::add);
        }

        // Priority 3: AI search categories (may be inaccurate)
        if (profile.getAiSearchCategories() != null) {
            profile.getAiSearchCategories().stream()
                    .filter(c -> c != null && !c.isBlank())
                    .filter(c -> !combinedCategories.contains(c))
                    .forEach(combinedCategories::add);
        }

        return combinedCategories.stream()
                .distinct()
                .limit(4)
                .toList();
    }

    /**
     * Helper: Truncate context string for display.
     */
    private String truncateContext(String text, int maxLength) {
        if (text == null) return null;
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    /**
     * Strategy 2: Similar Products
     * Vector search based on recently viewed product embeddings.
     * IMPORTANT: Filters results to same category to ensure relevance.
     */
    private List<SuggestedProduct> getSimilarProducts(UserProfile profile, int limit, List<SuggestedProduct> existing) {
        try {
            // Get most recently viewed product
            String recentProductId = profile.getRecentProductIds().get(0);
            ProductDTO recentProduct = productService.getProductById(UUID.fromString(recentProductId));

            if (recentProduct == null) {
                return List.of();
            }

            // Get the category of the viewed product for filtering
            String sourceCategory = recentProduct.getCategoryName();

            // Build query from product attributes
            String query = buildProductQuery(recentProduct);

            // Search for more products to filter by category
            List<SearchResult> results = vectorSearchService.search(query, limit * 3, Map.of());

            // Get existing product IDs to exclude
            Set<UUID> existingIds = existing.stream()
                    .map(sp -> sp.getProduct().getId())
                    .collect(Collectors.toSet());
            existingIds.add(UUID.fromString(recentProductId)); // Also exclude the source product

            // Convert to UUIDs
            List<UUID> candidateIds = results.stream()
                    .map(r -> UUID.fromString(r.getProductId()))
                    .filter(id -> !existingIds.contains(id))
                    .toList();

            // Fetch products and filter by same category
            List<ProductDTO> allProducts = productService.getProductsByIds(candidateIds);

            List<ProductDTO> filteredProducts = allProducts.stream()
                    .filter(p -> sourceCategory != null && sourceCategory.equalsIgnoreCase(p.getCategoryName()))
                    .limit(limit)
                    .toList();

            // If no products in same category, return empty - don't show irrelevant products as "Similar to X"
            if (filteredProducts.isEmpty()) {
                log.debug("No similar products found in category '{}', returning empty (vector matches were in different categories)", sourceCategory);
                return List.of();
            }

            // Context: the product name we're finding similar items for
            String productName = recentProduct.getName();
            final String context = (productName != null && productName.length() > 30)
                    ? productName.substring(0, 30) + "..."
                    : productName;

            return filteredProducts.stream()
                    .map(p -> SuggestedProduct.fromStrategy(p, "similar_products", context))
                    .toList();

        } catch (Exception e) {
            log.warn("Similar products strategy failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Strategy 3: Category Products
     * Top-rated products in user's browsed categories, filtered by price preference.
     * Uses round-robin approach to ensure diversity across all categories.
     */
    private List<SuggestedProduct> getCategoryProducts(UserProfile profile, int limit, List<SuggestedProduct> existing) {
        try {
            List<String> categories = profile.getRecentCategories().stream()
                    .filter(c -> c != null && !c.isBlank())
                    .limit(5) // Cap to top 5 categories to avoid too many queries
                    .toList();

            if (categories.isEmpty()) {
                return List.of();
            }

            PriceRange priceRange = getPriceRange(profile.getPricePreference());

            // Track already included product IDs
            Set<UUID> existingIds = existing.stream()
                    .map(sp -> sp.getProduct().getId())
                    .collect(Collectors.toSet());
            Set<UUID> addedIds = new HashSet<>(existingIds);

            // Fetch top products per category (get extra to handle duplicates)
            int productsPerCategory = Math.max(2, (limit / categories.size()) + 1);
            Map<String, List<ProductDTO>> categoryProductsMap = new LinkedHashMap<>();

            for (String category : categories) {
                List<ProductDTO> products = productService.findTopRatedByCategories(
                        List.of(category),
                        priceRange.min(),
                        priceRange.max(),
                        productsPerCategory
                );
                categoryProductsMap.put(category, new ArrayList<>(products));
            }

            // Round-robin: pick 1 product from each category in rotation until we have enough
            List<SuggestedProduct> results = new ArrayList<>();
            Map<String, Integer> categoryIndex = new HashMap<>();
            categories.forEach(c -> categoryIndex.put(c, 0));

            int maxRounds = productsPerCategory;
            for (int round = 0; round < maxRounds && results.size() < limit; round++) {
                for (String category : categories) {
                    if (results.size() >= limit) break;

                    List<ProductDTO> products = categoryProductsMap.get(category);
                    int idx = categoryIndex.get(category);

                    // Find next product from this category that hasn't been added
                    while (idx < products.size()) {
                        ProductDTO product = products.get(idx);
                        idx++;
                        categoryIndex.put(category, idx);

                        if (!addedIds.contains(product.getId())) {
                            addedIds.add(product.getId());
                            results.add(SuggestedProduct.fromStrategy(product, "category_affinity", category));
                            break;
                        }
                    }
                }
            }

            log.debug("Category affinity: {} products from {} categories (round-robin)",
                    results.size(), categories.size());
            return results;

        } catch (Exception e) {
            log.warn("Category products strategy failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildProductQuery(ProductDTO product) {
        StringBuilder query = new StringBuilder();

        if (product.getName() != null) {
            query.append(product.getName()).append(" ");
        }
        if (product.getCategoryName() != null) {
            query.append(product.getCategoryName()).append(" ");
        }
        if (product.getBrand() != null) {
            query.append(product.getBrand());
        }

        return query.toString().trim();
    }

    private PriceRange getPriceRange(String pricePreference) {
        if (pricePreference == null) {
            return new PriceRange(null, null);
        }

        return switch (pricePreference) {
            case "BUDGET" -> new PriceRange(0.0, 500.0);
            case "MID_RANGE" -> new PriceRange(500.0, 2000.0);
            case "PREMIUM" -> new PriceRange(2000.0, null);
            default -> new PriceRange(null, null);
        };
    }

    private List<SuggestedProduct> deduplicateAndLimit(List<SuggestedProduct> products, int limit) {
        Map<UUID, SuggestedProduct> uniqueProducts = new LinkedHashMap<>();

        for (SuggestedProduct sp : products) {
            if (sp != null && sp.getProduct() != null && sp.getProduct().getId() != null
                    && !uniqueProducts.containsKey(sp.getProduct().getId())) {
                uniqueProducts.put(sp.getProduct().getId(), sp);
            }
            if (uniqueProducts.size() >= limit) {
                break;
            }
        }

        return new ArrayList<>(uniqueProducts.values());
    }

    private record PriceRange(Double min, Double max) {}
}
