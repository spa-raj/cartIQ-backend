# CartIQ Internal Debug API Testing Guide

This guide explains how to test the internal debug endpoints for inspecting Redis cached data:
- **User Profiles** - Aggregated user behavior from Flink
- **Embeddings** - Cached vector embeddings for products and queries

## Base URL

Set the `BASE_URL` and `INTERNAL_API_KEY` variables before running the commands:

```bash
# Local development
export BASE_URL="http://localhost:8080"
export INTERNAL_API_KEY="your-local-api-key"

# Production (Cloud Run)
export BASE_URL="https://cartiq-xxxxxxx.run.app"
export INTERNAL_API_KEY="your-production-api-key"
```

---

## Authentication

All internal debug endpoints require the `X-Internal-Api-Key` header.

| Header | Required | Description |
|--------|----------|-------------|
| `X-Internal-Api-Key` | Yes | Internal API key from `INTERNAL_API_KEY` env variable |

---

## Endpoints Overview

### User Profiles

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/internal/debug/user-profiles/{userId}` | GET | Get cached user profile by userId |
| `/api/internal/debug/user-profiles` | GET | List all cached user profile keys |

### Embeddings

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/internal/debug/embeddings/stats` | GET | Get embedding cache statistics |
| `/api/internal/debug/embeddings/products` | GET | List all cached product embeddings |
| `/api/internal/debug/embeddings/queries` | GET | List all cached query embeddings |
| `/api/internal/debug/embeddings/product/{productId}` | GET | Get specific product embedding |
| `/api/internal/debug/embeddings/query/{hashCode}` | GET | Get specific query embedding |

---

## 1. Get Cached User Profile

Retrieve a specific user profile from Redis cache.

### Request

```bash
curl -X GET "$BASE_URL/api/internal/debug/user-profiles/{userId}" \
  -H "X-Internal-Api-Key: $INTERNAL_API_KEY"
```

### Example

```bash
curl -X GET "$BASE_URL/api/internal/debug/user-profiles/598e2b6a-98f4-4ee9-83e5-a26164ee5bb8" \
  -H "X-Internal-Api-Key: $INTERNAL_API_KEY"
```

### Response (200 OK)

```json
{
  "@class": "com.cartiq.kafka.dto.UserProfile",
  "userId": "598e2b6a-98f4-4ee9-83e5-a26164ee5bb8",
  "sessionId": "session-abc123",
  "recentProductIds": ["prod-1", "prod-2"],
  "recentCategories": ["Smartphones", "Laptops"],
  "recentSearchQueries": ["gaming laptop"],
  "totalProductViews": 15,
  "avgViewDurationMs": 45000,
  "avgProductPrice": 1299.99,
  "totalCartAdds": 3,
  "currentCartTotal": 2499.99,
  "currentCartItems": 2,
  "deviceType": "DESKTOP",
  "sessionDurationMs": 1800000,
  "pricePreference": "PREMIUM",
  "aiSearchCount": 2,
  "aiSearchQueries": ["best gaming laptop under 2000"],
  "aiSearchCategories": ["Laptops"],
  "aiMaxBudget": 2000.0,
  "aiProductSearches": 5,
  "aiProductComparisons": 2,
  "lastUpdated": "2025-12-20T10:39:12"
}
```

### Response (404 Not Found)

```json
{
  "message": "User profile not found in cache",
  "userId": "non-existent-user-id",
  "cacheKey": "user-profile:non-existent-user-id"
}
```

### Response (401 Unauthorized)

```json
{
  "message": "Invalid or missing API key"
}
```

---

## 2. List All Cached User Profiles

List all user profile keys currently in the Redis cache.

> **Note:** Use with caution in production - the Redis KEYS command can be slow with large datasets.

### Request

```bash
curl -X GET "$BASE_URL/api/internal/debug/user-profiles" \
  -H "X-Internal-Api-Key: $INTERNAL_API_KEY"
```

### Response (200 OK)

