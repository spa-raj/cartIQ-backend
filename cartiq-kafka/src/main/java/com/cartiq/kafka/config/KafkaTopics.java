package com.cartiq.kafka.config;

/**
 * Kafka topic names for CartIQ.
 * These must match the topics created in Confluent Cloud.
 *
 * Topics:
 * - Input (4): user-events, product-views, cart-events, order-events
 * - Output (1): user-profiles (Flink aggregated context)
 */
public final class KafkaTopics {

    private KafkaTopics() {} // Prevent instantiation

    // ==================== INPUT TOPICS ====================
    // These receive raw events from the application

    /** User session events: login, logout, page visits */
    public static final String USER_EVENTS = "user-events";

    /** Product page views and interactions */
    public static final String PRODUCT_VIEWS = "product-views";

    /** Cart actions: add, remove, update quantity */
    public static final String CART_EVENTS = "cart-events";

    /** Order events: placed, completed, cancelled */
    public static final String ORDER_EVENTS = "order-events";

    /** AI chat and search events */
    public static final String AI_EVENTS = "ai-events";

    // ==================== OUTPUT TOPICS ====================
    // These receive aggregated data from Flink

    /** Flink-aggregated user profiles (consumed by AI module) */
    public static final String USER_PROFILES = "user-profiles";
}
