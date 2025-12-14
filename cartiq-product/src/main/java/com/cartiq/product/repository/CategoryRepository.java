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
}
