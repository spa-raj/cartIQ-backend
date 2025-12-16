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
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ user-events │     │product-views│     │ cart-events │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           │
                    ┌──────▼──────┐
                    │  Flink SQL  │
                    │ Aggregation │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │user-profiles│
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

#### Aggregation Views

| View | Bucket Size | Description |
|------|-------------|-------------|
| `recent_product_activity` | 5 minutes | Aggregates recently viewed products per user session. Collects recent product IDs (last 10), categories (last 5), search queries, and calculates metrics like avg view duration and price range. |
| `cart_activity` | 1 minute | Real-time cart state per user session. Tracks current cart total/items, add/remove counts, and products/categories in cart. |
| `session_activity` | 1 minute | Session-level user behavior. Tracks device type, page views by type (product, cart, checkout), and engagement signals. |
| `price_preferences` | 5 minutes | Derived from `recent_product_activity`. Classifies users into price tiers: BUDGET (<₹500), MID_RANGE (₹500-₹2000), PREMIUM (>₹2000). |

### 02-user-profiles.sql

Creates the main user profile aggregation job that outputs to the `user-profiles` topic.

#### Sink Table: `user-profiles`

Creates an upsert table with the following schema:

| Field | Type | Description |
|-------|------|-------------|
| `userId` | STRING | User identifier (primary key) |
| `sessionId` | STRING | Session identifier (primary key) |
| `windowBucket` | STRING | Time bucket (primary key) |
| `eventId` | STRING | Deterministic event ID |
| `recentProductIds` | ARRAY<STRING> | Last 10 viewed product IDs |
| `recentCategories` | ARRAY<STRING> | Last 5 browsed categories |
| `recentSearchQueries` | ARRAY<STRING> | Search queries in session |
| `totalProductViews` | BIGINT | Total products viewed |
| `totalCartAdds` | BIGINT | Cart additions count |
| `avgViewDurationMs` | BIGINT | Average view duration |
| `avgProductPrice` | DOUBLE | Average price of viewed products |
| `pricePreference` | STRING | BUDGET / MID_RANGE / PREMIUM |
| `currentCartTotal` | DOUBLE | Current cart value |
| `currentCartItems` | BIGINT | Items in cart |
| `sessionDurationMs` | BIGINT | Session duration |
| `deviceType` | STRING | User's device type |
| `windowStart` | STRING | Window start timestamp |
| `windowEnd` | STRING | Window end timestamp |
| `lastUpdated` | STRING | Last update timestamp |

#### Configuration

- **Changelog mode**: `upsert` - enables updates to existing keys
- **State TTL**: 1 hour (3600000 ms) - prevents unbounded state growth

#### Main Job

The `INSERT INTO user-profiles` statement:
1. Reads from `product_views_timed` view
2. Groups by `userId`, `sessionId`, and 1-minute time buckets
3. Aggregates product views, categories, search queries
4. Calculates price preference based on average viewed price
5. Outputs to `user-profiles` topic for AI module consumption
