-- ============================================================================
-- CartIQ Flink SQL: Intermediate Materialized Tables
-- Run this FIRST - creates materialized state tables for each event type
-- These tables enable complex JOINs without retraction issues
-- Format: avro-registry (default for Confluent Cloud Flink)
-- ============================================================================

-- ============================================================================
-- STEP 1: HELPER VIEWS - Parse STRING timestamps to TIMESTAMP
-- Auto-generated tables from Kafka topics have `timestamp` as STRING
-- ============================================================================

CREATE VIEW IF NOT EXISTS `product_views_timed` AS
SELECT
    eventId, userId, sessionId, productId, productName,
    category, price, source, searchQuery, viewDurationMs,
    TO_TIMESTAMP(`timestamp`) AS event_time
FROM `product-views`
WHERE `timestamp` IS NOT NULL AND `timestamp` <> '';

CREATE VIEW IF NOT EXISTS `cart_events_timed` AS
SELECT
    eventId, userId, sessionId, action, productId, productName,
    category, quantity, price, cartTotal, cartItemCount,
    TO_TIMESTAMP(`timestamp`) AS event_time
FROM `cart-events`
WHERE `timestamp` IS NOT NULL AND `timestamp` <> '';

CREATE VIEW IF NOT EXISTS `user_events_timed` AS
SELECT
    eventId, userId, sessionId, eventType, pageType,
    pageUrl, deviceType, referrer,
    TO_TIMESTAMP(`timestamp`) AS event_time
FROM `user-events`
WHERE `timestamp` IS NOT NULL AND `timestamp` <> '';

CREATE VIEW IF NOT EXISTS `ai_events_timed` AS
SELECT
    eventId, userId, sessionId, query, searchType, toolName,
    category, minPrice, maxPrice, minRating, resultsCount,
    returnedProductIds, processingTimeMs,
    TO_TIMESTAMP_LTZ(
        CAST(UNIX_TIMESTAMP(SUBSTRING(`timestamp`, 1, 19), 'yyyy-MM-dd''T''HH:mm:ss') AS BIGINT) * 1000,
        3
    ) AS event_time
FROM `ai-events`
WHERE `timestamp` IS NOT NULL AND `timestamp` <> '';

CREATE VIEW IF NOT EXISTS `order_events_timed` AS
SELECT
    eventId, userId, orderId, items, subtotal, discount, total,
    paymentMethod, status, shippingCity, shippingState,
    TO_TIMESTAMP(`timestamp`) AS event_time
FROM `order-events`
WHERE `timestamp` IS NOT NULL AND `timestamp` <> '';

-- Flattened view of order items (UNNEST nested items array)
-- Note: UNNEST column order must match Avro schema field order (alphabetical)
CREATE VIEW IF NOT EXISTS `order_items_flattened` AS
SELECT
    o.userId,
    o.orderId,
    o.total,
    o.discount,
    o.paymentMethod,
    o.status,
    o.event_time,
    i.category,
    i.productId
FROM `order_events_timed` o
CROSS JOIN UNNEST(o.items) AS i(category, price, productId, productName, quantity);

-- ============================================================================
-- STEP 2: MATERIALIZED TABLE - Product Activity
-- Aggregates product views per user/session
-- Primary key enables upsert mode for downstream JOINs
-- ============================================================================
DROP TABLE IF EXISTS `user-product-activity`;

CREATE TABLE `user-product-activity` (
    userId               STRING NOT NULL,
    sessionId            STRING NOT NULL,
    recentProductIds     ARRAY<STRING NOT NULL>,
    recentCategories     ARRAY<STRING NOT NULL>,
    recentSearchQueries  ARRAY<STRING NOT NULL>,
    totalProductViews    BIGINT,
    avgViewDurationMs    BIGINT,
    avgProductPrice      DOUBLE,
    maxViewedPrice       DOUBLE,
    minViewedPrice       DOUBLE,
    lastEventTime        TIMESTAMP(3),
    PRIMARY KEY (userId, sessionId) NOT ENFORCED
) WITH (
    'changelog.mode' = 'upsert'
);

