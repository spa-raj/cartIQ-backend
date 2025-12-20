# CartIQ Internal Debug API Testing Guide

This guide explains how to test the internal debug endpoints for inspecting Redis cached user profiles.

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

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/internal/debug/user-profiles/{userId}` | GET | Get cached user profile by userId |
| `/api/internal/debug/user-profiles` | GET | List all cached user profile keys |

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

## Troubleshooting

### Profile not found
- Verify the user has generated activity (product views, cart actions)
- Check if the Flink SQL job is running and producing to `user-profiles` topic
- Verify the Kafka consumer is running (check logs for "UserProfileConsumer initialized")

### Unauthorized response
- Ensure `INTERNAL_API_KEY` environment variable is set in Cloud Run
- Verify the API key matches the configured value

### Empty keys list
- The consumer may not have processed any messages yet
- Check Cloud Run logs for consumer activity
- Verify Redis connectivity (check for connection errors in logs)
