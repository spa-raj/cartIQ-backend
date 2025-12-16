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
--   - recent_product_activity (aggregated product views)
--   - cart_activity (aggregated cart events)
--   - session_activity (aggregated session events)
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
-- STEP 3: MAIN USER PROFILE AGGREGATION JOB (JOIN-based)
-- Joins product views, cart activity, and session activity
-- Uses LEFT JOINs with 5-minute interval to handle late data
-- Outputs to user-profiles topic for AI module consumption
-- ============================================================================

INSERT INTO `user-profiles`
SELECT
    -- Primary keys
    p.userId,
    p.sessionId,
    p.timeBucket AS windowBucket,

    -- Deterministic event ID
    CONCAT('profile-', p.userId, '-', p.sessionId, '-', p.timeBucket) AS eventId,

    -- Product activity (from recent_product_activity)
    p.recentProductIds,
    p.recentCategories,
    p.recentSearchQueries,
    p.totalProductViews,

    -- Cart activity (from cart_activity via JOIN)
    COALESCE(c.cartAdds, CAST(0 AS BIGINT)) AS totalCartAdds,

    -- Product metrics
    p.avgViewDurationMs,
    p.avgProductPrice,

    -- Price preference
    CASE
        WHEN p.avgProductPrice < 500 THEN 'BUDGET'
        WHEN p.avgProductPrice < 2000 THEN 'MID_RANGE'
        ELSE 'PREMIUM'
    END AS pricePreference,

    -- Cart state (from cart_activity via JOIN)
    COALESCE(c.currentCartTotal, 0.0) AS currentCartTotal,
    COALESCE(c.currentCartItems, CAST(0 AS BIGINT)) AS currentCartItems,

    -- Session info (from session_activity via JOIN)
    COALESCE(s.sessionDurationMs, CAST(0 AS BIGINT)) AS sessionDurationMs,
    COALESCE(s.deviceType, 'UNKNOWN') AS deviceType,

    -- Window timestamps
    p.windowStart,
    p.windowEnd,

    -- Last updated (use latest event time from product views)
    CAST(DATE_FORMAT(p.lastEventTime, 'yyyy-MM-dd HH:mm:ss') AS STRING) AS lastUpdated

FROM recent_product_activity p

-- LEFT JOIN cart activity on same user and session
-- Cart state is aggregated at session level for reliable matching
LEFT JOIN cart_activity c
    ON p.userId = c.userId
    AND p.sessionId = c.sessionId

-- LEFT JOIN session activity on same user and session
-- Session info is aggregated at session level for reliable matching
LEFT JOIN session_activity s
    ON p.userId = s.userId
    AND p.sessionId = s.sessionId;
