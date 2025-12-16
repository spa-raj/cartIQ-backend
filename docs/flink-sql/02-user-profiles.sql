-- ============================================================================
-- CartIQ Flink SQL: User Profile Generation
-- Run this after 01-aggregations.sql
-- This is the main job that outputs to user-profiles topic
-- Format: avro-registry (default for Confluent Cloud Flink)
-- ============================================================================

-- ============================================================================
-- PREREQUISITE: Run 01-aggregations.sql first to create:
--   - product_views_timed (view with parsed timestamp)
--   - cart_events_timed (view with parsed timestamp)
--   - user_events_timed (view with parsed timestamp)
-- ============================================================================

-- ============================================================================
-- STEP 1: Create the user-profiles sink table with explicit schema
-- This is required because the topic may not exist or has raw bytes schema
-- ============================================================================
DROP TABLE IF EXISTS `user-profiles`;

CREATE TABLE `user-profiles` (
    userId               STRING NOT NULL,
    sessionId            STRING NOT NULL,
    windowBucket         STRING NOT NULL,
    eventId              STRING NOT NULL,
    recentProductIds     ARRAY<STRING NOT NULL> NOT NULL,
    recentCategories     ARRAY<STRING NOT NULL> NOT NULL,
    recentSearchQueries  ARRAY<STRING NOT NULL> NOT NULL,
    totalProductViews    BIGINT NOT NULL,
    totalCartAdds        BIGINT NOT NULL,
    avgViewDurationMs    BIGINT NOT NULL,
    avgProductPrice      DOUBLE NOT NULL,
    pricePreference      STRING NOT NULL,
    currentCartTotal     DOUBLE NOT NULL,
    currentCartItems     BIGINT NOT NULL,
    sessionDurationMs    BIGINT NOT NULL,
    deviceType           STRING NOT NULL,
    windowStart          STRING NOT NULL,
    windowEnd            STRING NOT NULL,
    lastUpdated          STRING NOT NULL,
    PRIMARY KEY (userId, sessionId, windowBucket) NOT ENFORCED
) WITH (
    'changelog.mode' = 'upsert'
);

-- ============================================================================
-- STEP 2: Set state TTL to prevent unbounded state growth (1 hour = 3600000 ms)
-- ============================================================================
SET 'sql.state-ttl' = '3600000 ms';

-- ============================================================================
-- STEP 3: MAIN USER PROFILE AGGREGATION JOB (Simplified - No JOINs)
-- Aggregates product views into user profiles
-- Uses simple GROUP BY with timestamp-based bucketing
-- Outputs to user-profiles topic for AI module consumption
-- ============================================================================

INSERT INTO `user-profiles`
SELECT
    userId,
    sessionId,
    -- Window bucket directly from GROUP BY (matches upsert key)
    time_bucket AS windowBucket,
    -- Use deterministic eventId
    CONCAT('profile-', userId, '-', sessionId, '-', time_bucket) AS eventId,

    -- Recent products (last 10)
    ARRAY_SLICE(ARRAY_AGG(productId), 1, 10) AS recentProductIds,
    -- Recent categories (deduplicated, last 5)
    ARRAY_SLICE(ARRAY_DISTINCT(ARRAY_AGG(category)), 1, 5) AS recentCategories,
    -- Search queries
    ARRAY_DISTINCT(ARRAY_AGG(searchQuery)) AS recentSearchQueries,

    -- Metrics
    COUNT(*) AS totalProductViews,
    CAST(0 AS BIGINT) AS totalCartAdds,
    CAST(COALESCE(AVG(viewDurationMs), 0) AS BIGINT) AS avgViewDurationMs,
    COALESCE(AVG(price), 0.0) AS avgProductPrice,

    -- Price preference based on average viewed price
    CASE
        WHEN AVG(price) < 500 THEN 'BUDGET'
        WHEN AVG(price) < 2000 THEN 'MID_RANGE'
        ELSE 'PREMIUM'
    END AS pricePreference,

    -- Cart state (placeholder - would need separate stream)
    CAST(0.0 AS DOUBLE) AS currentCartTotal,
    CAST(0 AS BIGINT) AS currentCartItems,

    -- Session info (placeholder)
    CAST(0 AS BIGINT) AS sessionDurationMs,
    'UNKNOWN' AS deviceType,

    -- Timestamps (derived from time_bucket)
    CONCAT(time_bucket, ':00') AS windowStart,
    CAST(DATE_FORMAT(TO_TIMESTAMP(CONCAT(time_bucket, ':00')) + INTERVAL '5' MINUTE, 'yyyy-MM-dd HH:mm:ss') AS STRING) AS windowEnd,
    -- Use MAX event_time as lastUpdated (deterministic)
    CAST(DATE_FORMAT(MAX(event_time), 'yyyy-MM-dd HH:mm:ss') AS STRING) AS lastUpdated

FROM (
    SELECT
        userId,
        sessionId,
        productId,
        category,
        searchQuery,
        viewDurationMs,
        price,
        event_time,
        COALESCE(DATE_FORMAT(event_time, 'yyyy-MM-dd HH:mm'), 'unknown') AS time_bucket
    FROM `product_views_timed`
    WHERE event_time IS NOT NULL
)
GROUP BY
    userId,
    sessionId,
    time_bucket;
