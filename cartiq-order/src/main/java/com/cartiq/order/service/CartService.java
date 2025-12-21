package com.cartiq.order.service;

import com.cartiq.order.dto.AddToCartRequest;
import com.cartiq.order.dto.CartDTO;
import com.cartiq.order.dto.UpdateCartItemRequest;
import com.cartiq.order.entity.Cart;
import com.cartiq.order.entity.CartItem;
import com.cartiq.order.exception.OrderException;
import com.cartiq.order.repository.CartItemRepository;
import com.cartiq.order.repository.CartRepository;
import com.cartiq.product.dto.ProductDTO;
import com.cartiq.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductService productService;

    @Transactional
    public CartDTO getCart(UUID userId) {
        Cart cart = getOrCreateCart(userId);
        return CartDTO.fromEntity(cart);
    }

    @Transactional
    public CartDTO addToCart(UUID userId, AddToCartRequest request) {
        ProductDTO product = productService.getProductById(request.getProductId());

        if (!product.getInStock()) {
            throw OrderException.productNotAvailable(product.getName());
        }

        if (product.getStockQuantity() < request.getQuantity()) {
            throw OrderException.insufficientStock(
                    product.getName(),
                    product.getStockQuantity(),
                    request.getQuantity()
            );
        }

        Cart cart = getOrCreateCart(userId);

        Optional<CartItem> existingItem = cart.getItems().stream()
                .filter(item -> item.getProductId().equals(request.getProductId()))
                .findFirst();

        if (existingItem.isPresent()) {
            CartItem item = existingItem.get();
            int newQuantity = item.getQuantity() + request.getQuantity();

            if (product.getStockQuantity() < newQuantity) {
                throw OrderException.insufficientStock(
                        product.getName(),
                        product.getStockQuantity(),
                        newQuantity
                );
            }

            item.setQuantity(newQuantity);
            log.info("Updated cart item quantity: cartId={}, productId={}, quantity={}",
                    cart.getId(), request.getProductId(), newQuantity);
        } else {
            CartItem newItem = CartItem.builder()
                    .productId(product.getId())
                    .productName(product.getName())
                    .categoryName(product.getCategoryName())
                    .unitPrice(product.getPrice())
                    .quantity(request.getQuantity())
                    .thumbnailUrl(product.getThumbnailUrl())
                    .build();
            cart.addItem(newItem);
            log.info("Added item to cart: cartId={}, productId={}, quantity={}",
                    cart.getId(), request.getProductId(), request.getQuantity());
        }

        cart = cartRepository.save(cart);
        return CartDTO.fromEntity(cart);
    }

    @Transactional
    public CartDTO updateCartItem(UUID userId, UUID itemId, UpdateCartItemRequest request) {
        Cart cart = cartRepository.findByUserIdWithItems(userId)
                .orElseThrow(() -> OrderException.cartNotFound(userId.toString()));

        CartItem item = cart.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> OrderException.cartItemNotFound(itemId.toString()));

        ProductDTO product = productService.getProductById(item.getProductId());

        if (product.getStockQuantity() < request.getQuantity()) {
            throw OrderException.insufficientStock(
                    product.getName(),
                    product.getStockQuantity(),
                    request.getQuantity()
            );
        }

        item.setQuantity(request.getQuantity());
        item.setUnitPrice(product.getPrice());

        cart = cartRepository.save(cart);
        log.info("Updated cart item: cartId={}, itemId={}, quantity={}",
                cart.getId(), itemId, request.getQuantity());

        return CartDTO.fromEntity(cart);
    }

    @Transactional
    public CartDTO removeFromCart(UUID userId, UUID itemId) {
        Cart cart = cartRepository.findByUserIdWithItems(userId)
                .orElseThrow(() -> OrderException.cartNotFound(userId.toString()));

        CartItem item = cart.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> OrderException.cartItemNotFound(itemId.toString()));

        cart.removeItem(item);
        cart = cartRepository.save(cart);

        log.info("Removed item from cart: cartId={}, itemId={}", cart.getId(), itemId);

        return CartDTO.fromEntity(cart);
    }

    @Transactional
    public void clearCart(UUID userId) {
        Cart cart = cartRepository.findByUserIdWithItems(userId)
                .orElseThrow(() -> OrderException.cartNotFound(userId.toString()));

        cart.clearItems();
        cartRepository.save(cart);

        log.info("Cleared cart: cartId={}, userId={}", cart.getId(), userId);
    }

    @Transactional(readOnly = true)
    public int getCartItemCount(UUID userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .map(Cart::getTotalItems)
                .orElse(0);
    }

    private Cart getOrCreateCart(UUID userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .orElseGet(() -> {
                    Cart newCart = Cart.builder()
                            .userId(userId)
                            .build();
                    newCart = cartRepository.save(newCart);
                    log.info("Created new cart for user: userId={}, cartId={}",
                            userId, newCart.getId());
                    return newCart;
                });
    }
}