```json
{
  "prefix": "user-profile:",
  "count": 3,
  "keys": [
    "user-profile:598e2b6a-98f4-4ee9-83e5-a26164ee5bb8",
    "user-profile:user-456",
    "user-profile:user-789"
  ]
}
```

---

## UserProfile Schema

The cached user profile contains aggregated data from Flink SQL processing:

| Field | Type | Description |
|-------|------|-------------|
| `userId` | string | User's unique identifier |
| `sessionId` | string | Current session ID |
| `recentProductIds` | string[] | Recently viewed product IDs |
| `recentCategories` | string[] | Recently browsed categories |
| `recentSearchQueries` | string[] | Recent search queries |
| `totalProductViews` | long | Total products viewed in session |
| `avgViewDurationMs` | long | Average view duration in milliseconds |
| `avgProductPrice` | double | Average price of viewed products |
| `totalCartAdds` | long | Number of cart additions |
| `currentCartTotal` | double | Current cart total value |
| `currentCartItems` | long | Number of items in cart |
| `deviceType` | string | Device type (DESKTOP, MOBILE, TABLET) |
| `sessionDurationMs` | long | Session duration in milliseconds |
| `pricePreference` | string | Computed preference: BUDGET, MID_RANGE, PREMIUM |
| `aiSearchCount` | long | Number of AI chat searches |
| `aiSearchQueries` | string[] | AI chat search queries |
| `aiSearchCategories` | string[] | Categories from AI searches |
| `aiMaxBudget` | double | Maximum budget mentioned in AI chat |
| `aiProductSearches` | long | Product searches via AI |
| `aiProductComparisons` | long | Product comparisons via AI |
| `lastUpdated` | datetime | When the profile was last updated |

---

## Cache Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `cartiq.suggestions.cache.prefix` | `user-profile:` | Redis key prefix |
| `cartiq.suggestions.cache.ttl-hours` | `1` | Cache TTL in hours |

---

# Embedding Cache Endpoints

These endpoints allow you to inspect the embedding cache used for vector search.

---

## 3. Get Embedding Cache Statistics

Get an overview of cached embeddings including counts and sample keys.

### Request

```bash
curl -X GET "$BASE_URL/api/internal/debug/embeddings/stats" \
  -H "X-Internal-Api-Key: $INTERNAL_API_KEY"
```

### Response (200 OK)

```json
{
  "productEmbeddings": {
    "prefix": "embedding:",
    "count": 150,
    "sampleKeys": [
      "embedding:prod-001",
      "embedding:prod-002",
      "embedding:prod-003"
    ]
  },
  "queryEmbeddings": {
    "prefix": "query_embedding:",
    "count": 25,
    "sampleKeys": [
      "query_embedding:123456789",
      "query_embedding:-987654321"
    ]
  },
  "totalCachedEmbeddings": 175
}
```

---

## 4. List Product Embeddings

List all cached product embeddings with their TTL.

### Request

```bash
curl -X GET "$BASE_URL/api/internal/debug/embeddings/products?limit=10" \
  -H "X-Internal-Api-Key: $INTERNAL_API_KEY"
```

### Query Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `limit` | 20 | Maximum number of keys to return |

### Response (200 OK)

```json
{
  "count": 150,
  "showing": 10,
  "embeddings": [
    {
      "key": "embedding:prod-001",
      "productId": "prod-001",
      "ttlSeconds": 82800
    },
    {
      "key": "embedding:prod-002",
      "productId": "prod-002",
      "ttlSeconds": 79200
    }
  ]
}
```

---

## 5. List Query Embeddings

List all cached query embeddings with their TTL.

### Request

```bash
curl -X GET "$BASE_URL/api/internal/debug/embeddings/queries?limit=10" \
  -H "X-Internal-Api-Key: $INTERNAL_API_KEY"
```

### Response (200 OK)

```json
{
  "count": 25,
  "showing": 10,
  "embeddings": [
    {
      "key": "query_embedding:123456789",
      "queryHash": "123456789",
      "ttlSeconds": 3200
    },
    {
      "key": "query_embedding:-987654321",
      "queryHash": "-987654321",
      "ttlSeconds": 2800
    }
  ]
}
```

