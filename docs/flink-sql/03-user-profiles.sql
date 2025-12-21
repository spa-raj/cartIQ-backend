-- ============================================================================
-- CartIQ Flink SQL: User Profile Generation (Final JOIN)
-- Run this THIRD (after 01-aggregations.sql and 02-activity-inserts.sql)
-- JOINs materialized activity tables to create comprehensive user profiles
-- Format: avro-registry (default for Confluent Cloud Flink)
-- ============================================================================

-- ============================================================================
-- STEP 1: Create the user-profiles sink table
-- This is the final output consumed by the AI recommendation engine
-- ============================================================================
DROP TABLE IF EXISTS `user-profiles`;

CREATE TABLE `user-profiles` (
    -- Primary identifiers
    userId               STRING NOT NULL,
    sessionId            STRING NOT NULL,

    -- Product browsing activity
    recentProductIds     ARRAY<STRING NOT NULL>,
    recentCategories     ARRAY<STRING NOT NULL>,
    recentSearchQueries  ARRAY<STRING NOT NULL>,
    totalProductViews    BIGINT,
    avgViewDurationMs    BIGINT,
    avgProductPrice      DOUBLE,

    -- Price preference (derived from browsing + AI signals)
    pricePreference      STRING,

    -- Cart state
    currentCartTotal     DOUBLE,
    currentCartItems     BIGINT,
    cartAdds             BIGINT,
    cartProductIds       ARRAY<STRING NOT NULL>,
    cartCategories       ARRAY<STRING NOT NULL>,

    -- Session info
    deviceType           STRING,
    sessionDurationMs    BIGINT,
    totalPageViews       BIGINT,
    productPageViews     BIGINT,
    cartPageViews        BIGINT,
    checkoutPageViews    BIGINT,

    -- AI Search Activity (strong intent signals)
    aiSearchCount        BIGINT,
    aiSearchQueries      ARRAY<STRING NOT NULL>,
    aiSearchCategories   ARRAY<STRING NOT NULL>,
    aiMaxBudget          DOUBLE,
    aiProductSearches    BIGINT,
    aiProductComparisons BIGINT,

    -- Order History (HIGHEST intent - actual purchases)
    totalOrders          BIGINT,
    totalSpent           DOUBLE,
    avgOrderValue        DOUBLE,
    lastOrderTotal       DOUBLE,
    preferredPaymentMethod STRING,

    -- Timestamps
    lastUpdated          STRING,

    PRIMARY KEY (userId, sessionId) NOT ENFORCED
) WITH (
    'changelog.mode' = 'upsert'
);

-- ============================================================================
-- STEP 2: Set state TTL
-- ============================================================================
SET 'sql.state-ttl' = '3600000 ms';

