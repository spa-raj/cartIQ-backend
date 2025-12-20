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
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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

    @Transactional(readOnly = true)
    public Page<ProductDTO> getProductsByCategory(UUID categoryId, Pageable pageable) {
        return productRepository.findByCategoryIdAndStatus(categoryId, ProductStatus.ACTIVE, pageable)
                .map(ProductDTO::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<ProductDTO> getProductsByBrand(String brand, Pageable pageable) {
        return productRepository.findByBrand(brand, pageable)
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

    @Transactional(readOnly = true)
    public Page<ProductDTO> getFeaturedProducts(Pageable pageable) {
        return productRepository.findFeaturedProducts(pageable)
                .map(ProductDTO::fromEntity);
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
     * Orders by featured flag, rating, and review count.
     *
     * @param limit Maximum number of products to return
     * @return List of featured/trending products
     */
    @Transactional(readOnly = true)
    public List<ProductDTO> getTopFeaturedProducts(int limit) {
        return productRepository.findTopFeaturedProducts(PageRequest.of(0, limit))
                .stream()
                .map(ProductDTO::fromEntity)
                .toList();
    }
}
