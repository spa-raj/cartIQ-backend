-- ============================================================================
-- CartIQ Flink SQL: INSERT Jobs for Activity Tables
-- Run this SECOND (after 01-aggregations.sql)
-- Each INSERT creates a separate Flink job that populates materialized tables
-- Run these as SEPARATE statements (not all at once)
-- ============================================================================

-- ============================================================================
-- JOB 1: Product Activity Aggregation
-- Aggregates product views per user/session into materialized table
-- ============================================================================
INSERT INTO `user-product-activity`
SELECT
    userId,
    sessionId,
    COALESCE(ARRAY_SLICE(ARRAY_AGG(productId), 1, 10), ARRAY['']) AS recentProductIds,
    COALESCE(ARRAY_SLICE(ARRAY_DISTINCT(ARRAY_AGG(category)), 1, 5), ARRAY['']) AS recentCategories,
    COALESCE(ARRAY_DISTINCT(ARRAY_AGG(searchQuery)), ARRAY['']) AS recentSearchQueries,
    COUNT(*) AS totalProductViews,
    CAST(COALESCE(AVG(viewDurationMs), 0) AS BIGINT) AS avgViewDurationMs,
    COALESCE(AVG(price), 0.0) AS avgProductPrice,
    COALESCE(MAX(price), 0.0) AS maxViewedPrice,
    COALESCE(MIN(price), 0.0) AS minViewedPrice,
    MAX(event_time) AS lastEventTime
FROM `product_views_timed`
WHERE event_time IS NOT NULL
GROUP BY userId, sessionId;

-- ============================================================================
-- JOB 2: Cart Activity Aggregation
-- Aggregates cart events per user/session into materialized table
-- ============================================================================
INSERT INTO `user-cart-activity`
SELECT
    userId,
    sessionId,
    COALESCE(LAST_VALUE(cartTotal), 0.0) AS currentCartTotal,
    COALESCE(CAST(LAST_VALUE(cartItemCount) AS BIGINT), CAST(0 AS BIGINT)) AS currentCartItems,
    COUNT(*) FILTER (WHERE action = 'ADD') AS cartAdds,
    COUNT(*) FILTER (WHERE action = 'REMOVE') AS cartRemoves,
    CASE
        WHEN COUNT(*) FILTER (WHERE action = 'ADD') > 0
        THEN ARRAY_DISTINCT(ARRAY_AGG(productId) FILTER (WHERE action = 'ADD'))
        ELSE ARRAY['']
    END AS cartProductIds,
    CASE
        WHEN COUNT(*) FILTER (WHERE action = 'ADD') > 0
        THEN ARRAY_DISTINCT(ARRAY_AGG(category) FILTER (WHERE action = 'ADD'))
        ELSE ARRAY['']
    END AS cartCategories,
    MAX(event_time) AS lastEventTime
FROM `cart_events_timed`
WHERE event_time IS NOT NULL
GROUP BY userId, sessionId;

-- ============================================================================
-- JOB 3: Session Activity Aggregation
-- Aggregates user navigation events per session into materialized table
-- ============================================================================
INSERT INTO `user-session-activity`
SELECT
    userId,
    sessionId,
    COALESCE(FIRST_VALUE(deviceType), 'UNKNOWN') AS deviceType,
    COUNT(*) AS totalPageViews,
    CAST(COUNT(DISTINCT pageType) AS BIGINT) AS uniquePageTypes,
    COUNT(*) FILTER (WHERE eventType = 'PAGE_VIEW' AND pageType = 'PRODUCT') AS productPageViews,
    COUNT(*) FILTER (WHERE eventType = 'PAGE_VIEW' AND pageType = 'CART') AS cartPageViews,
    COUNT(*) FILTER (WHERE eventType = 'PAGE_VIEW' AND pageType = 'CHECKOUT') AS checkoutPageViews,
    COALESCE(
        CAST(TIMESTAMPDIFF(SECOND, MIN(event_time), MAX(event_time)) * 1000 AS BIGINT),
        CAST(0 AS BIGINT)
    ) AS sessionDurationMs,
    MAX(event_time) AS lastEventTime
FROM `user_events_timed`
WHERE event_time IS NOT NULL
GROUP BY userId, sessionId;

-- ============================================================================
-- JOB 4: AI Search Activity Aggregation
-- Aggregates AI chat interactions per session into materialized table
-- These are STRONG intent signals - explicit queries > passive browsing
-- ============================================================================
INSERT INTO `user-ai-activity`
SELECT
    userId,
    sessionId,
    COUNT(*) AS aiSearchCount,
    COALESCE(ARRAY_SLICE(ARRAY_AGG(query), 1, 10), ARRAY['']) AS aiSearchQueries,
    COALESCE(ARRAY_SLICE(ARRAY_DISTINCT(ARRAY_AGG(category)), 1, 5), ARRAY['']) AS aiSearchCategories,
    COALESCE(AVG(minPrice), 0.0) AS aiAvgMinPrice,
    COALESCE(AVG(maxPrice), 0.0) AS aiAvgMaxPrice,
    COALESCE(MAX(maxPrice), 0.0) AS aiMaxBudget,
    COUNT(*) FILTER (WHERE toolName = 'searchProducts') AS aiProductSearches,
    COUNT(*) FILTER (WHERE toolName = 'getProductDetails') AS aiProductDetailViews,
    COUNT(*) FILTER (WHERE toolName = 'compareProducts') AS aiProductComparisons,
    COALESCE(SUM(resultsCount), CAST(0 AS BIGINT)) AS aiTotalResultsShown,
    MAX(event_time) AS lastEventTime
FROM `ai_events_timed`
WHERE event_time IS NOT NULL
GROUP BY userId, sessionId;

-- ============================================================================
-- JOB 5: Order Activity Aggregation
-- Aggregates purchase history per user (HIGHEST intent signal - actual conversions)
-- Note: Aggregated at USER level (not session) for lifetime purchase history
-- ============================================================================
INSERT INTO `user-order-activity`
SELECT
    userId,
    COUNT(*) AS totalOrders,
    COALESCE(SUM(total), 0.0) AS totalSpent,
    COALESCE(AVG(total), 0.0) AS avgOrderValue,
    COALESCE(SUM(discount), 0.0) AS totalDiscount,
    COALESCE(LAST_VALUE(total), 0.0) AS lastOrderTotal,
    -- Note: For categories/products from nested items, would need UNNEST
    -- Using placeholder arrays for now - can enhance with item-level flattening
    ARRAY[''] AS purchasedCategories,
    ARRAY[''] AS purchasedProductIds,
    COALESCE(LAST_VALUE(paymentMethod), 'UNKNOWN') AS preferredPaymentMethod,
    COALESCE(LAST_VALUE(status), 'UNKNOWN') AS lastOrderStatus,
    MAX(event_time) AS lastEventTime
FROM `order_events_timed`
WHERE event_time IS NOT NULL
GROUP BY userId;
