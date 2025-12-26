package com.cartiq.product.service;

import com.cartiq.product.dto.CreateProductRequest;
import com.cartiq.product.dto.ProductDTO;
import com.cartiq.product.dto.UpdateProductRequest;
import com.cartiq.product.entity.Category;
import com.cartiq.product.entity.Product;
import com.cartiq.product.entity.Product.ProductStatus;
import com.cartiq.product.exception.ProductException;
import com.cartiq.product.repository.CategoryRepository;
import com.cartiq.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public Page<ProductDTO> getAllProducts(Pageable pageable) {
        return productRepository.findByStatus(ProductStatus.ACTIVE, pageable)
                .map(ProductDTO::fromEntity);
    }

    @Transactional(readOnly = true)
    public ProductDTO getProductById(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> ProductException.productNotFound(id.toString()));
        return ProductDTO.fromEntity(product);
    }

    @Transactional(readOnly = true)
    public ProductDTO getProductBySku(String sku) {
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> ProductException.productNotFound(sku));
        return ProductDTO.fromEntity(product);
    }

    /**
     * Get products by category ID, including products from all subcategories.
     * This allows parent categories like "Electronics" to show products from
     * child categories like "Headphones" -> "On-Ear".
     */
    @Transactional(readOnly = true)
    public Page<ProductDTO> getProductsByCategory(UUID categoryId, Pageable pageable) {
        // Get the category to find its path
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> ProductException.categoryNotFound(categoryId.toString()));

        // Collect this category + all descendant category IDs
        List<UUID> allCategoryIds = new ArrayList<>();
        allCategoryIds.add(categoryId);

        // Find all descendant categories using path prefix matching
        if (category.getPath() != null) {
            List<UUID> descendantIds = categoryRepository.findDescendantCategoryIds(category.getPath());
            allCategoryIds.addAll(descendantIds);
        }

        log.debug("Category search: {} includes {} total categories (with descendants)",
                category.getName(), allCategoryIds.size());

        return productRepository.findByCategoryIdInAndStatusActive(allCategoryIds, pageable)
                .map(ProductDTO::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<ProductDTO> getProductsByBrand(String brand, Pageable pageable) {
        return productRepository.findByBrand(brand, pageable)
                .map(ProductDTO::fromEntity);
    }

    /**
     * Search by brand with price and rating filters.
     * Orders by price ASC to prioritize budget-friendly products.
     *
     * @param brand Brand name (case-insensitive)
     * @param minPrice Minimum price filter (nullable)
     * @param maxPrice Maximum price filter (nullable)
     * @param minRating Minimum rating filter (nullable)
     * @param pageable Pagination parameters
     * @return Page of matching products ordered by price ascending
     */
    @Transactional(readOnly = true)
    public Page<ProductDTO> getProductsByBrandWithFilters(String brand, BigDecimal minPrice,
                                                           BigDecimal maxPrice, BigDecimal minRating,
                                                           Pageable pageable) {
        log.debug("Brand search with filters: brand={}, minPrice={}, maxPrice={}, minRating={}",
                brand, minPrice, maxPrice, minRating);
        return productRepository.findByBrandWithFilters(brand, minPrice, maxPrice, minRating, pageable)
                .map(ProductDTO::fromEntity);
    }

    /**
     * Search products using PostgreSQL Full-Text Search.
     * Falls back to LIKE-based search if FTS returns no results.
     *
     * @param query Search text
     * @param pageable Pagination parameters
     * @return Page of matching products, ranked by relevance
     */
    @Transactional(readOnly = true)
    public Page<ProductDTO> searchProducts(String query, Pageable pageable) {
        log.debug("Searching products with FTS: query={}", query);

        // Try full-text search first
        Page<Product> results = productRepository.fullTextSearch(query, pageable);

        // Fall back to LIKE search if FTS returns no results
        if (results.isEmpty()) {
            log.debug("FTS returned no results, falling back to LIKE search");
            results = productRepository.search(query, pageable);
        }

        return results.map(ProductDTO::fromEntity);
    }

    /**
     * Combined search: full-text search with optional price and rating filters.
     * This is the primary search method for AI-powered queries like
     * "Show me mobile phones under Rs.20000 with rating above 4".
     *
     * Uses PostgreSQL FTS for better relevance ranking (stemming, word boundaries).
     * Falls back to LIKE-based search if FTS returns no results.
     *
     * @param query Text to search in name, description, brand
     * @param minPrice Minimum price filter (nullable)
     * @param maxPrice Maximum price filter (nullable)
     * @param minRating Minimum rating filter (nullable)
     * @param pageable Pagination parameters
     * @return Page of matching products
     */
    @Transactional(readOnly = true)
    public Page<ProductDTO> searchWithFilters(String query, BigDecimal minPrice, BigDecimal maxPrice,
                                              BigDecimal minRating, Pageable pageable) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw ProductException.invalidPriceRange();
        }
        log.debug("Combined FTS search: query={}, minPrice={}, maxPrice={}, minRating={}",
                query, minPrice, maxPrice, minRating);

        // Try full-text search with filters first
        Page<Product> results = productRepository.fullTextSearchWithFilters(
                query, minPrice, maxPrice, minRating, pageable);

        // Fall back to LIKE search if FTS returns no results
        if (results.isEmpty()) {
            log.debug("FTS returned no results, falling back to LIKE search with filters");
            results = productRepository.searchWithFilters(query, minPrice, maxPrice, minRating, pageable);
        }

        return results.map(ProductDTO::fromEntity);
    }

    /**
     * Search by category with optional price and rating filters.
     *
     * @param categoryId Category UUID
     * @param minPrice Minimum price filter (nullable)
     * @param maxPrice Maximum price filter (nullable)
     * @param minRating Minimum rating filter (nullable)
     * @param pageable Pagination parameters
     * @return Page of matching products
     */
    @Transactional(readOnly = true)
    public Page<ProductDTO> getProductsByCategoryWithFilters(UUID categoryId, BigDecimal minPrice,
                                                              BigDecimal maxPrice, BigDecimal minRating,
                                                              Pageable pageable) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw ProductException.invalidPriceRange();
        }
        log.debug("Category search with filters: categoryId={}, minPrice={}, maxPrice={}, minRating={}",
                categoryId, minPrice, maxPrice, minRating);
        return productRepository.findByCategoryWithFilters(categoryId, minPrice, maxPrice, minRating, pageable)
                .map(ProductDTO::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<ProductDTO> getProductsByPriceRange(BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw ProductException.invalidPriceRange();
        }
        return productRepository.findByPriceRange(
                minPrice != null ? minPrice : BigDecimal.ZERO,
                maxPrice != null ? maxPrice : new BigDecimal("999999"),
                pageable
        ).map(ProductDTO::fromEntity);
    }

    /**
     * Get featured products with category diversity and randomization.
     * Ensures products from different categories are shown, not just one category.
     */
    @Transactional(readOnly = true)
    public Page<ProductDTO> getFeaturedProducts(Pageable pageable) {
        int requestedSize = pageable.getPageSize();
        int fetchSize = Math.min(requestedSize * 5, 100);

        // Fetch more products than needed for diversity
        List<Product> allProducts = productRepository.findFeaturedProducts(
                PageRequest.of(0, fetchSize)).getContent();

        if (allProducts.isEmpty()) {
            return Page.empty(pageable);
        }

        // Group by category
        Map<String, List<Product>> productsByCategory = allProducts.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getCategory() != null ? p.getCategory().getName() : "Uncategorized",
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // Shuffle products within each category for randomization
        Random random = new Random();
        productsByCategory.values().forEach(list -> Collections.shuffle(list, random));

        // Round-robin select from each category
        List<ProductDTO> diverseProducts = new ArrayList<>();
        Set<UUID> addedIds = new HashSet<>();
        int maxIterations = requestedSize;

        for (int i = 0; i < maxIterations && diverseProducts.size() < requestedSize; i++) {
            for (List<Product> categoryProducts : productsByCategory.values()) {
                if (diverseProducts.size() >= requestedSize) break;
                if (i < categoryProducts.size()) {
                    Product product = categoryProducts.get(i);
                    if (addedIds.add(product.getId())) {
                        diverseProducts.add(ProductDTO.fromEntity(product));
                    }
                }
            }
        }

        log.debug("Featured products: {} categories, {} products (randomized)",
                productsByCategory.size(), diverseProducts.size());

        return new org.springframework.data.domain.PageImpl<>(
                diverseProducts, pageable, allProducts.size());
    }

    @Transactional(readOnly = true)
    public List<String> getAllBrands() {
        return productRepository.findAllBrands();
    }

    @Transactional(readOnly = true)
    public List<ProductDTO> getProductsByIds(List<UUID> ids) {
        return productRepository.findByIdIn(ids).stream()
                .map(ProductDTO::fromEntity)
                .toList();
    }

    @Transactional
    public ProductDTO createProduct(CreateProductRequest request) {
        if (productRepository.existsBySku(request.getSku())) {
            throw ProductException.skuAlreadyExists(request.getSku());
        }

        Product product = Product.builder()
                .sku(request.getSku())
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .compareAtPrice(request.getCompareAtPrice())
                .stockQuantity(request.getStockQuantity() != null ? request.getStockQuantity() : 0)
                .brand(request.getBrand())
                .imageUrls(request.getImageUrls() != null ? request.getImageUrls() : List.of())
                .thumbnailUrl(request.getThumbnailUrl())
                .featured(request.getFeatured() != null ? request.getFeatured() : false)
                .build();

        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> ProductException.categoryNotFound(request.getCategoryId().toString()));
            product.setCategory(category);
        }

        product = productRepository.save(product);
        log.info("Product created: id={}, sku={}", product.getId(), product.getSku());

        return ProductDTO.fromEntity(product);
    }

    @Transactional
    public ProductDTO updateProduct(UUID id, UpdateProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> ProductException.productNotFound(id.toString()));

        if (request.getName() != null) {
            product.setName(request.getName());
        }
        if (request.getDescription() != null) {
            product.setDescription(request.getDescription());
        }
        if (request.getPrice() != null) {
            product.setPrice(request.getPrice());
        }
        if (request.getCompareAtPrice() != null) {
            product.setCompareAtPrice(request.getCompareAtPrice());
        }
        if (request.getStockQuantity() != null) {
            product.setStockQuantity(request.getStockQuantity());
        }
        if (request.getBrand() != null) {
            product.setBrand(request.getBrand());
        }
        if (request.getImageUrls() != null) {
            product.setImageUrls(request.getImageUrls());
        }
        if (request.getThumbnailUrl() != null) {
            product.setThumbnailUrl(request.getThumbnailUrl());
        }
        if (request.getFeatured() != null) {
            product.setFeatured(request.getFeatured());
        }
        if (request.getStatus() != null) {
            product.setStatus(ProductStatus.valueOf(request.getStatus()));
        }
        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> ProductException.categoryNotFound(request.getCategoryId().toString()));
            product.setCategory(category);
        }

        product = productRepository.save(product);
        log.info("Product updated: id={}", product.getId());

        return ProductDTO.fromEntity(product);
    }

    @Transactional
    public void updateStock(UUID id, int quantity) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> ProductException.productNotFound(id.toString()));

        int newQuantity = product.getStockQuantity() + quantity;
        if (newQuantity < 0) {
            throw ProductException.insufficientStock(product.getName(), product.getStockQuantity());
        }

        product.setStockQuantity(newQuantity);
        if (newQuantity == 0) {
            product.setStatus(ProductStatus.OUT_OF_STOCK);
        } else if (product.getStatus() == ProductStatus.OUT_OF_STOCK) {
            product.setStatus(ProductStatus.ACTIVE);
        }

        productRepository.save(product);
        log.info("Product stock updated: id={}, newQuantity={}", id, newQuantity);
    }

    // ==================== BEST OF ELECTRONICS ====================

    /**
     * Get randomized electronics products with varying price ranges.
     * Strictly filters by Electronics category and its subcategories.
     * Ensures price diversity by mixing budget, mid-range, and premium products.
     *
     * @param page Page number (0-indexed)
     * @param size Number of products per page
     * @return Paginated list of randomized electronics products
     */
    @Transactional(readOnly = true)
    public Page<ProductDTO> getBestOfElectronics(int page, int size) {
        // Find Electronics category
        Optional<Category> electronicsOpt = categoryRepository.findByNameIgnoreCase("Electronics");
        if (electronicsOpt.isEmpty()) {
            log.warn("Electronics category not found");
            return Page.empty(PageRequest.of(page, size));
        }

        Category electronics = electronicsOpt.get();

        // Get all Electronics subcategory IDs
        List<UUID> allCategoryIds = new ArrayList<>();
        allCategoryIds.add(electronics.getId());
        if (electronics.getPath() != null) {
            List<UUID> descendantIds = categoryRepository.findDescendantCategoryIds(electronics.getPath());
            allCategoryIds.addAll(descendantIds);
        }

        log.debug("Best of Electronics: searching in {} categories", allCategoryIds.size());

        // Fetch more products for randomization and price diversity
        int fetchSize = Math.min(size * 10, 200);
        List<Product> allProducts = productRepository.findByCategoryIdInAndStatusActive(
                allCategoryIds, PageRequest.of(0, fetchSize)).getContent();

        if (allProducts.isEmpty()) {
            return Page.empty(PageRequest.of(page, size));
        }

        // Categorize by price range for diversity
        List<Product> budget = new ArrayList<>();      // < 5000
        List<Product> midRange = new ArrayList<>();    // 5000-30000
        List<Product> premium = new ArrayList<>();     // > 30000

        for (Product p : allProducts) {
            double price = p.getPrice().doubleValue();
            if (price < 5000) {
                budget.add(p);
            } else if (price <= 30000) {
                midRange.add(p);
            } else {
                premium.add(p);
            }
        }

        // Shuffle each price range
        Random random = new Random();
        Collections.shuffle(budget, random);
        Collections.shuffle(midRange, random);
        Collections.shuffle(premium, random);

        // Combine with price diversity: mix from each range
        List<Product> diverseProducts = new ArrayList<>();
        int[] indices = {0, 0, 0};
        List<List<Product>> ranges = List.of(midRange, premium, budget); // Priority order

        // Round-robin from each price range
        while (diverseProducts.size() < allProducts.size()) {
            boolean added = false;
            for (int i = 0; i < ranges.size(); i++) {
                List<Product> range = ranges.get(i);
                if (indices[i] < range.size()) {
                    diverseProducts.add(range.get(indices[i]));
                    indices[i]++;
                    added = true;
                }
            }
            if (!added) break; // All ranges exhausted
        }

        // Calculate pagination
        int start = page * size;
        int end = Math.min(start + size, diverseProducts.size());

        if (start >= diverseProducts.size()) {
            return Page.empty(PageRequest.of(page, size));
        }

        List<ProductDTO> pageContent = diverseProducts.subList(start, end).stream()
                .map(ProductDTO::fromEntity)
                .toList();

        log.debug("Best of Electronics: page={}, size={}, returning {} products (budget={}, mid={}, premium={})",
                page, size, pageContent.size(), budget.size(), midRange.size(), premium.size());

        return new PageImpl<>(
                pageContent,
                PageRequest.of(page, size),
                diverseProducts.size()
        );
    }

    // ==================== BEST OF FASHION ====================

    /**
     * Get randomized fashion products with varying price ranges.
     * Strictly filters by Clothing & Accessories and Shoes & Handbags categories.
     * Ensures price diversity by mixing budget, mid-range, and premium products.
     *
     * @param page Page number (0-indexed)
     * @param size Number of products per page
     * @return Paginated list of randomized fashion products
     */
    @Transactional(readOnly = true)
    public Page<ProductDTO> getBestOfFashion(int page, int size) {
        // Find Fashion categories
        List<String> fashionCategoryNames = List.of("Clothing & Accessories", "Shoes & Handbags");
        List<UUID> allCategoryIds = new ArrayList<>();

        for (String categoryName : fashionCategoryNames) {
            Optional<Category> categoryOpt = categoryRepository.findByNameIgnoreCase(categoryName);
            if (categoryOpt.isPresent()) {
                Category category = categoryOpt.get();
                allCategoryIds.add(category.getId());
                // Get all subcategories
                if (category.getPath() != null) {
                    List<UUID> descendantIds = categoryRepository.findDescendantCategoryIds(category.getPath());
                    allCategoryIds.addAll(descendantIds);
                }
            }
        }

        if (allCategoryIds.isEmpty()) {
            log.warn("Fashion categories not found");
            return Page.empty(PageRequest.of(page, size));
        }

        log.debug("Best of Fashion: searching in {} categories", allCategoryIds.size());

        // Fetch more products for randomization and price diversity
        int fetchSize = Math.min(size * 10, 200);
        List<Product> allProducts = productRepository.findByCategoryIdInAndStatusActive(
                allCategoryIds, PageRequest.of(0, fetchSize)).getContent();

        if (allProducts.isEmpty()) {
            return Page.empty(PageRequest.of(page, size));
        }

        // Categorize by price range for diversity (fashion-appropriate ranges)
        List<Product> budget = new ArrayList<>();      // < 1000
        List<Product> midRange = new ArrayList<>();    // 1000-5000
        List<Product> premium = new ArrayList<>();     // > 5000

        for (Product p : allProducts) {
            double price = p.getPrice().doubleValue();
            if (price < 1000) {
                budget.add(p);
            } else if (price <= 5000) {
                midRange.add(p);
            } else {
                premium.add(p);
            }
        }

        // Shuffle each price range
        Random random = new Random();
        Collections.shuffle(budget, random);
        Collections.shuffle(midRange, random);
        Collections.shuffle(premium, random);

        // Combine with price diversity: mix from each range
        List<Product> diverseProducts = new ArrayList<>();
        int[] indices = {0, 0, 0};
        List<List<Product>> ranges = List.of(midRange, premium, budget); // Priority order

        // Round-robin from each price range
        while (diverseProducts.size() < allProducts.size()) {
            boolean added = false;
            for (int i = 0; i < ranges.size(); i++) {
                List<Product> range = ranges.get(i);
                if (indices[i] < range.size()) {
                    diverseProducts.add(range.get(indices[i]));
                    indices[i]++;
                    added = true;
                }
            }
            if (!added) break; // All ranges exhausted
        }

        // Calculate pagination
        int start = page * size;
        int end = Math.min(start + size, diverseProducts.size());

        if (start >= diverseProducts.size()) {
            return Page.empty(PageRequest.of(page, size));
        }

        List<ProductDTO> pageContent = diverseProducts.subList(start, end).stream()
                .map(ProductDTO::fromEntity)
                .toList();

        log.debug("Best of Fashion: page={}, size={}, returning {} products (budget={}, mid={}, premium={})",
                page, size, pageContent.size(), budget.size(), midRange.size(), premium.size());

        return new PageImpl<>(
                pageContent,
                PageRequest.of(page, size),
                diverseProducts.size()
        );
    }

    // ==================== SUGGESTIONS API METHODS ====================

    /**
     * Find top-rated products in specified categories within price range.
     * Used for category affinity recommendation strategy.
     *
     * @param categoryNames List of category names to search
     * @param minPrice Minimum price filter (nullable)
     * @param maxPrice Maximum price filter (nullable)
     * @param limit Maximum number of products to return
     * @return List of top-rated products matching the criteria
     */
    @Transactional(readOnly = true)
    public List<ProductDTO> findTopRatedByCategories(List<String> categoryNames,
                                                      Double minPrice,
                                                      Double maxPrice,
                                                      int limit) {
        if (categoryNames == null || categoryNames.isEmpty()) {
            return List.of();
        }

        BigDecimal minPriceBd = minPrice != null ? BigDecimal.valueOf(minPrice) : null;
        BigDecimal maxPriceBd = maxPrice != null ? BigDecimal.valueOf(maxPrice) : null;

        return productRepository.findTopRatedByCategoryNames(
                categoryNames,
                minPriceBd,
                maxPriceBd,
                PageRequest.of(0, limit)
        ).stream()
                .map(ProductDTO::fromEntity)
                .toList();
    }

    /**
     * Get featured/trending products for cold start recommendations.
     * Ensures CATEGORY DIVERSITY - products from different categories.
     * Orders by featured flag, rating, and review count within each category.
     *
     * @param limit Maximum number of products to return
     * @return List of featured/trending products with category diversity
     */
    @Transactional(readOnly = true)
    public List<ProductDTO> getTopFeaturedProducts(int limit) {
        // Fetch more products than needed to ensure diversity
        int fetchLimit = Math.min(limit * 5, 100);
        List<Product> allProducts = productRepository.findTopFeaturedProducts(PageRequest.of(0, fetchLimit));

        if (allProducts.isEmpty()) {
            return List.of();
        }

        // Group products by category
        Map<String, List<Product>> productsByCategory = allProducts.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getCategory() != null ? p.getCategory().getName() : "Uncategorized",
                        LinkedHashMap::new,  // Preserve insertion order (by rating)
                        Collectors.toList()
                ));

        // Round-robin select from each category to ensure diversity
        List<ProductDTO> diverseProducts = new ArrayList<>();
        Set<UUID> addedProductIds = new HashSet<>();
        int maxIterations = limit; // Prevent infinite loops

        for (int i = 0; i < maxIterations && diverseProducts.size() < limit; i++) {
            for (List<Product> categoryProducts : productsByCategory.values()) {
                if (diverseProducts.size() >= limit) {
                    break;
                }
                if (i < categoryProducts.size()) {
                    Product product = categoryProducts.get(i);
                    if (addedProductIds.add(product.getId())) {
                        diverseProducts.add(ProductDTO.fromEntity(product));
                    }
                }
            }
        }

        log.debug("Trending products: {} categories, {} products returned",
                productsByCategory.size(), diverseProducts.size());

        return diverseProducts;
    }
}
