package com.cartiq.seeder.service;

import com.cartiq.common.enums.Currency;
import com.cartiq.common.enums.Gender;
import com.cartiq.product.entity.Category;
import com.cartiq.product.entity.Product;
import com.cartiq.product.entity.Product.ProductStatus;
import com.cartiq.product.repository.ProductRepository;
import com.cartiq.seeder.dto.AmazonProductJson;
import com.cartiq.seeder.service.ProductSeederServiceFast.BatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FAST batch processing service.
 * Key optimizations:
 * 1. Batch existsBySku check (single query for entire batch)
 * 2. Batch saveAll (single transaction for entire batch)
 * 3. Pre-computed categories (cached)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductBatchServiceFast {

    private final ProductRepository productRepository;
    private final CategorySeederService categorySeederService;

    private final Random random = new Random(42);

    /**
     * Imports a batch of products in a single transaction.
     * Much faster than individual inserts.
     */
    @Transactional
    public BatchResult importBatch(List<AmazonProductJson> batch) {
        if (batch.isEmpty()) {
            return new BatchResult(0, 0, 0);
        }

        int success = 0;
        int skipped = 0;
        int errors = 0;

        try {
            // 1. Extract all SKUs from batch
            Set<String> batchSkus = batch.stream()
                    .map(j -> j.getUniqId().trim())
                    .collect(Collectors.toSet());

            // 2. Single query to find existing SKUs
            Set<String> existingSkus = productRepository.findExistingSkus(batchSkus);

            // 3. Filter out already existing products
            List<Product> productsToSave = new ArrayList<>();

            for (AmazonProductJson json : batch) {
                String sku = json.getUniqId().trim();

                if (existingSkus.contains(sku)) {
                    skipped++;
                    continue;
                }

                try {
                    Product product = convertToProduct(json);
                    if (product != null) {
                        productsToSave.add(product);
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    errors++;
                }
            }

            // 4. Batch save all products at once
            if (!productsToSave.isEmpty()) {
                productRepository.saveAll(productsToSave);
                success = productsToSave.size();
            }

        } catch (Exception e) {
            log.error("Batch import failed: {}", e.getMessage());
            errors = batch.size();
        }

        return new BatchResult(success, skipped, errors);
    }

    private Product convertToProduct(AmazonProductJson json) {
        BigDecimal price = json.getSellingPrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal compareAtPrice = json.getListPrice();
        if (compareAtPrice != null && compareAtPrice.compareTo(price) <= 0) {
            compareAtPrice = null;
        }

        Category category = categorySeederService.getOrCreateCategoryFromLdjson(
                json.getPrimaryCategoryName(),
                json.getSubCategoryName()
        );

        Currency currency = parseCurrency(json.getCurrency());
        Gender gender = parseGender(json.getGender());

        BigDecimal rating = json.getStarRatings();
        if (rating == null) {
            rating = BigDecimal.valueOf(3.5 + random.nextDouble() * 1.5).setScale(1, RoundingMode.HALF_UP);
        }

        Integer reviewCount = json.getReviewsCount();
        if (reviewCount == null || reviewCount == 0) {
            reviewCount = generateReviewCount(rating);
        }

        ProductStatus status = parseStockStatus(json.getStockStatus());
        int stockQuantity = status == ProductStatus.ACTIVE ? generateStockQuantity() : 0;
        boolean featured = json.getProductRank() != null && json.getProductRank() <= 50;

        List<String> imageUrls = json.getImageUrls() != null ? json.getImageUrls() : new ArrayList<>();
        String thumbnailUrl = imageUrls.isEmpty() ? null : imageUrls.get(0);

        return Product.builder()
                .sku(json.getUniqId().trim())
                .name(truncate(json.getProductName(), 255))
                .description(truncate(json.getBestDescription(), 2000))
                .price(price.setScale(2, RoundingMode.HALF_UP))
                .compareAtPrice(compareAtPrice != null ? compareAtPrice.setScale(2, RoundingMode.HALF_UP) : null)
                .currency(currency)
                .brand(truncate(json.getBrandName(), 100))
                .gender(gender)
                .category(category)
                .imageUrls(imageUrls)
                .thumbnailUrl(thumbnailUrl)
                .rating(rating.setScale(1, RoundingMode.HALF_UP))
                .reviewCount(reviewCount)
                .stockQuantity(stockQuantity)
                .status(status)
                .featured(featured)
                .build();
    }

    private Currency parseCurrency(String currencyStr) {
        if (currencyStr == null || currencyStr.isBlank()) {
            return Currency.INR;
        }
        try {
            return Currency.valueOf(currencyStr.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return Currency.INR;
        }
    }

    private Gender parseGender(String genderStr) {
        if (genderStr == null || genderStr.isBlank()) {
            return null;
        }
        String normalized = genderStr.trim().toUpperCase();
        return switch (normalized) {
            case "MEN", "MALE", "MENS", "MEN'S" -> Gender.MEN;
            case "WOMEN", "FEMALE", "WOMENS", "WOMEN'S" -> Gender.WOMEN;
            case "UNISEX" -> Gender.UNISEX;
            case "BOYS", "BOY" -> Gender.BOYS;
            case "GIRLS", "GIRL" -> Gender.GIRLS;
            case "KIDS", "CHILDREN", "KID" -> Gender.KIDS;
            default -> null;
        };
    }

    private ProductStatus parseStockStatus(String stockStatus) {
        if (stockStatus == null || stockStatus.isBlank()) {
            return ProductStatus.ACTIVE;
        }
        String normalized = stockStatus.trim().toLowerCase();
        return switch (normalized) {
            case "in_stock", "instock", "available" -> ProductStatus.ACTIVE;
            case "out_of_stock", "outofstock", "unavailable" -> ProductStatus.OUT_OF_STOCK;
            case "discontinued" -> ProductStatus.DISCONTINUED;
            default -> ProductStatus.ACTIVE;
        };
    }

    private int generateReviewCount(BigDecimal rating) {
        if (rating == null) {
            return random.nextInt(50);
        }
        return rating.intValue() * 20 + random.nextInt(100);
    }

    private int generateStockQuantity() {
        return 10 + random.nextInt(491);
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        str = str.trim();
        return str.length() <= maxLength ? str : str.substring(0, maxLength - 3) + "...";
    }
}
