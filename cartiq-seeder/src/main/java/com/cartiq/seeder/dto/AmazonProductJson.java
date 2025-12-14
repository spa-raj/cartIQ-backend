package com.cartiq.seeder.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO for parsing Amazon India LDJSON dataset.
 * Maps fields from the popular_ecomm_marketplace dataset.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AmazonProductJson {

    @JsonProperty("uniq_id")
    private String uniqId;

    @JsonProperty("product_name")
    private String productName;

    @JsonProperty("short_description")
    private String shortDescription;

    @JsonProperty("long_description")
    private String longDescription;

    @JsonProperty("brand_name")
    private String brandName;

    @JsonProperty("manufacturer_name")
    private String manufacturerName;

    @JsonProperty("selling_price")
    private BigDecimal sellingPrice;

    @JsonProperty("list_price")
    private BigDecimal listPrice;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("discount_percentage")
    private Integer discountPercentage;

    @JsonProperty("image_urls")
    private List<String> imageUrls;

    @JsonProperty("primary_category_name")
    private String primaryCategoryName;

    @JsonProperty("sub_category_name")
    private List<String> subCategoryName;

    @JsonProperty("rss_category")
    private String rssCategory;

    @JsonProperty("stock_status")
    private String stockStatus;

    @JsonProperty("star_ratings")
    private BigDecimal starRatings;

    @JsonProperty("reviews_count")
    private Integer reviewsCount;

    @JsonProperty("product_rank")
    private Integer productRank;

    @JsonProperty("gender")
    private String gender;

    @JsonProperty("market")
    private String market;

    @JsonProperty("product_variant_id")
    private String productVariantId;

    @JsonProperty("product_variant_name")
    private String productVariantName;

    @JsonProperty("is_variant")
    private Boolean isVariant;

    @JsonProperty("is_parent")
    private Boolean isParent;

    @JsonProperty("weekly_units_sold")
    private BigDecimal weeklyUnitsSold;

    /**
     * Get the best description available.
     */
    public String getBestDescription() {
        if (shortDescription != null && !shortDescription.isBlank()) {
            // Clean up JavaScript artifacts from description
            String desc = shortDescription;
            int jsIndex = desc.indexOf("var ");
            if (jsIndex > 0) {
                desc = desc.substring(0, jsIndex).trim();
            }
            int pWhenIndex = desc.indexOf("P.when(");
            if (pWhenIndex > 0) {
                desc = desc.substring(0, pWhenIndex).trim();
            }
            return desc;
        }
        return longDescription;
    }

    /**
     * Check if this is a valid product for import.
     */
    public boolean isValid() {
        return uniqId != null && !uniqId.isBlank()
                && productName != null && !productName.isBlank()
                && sellingPrice != null && sellingPrice.compareTo(BigDecimal.ZERO) > 0;
    }
}
