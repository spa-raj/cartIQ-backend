package com.cartiq.order.dto;

import com.cartiq.order.entity.OrderItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemDTO {

    private UUID id;
    private UUID productId;
    private String productSku;
    private String productName;
    private String category;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal subtotal;
    private String thumbnailUrl;
    private LocalDateTime createdAt;

    public static OrderItemDTO fromEntity(OrderItem item) {
        return OrderItemDTO.builder()
                .id(item.getId())
                .productId(item.getProductId())
                .productSku(item.getProductSku())
                .productName(item.getProductName())
                .category(item.getCategory())
                .unitPrice(item.getUnitPrice())
                .quantity(item.getQuantity())
                .subtotal(item.getSubtotal())
                .thumbnailUrl(item.getThumbnailUrl())
                .createdAt(item.getCreatedAt())
                .build();
    }
}