-- ============================================================================
-- STEP 3: MATERIALIZED TABLE - Cart Activity
-- Real-time cart state per user/session
-- ============================================================================
DROP TABLE IF EXISTS `user-cart-activity`;

CREATE TABLE `user-cart-activity` (
    userId               STRING NOT NULL,
    sessionId            STRING NOT NULL,
    currentCartTotal     DOUBLE,
    currentCartItems     BIGINT,
    cartAdds             BIGINT,
    cartRemoves          BIGINT,
    cartProductIds       ARRAY<STRING NOT NULL>,
    cartCategories       ARRAY<STRING NOT NULL>,
    lastEventTime        TIMESTAMP(3),
    PRIMARY KEY (userId, sessionId) NOT ENFORCED
) WITH (
    'changelog.mode' = 'upsert'
);

-- ============================================================================
-- STEP 4: MATERIALIZED TABLE - Session Activity
-- Session-level user behavior
-- ============================================================================
DROP TABLE IF EXISTS `user-session-activity`;

CREATE TABLE `user-session-activity` (
    userId               STRING NOT NULL,
    sessionId            STRING NOT NULL,
    deviceType           STRING,
    totalPageViews       BIGINT,
    uniquePageTypes      BIGINT,
    productPageViews     BIGINT,
    cartPageViews        BIGINT,
    checkoutPageViews    BIGINT,
    sessionDurationMs    BIGINT,
    lastEventTime        TIMESTAMP(3),
    PRIMARY KEY (userId, sessionId) NOT ENFORCED
) WITH (
    'changelog.mode' = 'upsert'
);

-- ============================================================================
-- STEP 5: MATERIALIZED TABLE - AI Search Activity
-- AI chat interactions (strong intent signals)
-- Note: Aggregated at USER level (not session) because:
--   1. AI chat sessionId may differ from browser sessionId
--   2. AI search intent transcends individual sessions
-- ============================================================================
DROP TABLE IF EXISTS `user-ai-activity`;

CREATE TABLE `user-ai-activity` (
    userId               STRING NOT NULL,
    aiSearchCount        BIGINT,
    aiSearchQueries      ARRAY<STRING NOT NULL>,
    aiSearchCategories   ARRAY<STRING NOT NULL>,
    aiAvgMinPrice        DOUBLE,
    aiAvgMaxPrice        DOUBLE,
    aiMaxBudget          DOUBLE,
    aiProductSearches    BIGINT,
    aiProductDetailViews BIGINT,
    aiProductComparisons BIGINT,
    aiTotalResultsShown  BIGINT,
    lastEventTime        TIMESTAMP(3),
    PRIMARY KEY (userId) NOT ENFORCED
) WITH (
    'changelog.mode' = 'upsert'
);

-- ============================================================================
-- STEP 6: MATERIALIZED TABLE - Order Activity
-- Purchase history (highest intent signal - actual conversions)
-- ============================================================================
DROP TABLE IF EXISTS `user-order-activity`;

CREATE TABLE `user-order-activity` (
    userId               STRING NOT NULL,
    totalOrders          BIGINT,
    totalSpent           DOUBLE,
    avgOrderValue        DOUBLE,
    totalDiscount        DOUBLE,
    lastOrderTotal       DOUBLE,
    purchasedCategories  ARRAY<STRING NOT NULL>,
    purchasedProductIds  ARRAY<STRING NOT NULL>,
    preferredPaymentMethod STRING,
    lastOrderStatus      STRING,
    lastEventTime        TIMESTAMP(3),
    PRIMARY KEY (userId) NOT ENFORCED
) WITH (
    'changelog.mode' = 'upsert'
);

-- ============================================================================
-- STEP 7: Set state TTL to prevent unbounded state growth (1 hour)
-- ============================================================================
SET 'sql.state-ttl' = '3600000 ms';
