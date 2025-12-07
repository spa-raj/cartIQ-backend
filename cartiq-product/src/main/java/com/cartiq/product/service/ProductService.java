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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public Page<ProductDTO> getAllProducts(Pageable pageable) {
        return productRepository.findByStatus(ProductStatus.ACTIVE, pageable)
                .map(ProductDTO::fromEntity);
    }

    public ProductDTO getProductById(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> ProductException.productNotFound(id.toString()));
        return ProductDTO.fromEntity(product);
    }

    public ProductDTO getProductBySku(String sku) {
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> ProductException.productNotFound(sku));
        return ProductDTO.fromEntity(product);
    }

    public Page<ProductDTO> getProductsByCategory(UUID categoryId, Pageable pageable) {
        return productRepository.findByCategoryIdAndStatus(categoryId, ProductStatus.ACTIVE, pageable)
                .map(ProductDTO::fromEntity);
    }

    public Page<ProductDTO> getProductsByBrand(String brand, Pageable pageable) {
        return productRepository.findByBrand(brand, pageable)
                .map(ProductDTO::fromEntity);
    }

    public Page<ProductDTO> searchProducts(String query, Pageable pageable) {
        return productRepository.search(query, pageable)
                .map(ProductDTO::fromEntity);
    }

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

    public Page<ProductDTO> getFeaturedProducts(Pageable pageable) {
        return productRepository.findFeaturedProducts(pageable)
                .map(ProductDTO::fromEntity);
    }

    public List<String> getAllBrands() {
        return productRepository.findAllBrands();
    }

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
}
