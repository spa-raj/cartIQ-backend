-- ============================================================================
-- CartIQ Flink SQL: Intermediate Aggregations for Confluent Cloud
-- Run this before 02-user-profiles.sql
-- Uses timestamp-based bucketing (since auto-generated tables have STRING timestamps)
-- Format: avro-registry (default for Confluent Cloud Flink)
-- ============================================================================

-- ============================================================================
-- HELPER VIEWS: Parse STRING timestamps to TIMESTAMP
-- Auto-generated tables from Kafka topics have `timestamp` as STRING
-- ============================================================================

CREATE VIEW `product_views_timed` AS
SELECT
    eventId, userId, sessionId, productId, productName,
    category, price, source, searchQuery, viewDurationMs,
    TO_TIMESTAMP(`timestamp`) AS event_time
FROM `product-views`;

CREATE VIEW `cart_events_timed` AS
SELECT
    eventId, userId, sessionId, action, productId, productName,
    category, quantity, price, cartTotal, cartItemCount,
    TO_TIMESTAMP(`timestamp`) AS event_time
FROM `cart-events`;

CREATE VIEW `user_events_timed` AS
SELECT
    eventId, userId, sessionId, eventType, pageType,
    pageUrl, deviceType, referrer,
    TO_TIMESTAMP(`timestamp`) AS event_time
FROM `user-events`;

-- ============================================================================
-- 1. RECENT PRODUCT VIEWS (5-minute buckets)
-- Aggregates recently viewed products per user session
-- Source: product-views topic (Avro schema)
-- ============================================================================
CREATE VIEW recent_product_activity AS
SELECT
    userId,
    sessionId,
    -- Collect recent product IDs (last 10)
    ARRAY_SLICE(
        ARRAY_AGG(productId),
        1, 10
    ) AS recentProductIds,
    -- Collect recent categories (deduplicated, last 5)
    ARRAY_SLICE(
        ARRAY_DISTINCT(ARRAY_AGG(category)),
        1, 5
    ) AS recentCategories,
    -- Collect search queries that led to views
    ARRAY_DISTINCT(ARRAY_AGG(searchQuery)) AS recentSearchQueries,
    -- Metrics
    COUNT(*) AS totalProductViews,
    CAST(COALESCE(AVG(viewDurationMs), 0) AS BIGINT) AS avgViewDurationMs,
    COALESCE(AVG(price), 0.0) AS avgProductPrice,
    MAX(price) AS maxViewedPrice,
    MIN(price) AS minViewedPrice,
    -- Window bounds (use MIN to get window start, add interval for end)
    CAST(DATE_FORMAT(MIN(event_time), 'yyyy-MM-dd HH:mm:00') AS STRING) AS windowStart,
    CAST(DATE_FORMAT(MIN(event_time) + INTERVAL '5' MINUTE, 'yyyy-MM-dd HH:mm:00') AS STRING) AS windowEnd
FROM `product_views_timed`
GROUP BY
    userId,
    sessionId,
    DATE_FORMAT(event_time, 'yyyy-MM-dd HH:mm');


-- ============================================================================
-- 2. CART ACTIVITY (1-minute buckets)
-- Real-time cart state per user session
-- Source: cart-events topic (Avro schema)
-- Note: action field contains enum values as strings (ADD, REMOVE, etc.)
-- ============================================================================
CREATE VIEW cart_activity AS
SELECT
    userId,
    sessionId,
    -- Latest cart state (from most recent event)
    LAST_VALUE(cartTotal) AS currentCartTotal,
    LAST_VALUE(cartItemCount) AS currentCartItems,
    -- Cart action counts (enum values are uppercase strings)
    COUNT(*) FILTER (WHERE action = 'ADD') AS cartAdds,
    COUNT(*) FILTER (WHERE action = 'REMOVE') AS cartRemoves,
    -- Products added to cart
    ARRAY_DISTINCT(
        ARRAY_AGG(productId) FILTER (WHERE action = 'ADD')
    ) AS cartProductIds,
    -- Categories in cart
    ARRAY_DISTINCT(
        ARRAY_AGG(category) FILTER (WHERE action = 'ADD')
    ) AS cartCategories,
    -- Window bounds (use MIN to get window start, add interval for end)
    CAST(DATE_FORMAT(MIN(event_time), 'yyyy-MM-dd HH:mm:00') AS STRING) AS windowStart,
    CAST(DATE_FORMAT(MIN(event_time) + INTERVAL '1' MINUTE, 'yyyy-MM-dd HH:mm:00') AS STRING) AS windowEnd
FROM `cart_events_timed`
GROUP BY
    userId,
    sessionId,
    DATE_FORMAT(event_time, 'yyyy-MM-dd HH:mm');


-- ============================================================================
-- 3. SESSION ACTIVITY (1-minute buckets)
-- Session-level user behavior aggregation
-- Source: user-events topic (Avro schema)
-- Note: eventType and pageType are enum values as strings (uppercase)
-- ============================================================================
CREATE VIEW session_activity AS
SELECT
    userId,
    sessionId,
    -- Device info (from first event)
    FIRST_VALUE(deviceType) AS deviceType,
    -- Page navigation
    COUNT(*) AS totalPageViews,
    COUNT(DISTINCT pageType) AS uniquePageTypes,
    -- Engagement signals (enum values are uppercase strings)
    COUNT(*) FILTER (WHERE eventType = 'PAGE_VIEW' AND pageType = 'PRODUCT') AS productPageViews,
    COUNT(*) FILTER (WHERE eventType = 'PAGE_VIEW' AND pageType = 'CART') AS cartPageViews,
    COUNT(*) FILTER (WHERE eventType = 'PAGE_VIEW' AND pageType = 'CHECKOUT') AS checkoutPageViews,
    -- Window bounds (use MIN to get window start, add interval for end)
    CAST(DATE_FORMAT(MIN(event_time), 'yyyy-MM-dd HH:mm:00') AS STRING) AS windowStart,
    CAST(DATE_FORMAT(MIN(event_time) + INTERVAL '1' MINUTE, 'yyyy-MM-dd HH:mm:00') AS STRING) AS windowEnd
FROM `user_events_timed`
GROUP BY
    userId,
    sessionId,
    DATE_FORMAT(event_time, 'yyyy-MM-dd HH:mm');


-- ============================================================================
-- 4. PRICE PREFERENCE CALCULATION
-- Determines user's price tier based on viewed/carted products
-- ============================================================================
CREATE VIEW price_preferences AS
SELECT
    userId,
    sessionId,
    avgProductPrice,
    CASE
        WHEN avgProductPrice < 500 THEN 'BUDGET'
        WHEN avgProductPrice < 2000 THEN 'MID_RANGE'
        ELSE 'PREMIUM'
    END AS pricePreference,
    windowStart,
    windowEnd
FROM recent_product_activity;