---

## 6. Get Product Embedding

Get a specific product embedding by product ID.

### Request

```bash
# Preview only (first 5 and last 5 values)
curl -X GET "$BASE_URL/api/internal/debug/embeddings/product/{productId}" \
  -H "X-Internal-Api-Key: $INTERNAL_API_KEY"

# Include full vector (768 dimensions)
curl -X GET "$BASE_URL/api/internal/debug/embeddings/product/{productId}?includeVector=true" \
  -H "X-Internal-Api-Key: $INTERNAL_API_KEY"
```

### Query Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `includeVector` | false | Include full 768-dimension vector in response |

### Response (200 OK)

```json
{
  "productId": "prod-001",
  "cacheKey": "embedding:prod-001",
  "dimensions": 768,
  "ttlSeconds": 82800,
  "ttlFormatted": "23h 0m 0s",
  "vectorPreview": {
    "first5": [0.0234, -0.0156, 0.0789, -0.0234, 0.0567],
    "last5": [0.0123, -0.0456, 0.0789, -0.0123, 0.0345]
  }
}
```

### Response (404 Not Found)

```json
{
  "message": "Product embedding not found in cache",
  "productId": "non-existent-id",
  "cacheKey": "embedding:non-existent-id"
}
```

---

## 7. Get Query Embedding

Get a specific query embedding by hash code.

### Request

```bash
curl -X GET "$BASE_URL/api/internal/debug/embeddings/query/{hashCode}" \
  -H "X-Internal-Api-Key: $INTERNAL_API_KEY"
```

### Response (200 OK)

```json
{
  "hashCode": "123456789",
  "cacheKey": "query_embedding:123456789",
  "dimensions": 768,
  "ttlSeconds": 3200,
  "ttlFormatted": "53m 20s",
  "vectorPreview": {
    "first5": [0.0234, -0.0156, 0.0789, -0.0234, 0.0567],
    "last5": [0.0123, -0.0456, 0.0789, -0.0123, 0.0345]
  }
}
```

---

## Embedding Cache Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `cartiq.rag.cache.product-embedding-ttl` | `24h` | TTL for product embeddings |
| `cartiq.rag.cache.query-embedding-ttl` | `1h` | TTL for query embeddings |

### Cache Key Formats

| Type | Key Format | Example |
|------|------------|---------|
| Product Embedding | `embedding:{productId}` | `embedding:prod-001` |
| Query Embedding | `query_embedding:{hashCode}` | `query_embedding:123456789` |

> **Note:** Query embeddings use the Java `String.hashCode()` of the query text as the key.

---

## Troubleshooting

### User Profile Issues

#### Profile not found
- Verify the user has generated activity (product views, cart actions)
- Check if the Flink SQL job is running and producing to `user-profiles` topic
- Verify the Kafka consumer is running (check logs for "UserProfileConsumer initialized")

#### Empty keys list
- The consumer may not have processed any messages yet
- Check Cloud Run logs for consumer activity
- Verify Redis connectivity (check for connection errors in logs)

### Embedding Cache Issues

#### No embeddings cached
- Embeddings are cached on-demand when vector search is performed
- Try running a search query in AI chat to generate cached embeddings
- Check logs for "Redis available - embedding caching enabled" at startup

#### Product embedding not found
- The product may not have been searched for yet
- Product embeddings are only cached when used in vector search
- Batch indexing does NOT cache embeddings (it writes directly to Vector Search index)

#### Query embedding not found
- Query embeddings use `String.hashCode()` as the key
- The same query text will always produce the same hash
- Query embeddings expire after 1 hour (configurable)

#### Redis not available response
- Redis connection may not be configured
- Check `REDIS_HOST` and `REDIS_PORT` environment variables
- Verify VPC connector is configured for Cloud Run to access Memorystore

### General Issues

#### Unauthorized response
- Ensure `INTERNAL_API_KEY` environment variable is set in Cloud Run
- Verify the API key matches the configured value
- Header must be exactly: `X-Internal-Api-Key`
