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
FROM `product-views`
WHERE `timestamp` IS NOT NULL AND `timestamp` <> '';

CREATE VIEW `cart_events_timed` AS
SELECT
    eventId, userId, sessionId, action, productId, productName,
    category, quantity, price, cartTotal, cartItemCount,
    TO_TIMESTAMP(`timestamp`) AS event_time
FROM `cart-events`
WHERE `timestamp` IS NOT NULL AND `timestamp` <> '';

CREATE VIEW `user_events_timed` AS
SELECT
    eventId, userId, sessionId, eventType, pageType,
    pageUrl, deviceType, referrer,
    TO_TIMESTAMP(`timestamp`) AS event_time
FROM `user-events`
WHERE `timestamp` IS NOT NULL AND `timestamp` <> '';

-- ============================================================================
-- 1. RECENT PRODUCT VIEWS (5-minute buckets)
-- Aggregates recently viewed products per user session
-- Source: product-views topic (Avro schema)
-- ============================================================================
CREATE VIEW recent_product_activity AS
SELECT
    userId,
    sessionId,
    -- Time bucket for JOIN key
    DATE_FORMAT(event_time, 'yyyy-MM-dd HH:mm') AS timeBucket,
    -- Collect recent product IDs (last 10)
    COALESCE(
        ARRAY_SLICE(ARRAY_AGG(productId), 1, 10),
        ARRAY['']
    ) AS recentProductIds,
    -- Collect recent categories (deduplicated, last 5)
    COALESCE(
        ARRAY_SLICE(ARRAY_DISTINCT(ARRAY_AGG(category)), 1, 5),
        ARRAY['']
    ) AS recentCategories,
    -- Collect search queries that led to views
    COALESCE(
        ARRAY_DISTINCT(ARRAY_AGG(searchQuery)),
        ARRAY['']
    ) AS recentSearchQueries,
    -- Metrics
    COUNT(*) AS totalProductViews,
    CAST(COALESCE(AVG(viewDurationMs), 0) AS BIGINT) AS avgViewDurationMs,
    COALESCE(AVG(price), 0.0) AS avgProductPrice,
    COALESCE(MAX(price), 0.0) AS maxViewedPrice,
    COALESCE(MIN(price), 0.0) AS minViewedPrice,
    -- Window bounds
    CAST(DATE_FORMAT(MIN(event_time), 'yyyy-MM-dd HH:mm:00') AS STRING) AS windowStart,
    CAST(DATE_FORMAT(MIN(event_time) + INTERVAL '5' MINUTE, 'yyyy-MM-dd HH:mm:00') AS STRING) AS windowEnd,
    -- Latest event time for lastUpdated
    MAX(event_time) AS lastEventTime
FROM `product_views_timed`
WHERE event_time IS NOT NULL
GROUP BY
    userId,
    sessionId,
    DATE_FORMAT(event_time, 'yyyy-MM-dd HH:mm');


-- ============================================================================
-- 2. CART ACTIVITY (Session-level aggregation)
-- Real-time cart state per user session
-- Source: cart-events topic (Avro schema)
-- Note: action field contains enum values as strings (ADD, REMOVE, etc.)
-- Aggregated at session level (not time-bucketed) for reliable JOINs
-- ============================================================================
CREATE VIEW cart_activity AS
SELECT
    userId,
    sessionId,
    -- Latest cart state (from most recent event)
    COALESCE(LAST_VALUE(cartTotal), 0.0) AS currentCartTotal,
    COALESCE(LAST_VALUE(cartItemCount), 0) AS currentCartItems,
    -- Cart action counts (enum values are uppercase strings)
    COUNT(*) FILTER (WHERE action = 'ADD') AS cartAdds,
    COUNT(*) FILTER (WHERE action = 'REMOVE') AS cartRemoves,
    -- Products added to cart (use CASE WHEN to handle no ADD actions)
    CASE
        WHEN COUNT(*) FILTER (WHERE action = 'ADD') > 0
        THEN ARRAY_DISTINCT(ARRAY_AGG(productId) FILTER (WHERE action = 'ADD'))
        ELSE ARRAY['']
    END AS cartProductIds,
    -- Categories in cart (use CASE WHEN to handle no ADD actions)
    CASE
        WHEN COUNT(*) FILTER (WHERE action = 'ADD') > 0
        THEN ARRAY_DISTINCT(ARRAY_AGG(category) FILTER (WHERE action = 'ADD'))
        ELSE ARRAY['']
    END AS cartCategories
FROM `cart_events_timed`
WHERE event_time IS NOT NULL
GROUP BY
    userId,
    sessionId;


-- ============================================================================
-- 3. SESSION ACTIVITY (Session-level aggregation)
-- Session-level user behavior aggregation
-- Source: user-events topic (Avro schema)
-- Note: eventType and pageType are enum values as strings (uppercase)
-- Aggregated at session level (not time-bucketed) for reliable JOINs
-- ============================================================================
CREATE VIEW session_activity AS
SELECT
    userId,
    sessionId,
    -- Device info (from first event)
    COALESCE(FIRST_VALUE(deviceType), 'UNKNOWN') AS deviceType,
    -- Page navigation
    COUNT(*) AS totalPageViews,
    COUNT(DISTINCT pageType) AS uniquePageTypes,
    -- Engagement signals (enum values are uppercase strings)
    COUNT(*) FILTER (WHERE eventType = 'PAGE_VIEW' AND pageType = 'PRODUCT') AS productPageViews,
    COUNT(*) FILTER (WHERE eventType = 'PAGE_VIEW' AND pageType = 'CART') AS cartPageViews,
    COUNT(*) FILTER (WHERE eventType = 'PAGE_VIEW' AND pageType = 'CHECKOUT') AS checkoutPageViews,
    -- Session duration in milliseconds (difference between first and last event)
    COALESCE(
        CAST(TIMESTAMPDIFF(SECOND, MIN(event_time), MAX(event_time)) * 1000 AS BIGINT),
        CAST(0 AS BIGINT)
    ) AS sessionDurationMs
FROM `user_events_timed`
WHERE event_time IS NOT NULL
GROUP BY
    userId,
    sessionId;


-- ============================================================================
-- 4. PRICE PREFERENCE CALCULATION
-- Determines user's price tier based on viewed/carted products
-- ============================================================================
CREATE VIEW price_preferences AS
SELECT
    userId,
    sessionId,
    timeBucket,
    avgProductPrice,
    CASE
        WHEN avgProductPrice < 500 THEN 'BUDGET'
        WHEN avgProductPrice < 2000 THEN 'MID_RANGE'
        ELSE 'PREMIUM'
    END AS pricePreference,
    windowStart,
    windowEnd
FROM recent_product_activity;
