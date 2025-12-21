package com.cartiq.product.repository;

import com.cartiq.product.entity.Product;
import com.cartiq.product.entity.Product.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    Page<Product> findByCategoryId(UUID categoryId, Pageable pageable);

    Page<Product> findByCategoryIdAndStatus(UUID categoryId, ProductStatus status, Pageable pageable);

    /**
     * Find products by multiple category IDs (for hierarchical category queries).
     */
    @Query("SELECT p FROM Product p WHERE p.category.id IN :categoryIds AND p.status = 'ACTIVE' ORDER BY p.rating DESC NULLS LAST")
    Page<Product> findByCategoryIdInAndStatusActive(@Param("categoryIds") List<UUID> categoryIds, Pageable pageable);

    Page<Product> findByBrand(String brand, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' AND p.featured = true")
    Page<Product> findFeaturedProducts(Pageable pageable);

    /**
     * Full-text search using PostgreSQL stored tsvector column with GIN index.
     * Handles stemming (phone→phones), ranking, and word boundaries.
     * Uses the pre-computed search_vector column for maximum performance.
     */
    @Query(value = """
            SELECT * FROM products p
            WHERE p.status = 'ACTIVE'
            AND p.search_vector @@ plainto_tsquery('english', :query)
            ORDER BY ts_rank(p.search_vector, plainto_tsquery('english', :query)) DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM products p
            WHERE p.status = 'ACTIVE'
            AND p.search_vector @@ plainto_tsquery('english', :query)
            """,
            nativeQuery = true)
    Page<Product> fullTextSearch(@Param("query") String query, Pageable pageable);

    /**
     * Legacy LIKE-based search (fallback when FTS returns no results).
     */
    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' AND " +
           "(LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.brand) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<Product> search(@Param("query") String query, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' AND p.price BETWEEN :minPrice AND :maxPrice")
    Page<Product> findByPriceRange(@Param("minPrice") BigDecimal minPrice,
                                   @Param("maxPrice") BigDecimal maxPrice,
                                   Pageable pageable);

    /**
     * Combined full-text search with price and rating filters.
     * Uses pre-computed search_vector column with GIN index for performance.
     */
    @Query(value = """
            SELECT * FROM products p
            WHERE p.status = 'ACTIVE'
            AND p.search_vector @@ plainto_tsquery('english', :query)
            AND (:minPrice IS NULL OR p.price >= :minPrice)
            AND (:maxPrice IS NULL OR p.price <= :maxPrice)
            AND (:minRating IS NULL OR p.rating >= :minRating)
            ORDER BY ts_rank(p.search_vector, plainto_tsquery('english', :query)) DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM products p
            WHERE p.status = 'ACTIVE'
            AND p.search_vector @@ plainto_tsquery('english', :query)
            AND (:minPrice IS NULL OR p.price >= :minPrice)
            AND (:maxPrice IS NULL OR p.price <= :maxPrice)
            AND (:minRating IS NULL OR p.rating >= :minRating)
            """,
            nativeQuery = true)
    Page<Product> fullTextSearchWithFilters(@Param("query") String query,
                                            @Param("minPrice") BigDecimal minPrice,
                                            @Param("maxPrice") BigDecimal maxPrice,
                                            @Param("minRating") BigDecimal minRating,
                                            Pageable pageable);

    /**
     * Legacy LIKE-based search with filters (fallback).
     */
    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' " +
           "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "     LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "     LOWER(p.brand) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "AND (:minPrice IS NULL OR p.price >= :minPrice) " +
           "AND (:maxPrice IS NULL OR p.price <= :maxPrice) " +
           "AND (:minRating IS NULL OR p.rating >= :minRating)")
    Page<Product> searchWithFilters(@Param("query") String query,
                                    @Param("minPrice") BigDecimal minPrice,
                                    @Param("maxPrice") BigDecimal maxPrice,
                                    @Param("minRating") BigDecimal minRating,
                                    Pageable pageable);

    /**
     * Search by category with price and rating filters.
     */
    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' " +
           "AND p.category.id = :categoryId " +
           "AND (:minPrice IS NULL OR p.price >= :minPrice) " +
           "AND (:maxPrice IS NULL OR p.price <= :maxPrice) " +
           "AND (:minRating IS NULL OR p.rating >= :minRating)")
    Page<Product> findByCategoryWithFilters(@Param("categoryId") UUID categoryId,
                                            @Param("minPrice") BigDecimal minPrice,
                                            @Param("maxPrice") BigDecimal maxPrice,
                                            @Param("minRating") BigDecimal minRating,
                                            Pageable pageable);

    @Query("SELECT DISTINCT p.brand FROM Product p WHERE p.brand IS NOT NULL AND p.status = 'ACTIVE' ORDER BY p.brand")
    List<String> findAllBrands();

    List<Product> findByIdIn(List<UUID> ids);

    /**
     * Batch lookup of existing SKUs.
     * Much faster than individual existsBySku calls.
     */
    @Query("SELECT p.sku FROM Product p WHERE p.sku IN :skus")
    Set<String> findExistingSkus(@Param("skus") Collection<String> skus);

    /**
     * Find top-rated products by category names with optional price filters.
     * Used for suggestions based on category affinity.
     * Note: p.id added for deterministic ordering when rating/reviewCount are equal.
     */
    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' " +
           "AND p.category.name IN :categoryNames " +
           "AND (:minPrice IS NULL OR p.price >= :minPrice) " +
           "AND (:maxPrice IS NULL OR p.price <= :maxPrice) " +
           "ORDER BY p.rating DESC NULLS LAST, p.reviewCount DESC NULLS LAST, p.id ASC")
    List<Product> findTopRatedByCategoryNames(@Param("categoryNames") List<String> categoryNames,
                                               @Param("minPrice") BigDecimal minPrice,
                                               @Param("maxPrice") BigDecimal maxPrice,
                                               Pageable pageable);

    /**
     * Find top featured/trending products for cold start recommendations.
     * Orders by featured flag, then rating, then review count.
     * Note: p.id added for deterministic ordering when other fields are equal.
     */
    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' " +
           "ORDER BY p.featured DESC NULLS LAST, p.rating DESC NULLS LAST, p.reviewCount DESC NULLS LAST, p.id ASC")
    List<Product> findTopFeaturedProducts(Pageable pageable);
}
