package com.cartiq.product.service;

import com.cartiq.product.dto.CategoryDTO;
import com.cartiq.product.dto.CreateCategoryRequest;
import com.cartiq.product.dto.UpdateCategoryRequest;
import com.cartiq.product.entity.Category;
import com.cartiq.product.exception.ProductException;
import com.cartiq.product.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<CategoryDTO> getAllCategories() {
        Map<UUID, Long> productCounts = getProductCountsMap();
        return categoryRepository.findAllActiveOrdered().stream()
                .map(cat -> {
                    CategoryDTO dto = CategoryDTO.fromEntity(cat);
                    dto.setProductCount(productCounts.getOrDefault(cat.getId(), 0L).intValue());
                    return dto;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryDTO> getRootCategories() {
        Map<UUID, Long> productCounts = getProductCountsMap();
        return categoryRepository.findRootCategories().stream()
                .map(cat -> {
                    CategoryDTO dto = CategoryDTO.fromEntityWithSubCategories(cat);
                    dto.setProductCount(productCounts.getOrDefault(cat.getId(), 0L).intValue());
                    // Also set counts for subcategories
                    if (dto.getSubCategories() != null) {
                        dto.getSubCategories().forEach(sub ->
                                sub.setProductCount(productCounts.getOrDefault(sub.getId(), 0L).intValue()));
                    }
                    return dto;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryDTO getCategoryById(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> ProductException.categoryNotFound(id.toString()));
        Map<UUID, Long> productCounts = getProductCountsMap();
        CategoryDTO dto = CategoryDTO.fromEntityWithSubCategories(category);
        dto.setProductCount(productCounts.getOrDefault(id, 0L).intValue());
        // Also set counts for subcategories
        if (dto.getSubCategories() != null) {
            dto.getSubCategories().forEach(sub ->
                    sub.setProductCount(productCounts.getOrDefault(sub.getId(), 0L).intValue()));
        }
        return dto;
    }

    @Transactional(readOnly = true)
    public List<CategoryDTO> getSubCategories(UUID parentId) {
        Map<UUID, Long> productCounts = getProductCountsMap();
        return categoryRepository.findByParentCategoryIdAndActiveTrue(parentId).stream()
                .map(cat -> {
                    CategoryDTO dto = CategoryDTO.fromEntity(cat);
                    dto.setProductCount(productCounts.getOrDefault(cat.getId(), 0L).intValue());
                    return dto;
                })
                .toList();
    }

    /**
     * Get product counts for all categories as a map.
     * For parent categories, includes products from all descendant categories.
     */
    private Map<UUID, Long> getProductCountsMap() {
        // Get direct product counts per category
        Map<UUID, Long> directCounts = categoryRepository.getProductCountsByCategory().stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> (Long) row[1]
                ));

        // Build hierarchical counts (parent categories include child counts)
        Map<UUID, Long> hierarchicalCounts = new HashMap<>(directCounts);
        List<Category> allCategories = categoryRepository.findAllActiveOrdered();

        // For each category, add counts from all descendants
        for (Category category : allCategories) {
            if (category.getPath() != null) {
                // Find all descendants and sum their counts
                List<UUID> descendantIds = categoryRepository.findDescendantCategoryIds(category.getPath());
                long totalCount = directCounts.getOrDefault(category.getId(), 0L);
                for (UUID descendantId : descendantIds) {
                    totalCount += directCounts.getOrDefault(descendantId, 0L);
                }
                hierarchicalCounts.put(category.getId(), totalCount);
            }
        }

        return hierarchicalCounts;
    }

    @Transactional
    public CategoryDTO createCategory(CreateCategoryRequest request) {
        if (categoryRepository.existsByName(request.getName())) {
            throw ProductException.categoryNameExists(request.getName());
        }

        Category category = Category.builder()
                .name(request.getName())
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .build();

        if (request.getParentCategoryId() != null) {
            Category parent = categoryRepository.findById(request.getParentCategoryId())
                    .orElseThrow(() -> ProductException.categoryNotFound(request.getParentCategoryId().toString()));
            category.setParentCategory(parent);
        }

        category = categoryRepository.save(category);
        log.info("Category created: id={}, name={}", category.getId(), category.getName());

        return CategoryDTO.fromEntity(category);
    }

    @Transactional
    public CategoryDTO updateCategory(UUID id, UpdateCategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> ProductException.categoryNotFound(id.toString()));

        if (request.getName() != null && !request.getName().equals(category.getName())) {
            if (categoryRepository.existsByName(request.getName())) {
                throw ProductException.categoryNameExists(request.getName());
            }
            category.setName(request.getName());
        }

        if (request.getDescription() != null) {
            category.setDescription(request.getDescription());
        }
        if (request.getImageUrl() != null) {
            category.setImageUrl(request.getImageUrl());
        }
        if (request.getActive() != null) {
            category.setActive(request.getActive());
        }
        if (request.getParentCategoryId() != null) {
            Category parent = categoryRepository.findById(request.getParentCategoryId())
                    .orElseThrow(() -> ProductException.categoryNotFound(request.getParentCategoryId().toString()));
            category.setParentCategory(parent);
        }

        category = categoryRepository.save(category);
        log.info("Category updated: id={}", category.getId());

        return CategoryDTO.fromEntity(category);
    }

    @Transactional
    public void deleteCategory(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> ProductException.categoryNotFound(id.toString()));

        category.setActive(false);
        categoryRepository.save(category);
        log.info("Category soft deleted: id={}", id);
    }
}
