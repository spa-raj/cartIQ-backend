package com.cartiq.product.repository;

import com.cartiq.product.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByName(String name);

    boolean existsByName(String name);

    List<Category> findByActiveTrue();

    List<Category> findByParentCategoryIsNullAndActiveTrue();

    List<Category> findByParentCategoryIdAndActiveTrue(UUID parentId);

    @Query("SELECT c FROM Category c WHERE c.active = true ORDER BY c.name ASC")
    List<Category> findAllActiveOrdered();

    @Query("SELECT c FROM Category c WHERE c.parentCategory IS NULL AND c.active = true ORDER BY c.name ASC")
    List<Category> findRootCategories();

    /**
     * Find all categories under a path prefix (includes the category itself and all descendants).
     * Example: findByPathStartingWith("Electronics") returns Electronics, Electronics >> Mobiles, etc.
     */
    List<Category> findByPathStartingWithAndActiveTrue(String pathPrefix);

    /**
     * Find category by exact path.
     */
    Optional<Category> findByPath(String path);

    /**
     * Find all categories at a specific level.
     */
    List<Category> findByLevelAndActiveTrue(Integer level);

    /**
     * Count products in a category.
     */
    @Query("SELECT COUNT(p) FROM Product p WHERE p.category.id = :categoryId AND p.status = 'ACTIVE'")
    Long countProductsByCategoryId(@org.springframework.data.repository.query.Param("categoryId") UUID categoryId);

    /**
     * Get product counts for all categories in a single query.
     * Returns list of [categoryId, count] pairs.
     */
    @Query("SELECT p.category.id, COUNT(p) FROM Product p WHERE p.status = 'ACTIVE' GROUP BY p.category.id")
    List<Object[]> getProductCountsByCategory();

    /**
     * Find all descendant categories (subcategories at any level) using recursive path matching.
     * Uses the 'path' column which stores the category hierarchy.
     */
    @Query("SELECT c.id FROM Category c WHERE c.path LIKE CONCAT(:parentPath, ' >> %') AND c.active = true")
    List<UUID> findDescendantCategoryIds(@org.springframework.data.repository.query.Param("parentPath") String parentPath);

    /**
     * Find categories where name contains the search term (case-insensitive).
     * Used for fuzzy matching when exact category name doesn't exist.
     */
    @Query("SELECT c FROM Category c WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) AND c.active = true")
    List<Category> findByNameContainingIgnoreCase(@org.springframework.data.repository.query.Param("searchTerm") String searchTerm);
}