-- ============================================================================
-- STEP 3: MAIN USER PROFILE JOIN JOB
-- JOINs all materialized activity tables
-- Product activity is the driver (LEFT JOINs for optional data)
-- ============================================================================
INSERT INTO `user-profiles`
SELECT
    -- Primary identifiers
    p.userId,
    p.sessionId,

    -- Product browsing activity
    p.recentProductIds,
    p.recentCategories,
    p.recentSearchQueries,
    p.totalProductViews,
    p.avgViewDurationMs,
    p.avgProductPrice,

    -- Price preference (combine browsing + AI search signals)
    CASE
        WHEN COALESCE(a.aiMaxBudget, 0.0) > 0 AND COALESCE(a.aiMaxBudget, 0.0) < 500 THEN 'BUDGET'
        WHEN COALESCE(a.aiMaxBudget, 0.0) > 0 AND COALESCE(a.aiMaxBudget, 0.0) < 2000 THEN 'MID_RANGE'
        WHEN COALESCE(a.aiMaxBudget, 0.0) > 0 THEN 'PREMIUM'
        WHEN COALESCE(p.avgProductPrice, 0.0) < 500 THEN 'BUDGET'
        WHEN COALESCE(p.avgProductPrice, 0.0) < 2000 THEN 'MID_RANGE'
        ELSE 'PREMIUM'
    END AS pricePreference,

    -- Cart state
    COALESCE(c.currentCartTotal, 0.0) AS currentCartTotal,
    COALESCE(c.currentCartItems, CAST(0 AS BIGINT)) AS currentCartItems,
    COALESCE(c.cartAdds, CAST(0 AS BIGINT)) AS cartAdds,
    COALESCE(c.cartProductIds, ARRAY['']) AS cartProductIds,
    COALESCE(c.cartCategories, ARRAY['']) AS cartCategories,

    -- Session info
    COALESCE(s.deviceType, 'UNKNOWN') AS deviceType,
    COALESCE(s.sessionDurationMs, CAST(0 AS BIGINT)) AS sessionDurationMs,
    COALESCE(s.totalPageViews, CAST(0 AS BIGINT)) AS totalPageViews,
    COALESCE(s.productPageViews, CAST(0 AS BIGINT)) AS productPageViews,
    COALESCE(s.cartPageViews, CAST(0 AS BIGINT)) AS cartPageViews,
    COALESCE(s.checkoutPageViews, CAST(0 AS BIGINT)) AS checkoutPageViews,

    -- AI Search Activity (strong intent signals)
    COALESCE(a.aiSearchCount, CAST(0 AS BIGINT)) AS aiSearchCount,
    COALESCE(a.aiSearchQueries, ARRAY['']) AS aiSearchQueries,
    COALESCE(a.aiSearchCategories, ARRAY['']) AS aiSearchCategories,
    COALESCE(a.aiMaxBudget, 0.0) AS aiMaxBudget,
    COALESCE(a.aiProductSearches, CAST(0 AS BIGINT)) AS aiProductSearches,
    COALESCE(a.aiProductComparisons, CAST(0 AS BIGINT)) AS aiProductComparisons,

    -- Order History (HIGHEST intent - actual purchases)
    COALESCE(o.totalOrders, CAST(0 AS BIGINT)) AS totalOrders,
    COALESCE(o.totalSpent, 0.0) AS totalSpent,
    COALESCE(o.avgOrderValue, 0.0) AS avgOrderValue,
    COALESCE(o.lastOrderTotal, 0.0) AS lastOrderTotal,
    COALESCE(o.preferredPaymentMethod, 'NONE') AS preferredPaymentMethod,

    -- Last updated (use latest event time across all sources)
    CAST(DATE_FORMAT(
        GREATEST(
            COALESCE(p.lastEventTime, TIMESTAMP '1970-01-01 00:00:00'),
            COALESCE(c.lastEventTime, TIMESTAMP '1970-01-01 00:00:00'),
            COALESCE(s.lastEventTime, TIMESTAMP '1970-01-01 00:00:00'),
            COALESCE(a.lastEventTime, TIMESTAMP '1970-01-01 00:00:00'),
            COALESCE(o.lastEventTime, TIMESTAMP '1970-01-01 00:00:00')
        ),
        'yyyy-MM-dd HH:mm:ss'
    ) AS STRING) AS lastUpdated

FROM `user-product-activity` p

-- LEFT JOIN cart activity
LEFT JOIN `user-cart-activity` c
    ON p.userId = c.userId
    AND p.sessionId = c.sessionId

-- LEFT JOIN session activity
LEFT JOIN `user-session-activity` s
    ON p.userId = s.userId
    AND p.sessionId = s.sessionId

-- LEFT JOIN AI search activity (user-level, not session-level)
-- AI chat sessionId may differ from browser sessionId
LEFT JOIN `user-ai-activity` a
    ON p.userId = a.userId

-- LEFT JOIN order activity (user-level, not session-level)
LEFT JOIN `user-order-activity` o
    ON p.userId = o.userId;
