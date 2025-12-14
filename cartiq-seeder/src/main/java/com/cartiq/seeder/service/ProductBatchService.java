package com.cartiq.seeder.service;

import com.cartiq.common.enums.Currency;
import com.cartiq.common.enums.Gender;
import com.cartiq.product.entity.Category;
import com.cartiq.product.entity.Product;
import com.cartiq.product.entity.Product.ProductStatus;
import com.cartiq.product.repository.ProductRepository;
import com.cartiq.seeder.dto.AmazonProductJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Helper service for batch processing products with proper transaction boundaries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductBatchService {

    private final ProductRepository productRepository;
    private final CategorySeederService categorySeederService;

    private final Random random = new Random(42);

    /**
     * Imports a single product in its own transaction.
     * Returns 1 if imported, 0 if skipped, -1 if error.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int importProductInTransaction(AmazonProductJson json) {
        try {
            String sku = json.getUniqId().trim();

            if (productRepository.existsBySku(sku)) {
                return 0; // skipped
            }

            BigDecimal price = json.getSellingPrice();
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

            Product product = Product.builder()
                    .sku(sku)
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

            productRepository.save(product);
            return 1; // success
        } catch (Exception e) {
            log.warn("Failed to import product {}: {}", json.getUniqId(), e.getMessage());
            return -1; // error
        }
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
