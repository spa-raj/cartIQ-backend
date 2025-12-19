# CartIQ Flink SQL for Confluent Cloud

This directory contains Flink SQL statements to run in Confluent Cloud.

## Setup Instructions

1. Go to **Confluent Cloud Console** → **Flink** → **SQL Workspace**
2. Select your Kafka cluster and Flink compute pool
3. Execute the SQL files in order:
   - `01-aggregations.sql` - Create aggregation queries
   - `02-user-profiles.sql` - Final user profile output

## Architecture

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│ user-events │  │product-views│  │ cart-events │  │  ai-events  │
└──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘
       │                │                │                │
       ▼                ▼                ▼                ▼
┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐
│  session   │  │  recent    │  │   cart     │  │ ai_search  │
│  activity  │  │  product   │  │  activity  │  │  activity  │
│   (view)   │  │  activity  │  │   (view)   │  │   (view)   │
└──────┬─────┘  └──────┬─────┘  └──────┬─────┘  └──────┬─────┘
       │               │               │               │
       └───────────────┼───────────────┼───────────────┘
                       │               │
                ┌──────▼───────────────▼──────┐
                │         LEFT JOINs          │
                │   on userId, sessionId      │
                └──────────────┬──────────────┘
                               │
                        ┌──────▼──────┐
                        │user-profiles│
                        │   (topic)   │
                        └─────────────┘
                               │
                        ┌──────▼──────┐
                        │  AI Module  │
                        └─────────────┘
```

## SQL Files

### 01-aggregations.sql

Creates intermediate views for processing raw Kafka events. Must be run before `02-user-profiles.sql`.

#### Helper Views (Timestamp Parsing)

Auto-generated tables from Kafka topics have `timestamp` as STRING. These views parse them to proper TIMESTAMP type:

| View | Source Topic | Purpose |
|------|--------------|---------|
| `product_views_timed` | `product-views` | Parsed product view events |
| `cart_events_timed` | `cart-events` | Parsed cart action events |
| `user_events_timed` | `user-events` | Parsed user navigation events |
| `ai_events_timed` | `ai-events` | Parsed AI chat/search events |

#### Aggregation Views

| View | Aggregation Level | Key Columns | Description |
|------|-------------------|-------------|-------------|
| `recent_product_activity` | 1-minute buckets | `userId`, `sessionId`, `timeBucket` | Aggregates recently viewed products. Includes `recentProductIds` (last 10), `recentCategories` (last 5), `recentSearchQueries`, metrics (view duration, price range), and `lastEventTime`. |
| `cart_activity` | **Session-level** | `userId`, `sessionId` | Real-time cart state for entire session. Includes `currentCartTotal`, `currentCartItems`, `cartAdds`, `cartRemoves`, and products/categories in cart. |
| `session_activity` | **Session-level** | `userId`, `sessionId` | Session-level behavior for entire session. Includes `deviceType`, page view counts by type, and `sessionDurationMs` (time between first and last event). |
| `price_preferences` | 1-minute buckets | `userId`, `sessionId`, `timeBucket` | Derived from `recent_product_activity`. Classifies users: BUDGET (<₹500), MID_RANGE (₹500-₹2000), PREMIUM (>₹2000). |
| `ai_search_activity` | **Session-level** | `userId`, `sessionId` | **AI chat interactions (strong intent signals)**. Includes `aiSearchCount`, `aiSearchQueries`, `aiSearchCategories`, `aiMaxBudget`, tool usage counts (`aiProductSearches`, `aiProductComparisons`). |

**Note**: `cart_activity`, `session_activity`, and `ai_search_activity` are aggregated at the session level (not time-bucketed) to ensure reliable JOINs regardless of when events occur within a session.

**Why AI events are valuable**: AI chat queries provide **explicit intent signals** that are stronger than passive browsing. When a user asks "laptops under 50000", that's a direct buying signal with category and budget information.

### 02-user-profiles.sql

Creates the main user profile aggregation job using **LEFT JOINs** to combine all four event streams.

#### JOIN Strategy

```sql
FROM recent_product_activity p
LEFT JOIN cart_activity c
    ON p.userId = c.userId
    AND p.sessionId = c.sessionId
LEFT JOIN session_activity s
    ON p.userId = s.userId
    AND p.sessionId = s.sessionId
LEFT JOIN ai_search_activity a
    ON p.userId = a.userId
    AND p.sessionId = a.sessionId
```

- **Base stream**: `recent_product_activity` (product views with 1-minute time buckets)
- **LEFT JOIN**: Ensures profiles are created even when cart/session/AI data is missing
- **Join key**: `(userId, sessionId)` - session-level matching for all joins
- **Why session-level?**: Cart, session, and AI events may occur at different times than product views. Session-level aggregation ensures the JOIN always matches regardless of timing.
- **COALESCE**: Handles null values when joined streams have no matching data

#### Sink Table: `user-profiles`

Creates an upsert table with the following schema:

| Field | Type | Source | Description |
|-------|------|--------|-------------|
| `userId` | STRING | product_views | User identifier (PK) |
| `sessionId` | STRING | product_views | Session identifier (PK) |
| `windowBucket` | STRING | product_views | Time bucket (PK) |
| `eventId` | STRING | generated | Deterministic event ID |
| `recentProductIds` | ARRAY | product_views | Last 10 viewed product IDs |
| `recentCategories` | ARRAY | product_views | Last 5 browsed categories |
| `recentSearchQueries` | ARRAY | product_views | Search queries in session |
| `totalProductViews` | BIGINT | product_views | Total products viewed |
| `totalCartAdds` | BIGINT | **cart_activity** | Cart additions count |
| `avgViewDurationMs` | BIGINT | product_views | Average view duration |
| `avgProductPrice` | DOUBLE | product_views | Average price of viewed products |
| `pricePreference` | STRING | calculated | BUDGET / MID_RANGE / PREMIUM (AI budget preferred) |
| `currentCartTotal` | DOUBLE | **cart_activity** | Current cart value |
| `currentCartItems` | BIGINT | **cart_activity** | Items in cart |
| `sessionDurationMs` | BIGINT | **session_activity** | Session duration |
| `deviceType` | STRING | **session_activity** | User's device type |
| `aiSearchCount` | BIGINT | **ai_search_activity** | Number of AI chat interactions |
| `aiSearchQueries` | ARRAY | **ai_search_activity** | Recent AI search queries (explicit intent) |
| `aiSearchCategories` | ARRAY | **ai_search_activity** | Categories searched via AI |
| `aiMaxBudget` | DOUBLE | **ai_search_activity** | Max budget from AI price filters |
| `aiProductSearches` | BIGINT | **ai_search_activity** | Count of product searches via AI |
| `aiProductComparisons` | BIGINT | **ai_search_activity** | Count of product comparisons via AI |
| `windowStart` | STRING | product_views | Window start timestamp |
| `windowEnd` | STRING | product_views | Window end timestamp |
| `lastUpdated` | STRING | product_views | Last update timestamp |

**Bold** fields are populated via JOINs from cart, session, and AI activity streams.

#### Configuration

- **Changelog mode**: `upsert` - enables updates to existing keys
- **State TTL**: 1 hour (3600000 ms) - prevents unbounded state growth

#### Late Data Handling

The JOIN uses exact `timeBucket` matching (1-minute granularity). Expected latency:

| Source | Latency |
|--------|---------|
| Producer → Kafka | 10-50ms |
| Cross-topic variance | 10-100ms |
| Flink processing | 10-50ms |
| **Total** | **~100-200ms** |

For events within the same minute bucket, late data is negligible (<1% of events, milliseconds to low seconds).
