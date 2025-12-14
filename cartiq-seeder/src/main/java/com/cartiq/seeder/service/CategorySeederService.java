package com.cartiq.seeder.service;

import com.cartiq.product.entity.Category;
import com.cartiq.product.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for parsing and creating hierarchical categories from Amazon LDJSON format.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategorySeederService {

    private static final String CATEGORY_DELIMITER = " >> ";

    private final CategoryRepository categoryRepository;

    // Cache categories by path for fast lookups
    private final Map<String, Category> categoryCache = new ConcurrentHashMap<>();

    // Lock for thread-safe category creation
    private final Object categoryLock = new Object();

    /**
     * Parses categories from Amazon LDJSON format.
     * Creates hierarchy from primary category and subcategory array.
     *
     * @param primaryCategory e.g., "Shoes & Handbags"
     * @param subCategories   e.g., ["Shoes", "Women's Shoes", "Flip-Flops & Slippers"]
     * @return the leaf category, or null if no categories provided
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Category getOrCreateCategoryFromLdjson(String primaryCategory, List<String> subCategories) {
        if ((primaryCategory == null || primaryCategory.isBlank()) &&
                (subCategories == null || subCategories.isEmpty())) {
            return null;
        }

        // Build the full path from primary + subcategories
        StringBuilder pathBuilder = new StringBuilder();

        // Add primary category as root
        if (primaryCategory != null && !primaryCategory.isBlank()) {
            String cleanPrimary = truncateName(primaryCategory.trim());
            pathBuilder.append(cleanPrimary);
        }

        // Add subcategories
        if (subCategories != null) {
            for (String subCat : subCategories) {
                if (subCat != null && !subCat.isBlank()) {
                    if (pathBuilder.length() > 0) {
                        pathBuilder.append(CATEGORY_DELIMITER);
                    }
                    pathBuilder.append(truncateName(subCat.trim()));
                }
            }
        }

        String fullPath = pathBuilder.toString();
        if (fullPath.isEmpty()) {
            return null;
        }

        // Check cache first for the full path
        Category cached = categoryCache.get(fullPath);
        if (cached != null) {
            return cached;
        }

        // Check database for existing category with this path
        Category existing = categoryRepository.findByPath(fullPath).orElse(null);
        if (existing != null) {
            categoryCache.put(fullPath, existing);
            return existing;
        }

        // Create the hierarchy
        Category parent = null;
        pathBuilder = new StringBuilder();

        // Process primary category
        if (primaryCategory != null && !primaryCategory.isBlank()) {
            String cleanPrimary = truncateName(primaryCategory.trim());
            pathBuilder.append(cleanPrimary);
            parent = getOrCreateCategory(cleanPrimary, pathBuilder.toString(), 0, null);
        }

        // Process subcategories
        if (subCategories != null) {
            int level = parent != null ? 1 : 0;
            for (String subCat : subCategories) {
                if (subCat != null && !subCat.isBlank()) {
                    String cleanSubCat = truncateName(subCat.trim());
                    if (pathBuilder.length() > 0) {
                        pathBuilder.append(CATEGORY_DELIMITER);
                    }
                    pathBuilder.append(cleanSubCat);
                    parent = getOrCreateCategory(cleanSubCat, pathBuilder.toString(), level, parent);
                    level++;
                }
            }
        }

        return parent;
    }

    /**
     * Gets or creates a category with the given parameters.
     * Thread-safe: uses synchronization to prevent race conditions.
     */
    private Category getOrCreateCategory(String name, String path, int level, Category parent) {
        // Check cache first (fast path, no lock needed)
        Category cached = categoryCache.get(path);
        if (cached != null) {
            return cached;
        }

        // Synchronize category creation to prevent race conditions
        synchronized (categoryLock) {
            // Double-check after acquiring lock
            cached = categoryCache.get(path);
            if (cached != null) {
                return cached;
            }

            // Check database
            Category existing = categoryRepository.findByPath(path).orElse(null);
            if (existing != null) {
                categoryCache.put(path, existing);
                return existing;
            }

            // Create new category
            Category created = createCategory(name, path, level, parent);
            categoryCache.put(path, created);
            return created;
        }
    }

    private Category createCategory(String name, String path, int level, Category parent) {
        Category category = Category.builder()
                .name(generateUniqueName(name, path))
                .path(path)
                .level(level)
                .parentCategory(parent)
                .active(true)
                .build();

        Category saved = categoryRepository.save(category);
        log.debug("Created category: {} (level: {}, path: {})", name, level, path);
        return saved;
    }

    /**
     * Generates a unique name for categories that might have the same name
     * but different paths. Always uses path context to ensure uniqueness.
     * This method is called within a synchronized block, so it's thread-safe.
     */
    private String generateUniqueName(String name, String path) {
        // First check cache for any category with this name
        boolean nameExistsInCache = categoryCache.values().stream()
                .anyMatch(c -> c.getName().equals(name));

        // Check if a category with this name already exists (cache or DB)
        if (!nameExistsInCache && !categoryRepository.existsByName(name)) {
            return name;
        }

        // If name exists, append parent context to make it unique
        String[] parts = path.split(CATEGORY_DELIMITER);
        if (parts.length >= 2) {
            String parentName = parts[parts.length - 2].trim();
            String uniqueName = name + " (" + parentName + ")";
            if (uniqueName.length() > 250) {
                uniqueName = uniqueName.substring(0, 250);
            }
            return uniqueName;
        }

        // Fallback: append a hash of the path
        return name + " #" + Math.abs(path.hashCode() % 10000);
    }

    /**
     * Truncates name to maximum allowed length.
     */
    private String truncateName(String name) {
        if (name.length() > 200) {
            return name.substring(0, 200);
        }
        return name;
    }

    /**
     * Preloads all existing categories into the cache.
     * Call this before bulk seeding for better performance.
     */
    @Transactional(readOnly = true)
    public void preloadCache() {
        categoryRepository.findAll().forEach(cat -> {
            if (cat.getPath() != null) {
                categoryCache.put(cat.getPath(), cat);
            }
        });
        log.info("Preloaded {} categories into cache", categoryCache.size());
    }

    /**
     * Clears the category cache.
     */
    public void clearCache() {
        categoryCache.clear();
    }

    /**
     * Gets the count of cached categories.
     */
    public int getCacheSize() {
        return categoryCache.size();
    }
}
