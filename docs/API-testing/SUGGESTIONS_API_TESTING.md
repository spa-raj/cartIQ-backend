# CartIQ Suggestions API Testing Guide

This guide explains how to integrate and test the Suggestions API for personalized product recommendations.

## Base URL

Set the `BASE_URL` variable before running the commands:

```bash
# Local development
export BASE_URL="http://localhost:8080"

# Production (Cloud Run)
export BASE_URL="https://your-cloud-run-url.run.app"
```

---

## Endpoints Overview

| Endpoint | Method | Description | Auth |
|----------|--------|-------------|------|
| `/api/suggestions` | GET | Get personalized product suggestions | None (X-User-Id optional) |

---

## How Personalization Works

The Suggestions API uses a **4-strategy recommendation engine** based on user behavior signals:

```
┌─────────────────────────────────────────────────────────────────┐
│                    RECOMMENDATION FLOW                          │
└─────────────────────────────────────────────────────────────────┘

  User browses products    User chats with AI      User views categories
         │                        │                        │
         ▼                        ▼                        ▼
  ┌─────────────┐         ┌─────────────┐          ┌─────────────┐
  │  Product    │         │   AI Chat   │          │  Category   │
  │   Views     │         │   Queries   │          │  Browsing   │
  └──────┬──────┘         └──────┬──────┘          └──────┬──────┘
         │                       │                        │
         └───────────────────────┼────────────────────────┘
                                 │
                                 ▼
                    ┌────────────────────────┐
                    │   Flink SQL Processing │
                    │   (Real-time Agg.)     │
                    └───────────┬────────────┘
                                │
                                ▼
                    ┌────────────────────────┐
                    │   user-profiles Topic  │
                    │   (Kafka)              │
                    └───────────┬────────────┘
                                │
                                ▼
                    ┌────────────────────────┐
                    │   Redis Cache          │
                    │   (1-hour TTL)         │
                    └───────────┬────────────┘
                                │
                                ▼
                    ┌────────────────────────┐
                    │   GET /api/suggestions │
                    │   + X-User-Id header   │
                    └────────────────────────┘
```

### Strategy Weights

| Strategy | Weight | Signal Strength | Description |
|----------|--------|-----------------|-------------|
| AI Intent | 40% | ★★★★★ | Products matching AI chat queries (category + budget) |
| Similar Products | 30% | ★★★★ | Vector similarity to recently viewed products |
| Category Affinity | 20% | ★★★ | Top-rated in user's browsed categories |
| Trending | 10% | ★★ | Featured/popular products (cold start fallback) |

---

## Request

### GET /api/suggestions

Get personalized product suggestions for the current user.

#### Headers

| Header | Required | Description |
|--------|----------|-------------|
| `X-User-Id` | No | User's UUID. If provided, returns personalized suggestions. If absent, returns trending products only. |

#### Query Parameters

| Parameter | Type | Default | Min | Max | Description |
|-----------|------|---------|-----|-----|-------------|
| `limit` | int | 12 | 1 | 50 | Maximum number of suggestions to return |

---

## Response Schema

### SuggestionsResponse

```json
{
  "products": [ProductDTO],
  "totalCount": 12,
  "personalized": true,
  "strategies": {
    "ai_intent": "5",
    "similar_products": "4",
    "category_affinity": "2",
    "trending": "1"
  },
  "userId": "598e2b6a-98f4-4ee9-83e5-a26164ee5bb8"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `products` | array | List of recommended ProductDTO objects |
| `totalCount` | int | Number of products returned |
| `personalized` | boolean | `true` if user profile was found, `false` for anonymous/cold start |
| `strategies` | object | Map of strategy name → count of products from that strategy |
| `userId` | string | User ID (null for anonymous users) |

### ProductDTO (in response)

```json
{
  "id": "uuid",
  "sku": "SKU-12345",
  "name": "Product Name",
  "description": "Product description text",
  "price": 999.00,
  "compareAtPrice": 1299.00,
  "stockQuantity": 50,
  "brand": "Brand Name",
  "categoryId": "category-uuid",
  "categoryName": "Electronics",
  "imageUrls": ["https://..."],
  "thumbnailUrl": "https://...",
  "rating": 4.5,
  "reviewCount": 128,
  "status": "ACTIVE",
  "featured": true,
  "inStock": true,
  "createdAt": "2025-12-20T10:00:00",
  "updatedAt": "2025-12-20T10:00:00"
}
```

---

## Examples

### 1. Get Suggestions for Logged-in User (Personalized)

```bash
curl -X GET "$BASE_URL/api/suggestions?limit=12" \
  -H "X-User-Id: 598e2b6a-98f4-4ee9-83e5-a26164ee5bb8" \
  -H "Content-Type: application/json"
```

**Response (200 OK):**

```json
{
  "products": [
    {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "name": "Samsung Galaxy S24 Ultra",
      "price": 1299.99,
      "categoryName": "Smartphones",
      "thumbnailUrl": "https://example.com/s24.jpg",
      "rating": 4.8,
      "reviewCount": 2456,
      "featured": true,
      "inStock": true
    },
    {
      "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "name": "iPhone 15 Pro Max",
      "price": 1199.99,
      "categoryName": "Smartphones",
      "thumbnailUrl": "https://example.com/iphone15.jpg",
      "rating": 4.9,
      "reviewCount": 3891,
      "featured": true,
      "inStock": true
    }
  ],
  "totalCount": 12,
  "personalized": true,
  "strategies": {
    "ai_intent": "5",
    "similar_products": "3",
    "category_affinity": "2",
    "trending": "2"
  },
  "userId": "598e2b6a-98f4-4ee9-83e5-a26164ee5bb8"
}
```

### 2. Get Suggestions for Anonymous User (Cold Start)

```bash
curl -X GET "$BASE_URL/api/suggestions?limit=8" \
  -H "Content-Type: application/json"
```

**Response (200 OK):**

```json
{
  "products": [
    {
      "id": "c3d4e5f6-a7b8-9012-cdef-123456789012",
      "name": "Apple MacBook Pro 14\"",
      "price": 1999.99,
      "categoryName": "Laptops",
      "thumbnailUrl": "https://example.com/macbook.jpg",
      "rating": 4.7,
      "reviewCount": 1523,
      "featured": true,
      "inStock": true
    }
  ],
  "totalCount": 8,
  "personalized": false,
  "strategies": {
    "trending": "8"
  },
  "userId": null
}
```

### 3. Get More Suggestions (Higher Limit)

```bash
curl -X GET "$BASE_URL/api/suggestions?limit=24" \
  -H "X-User-Id: 598e2b6a-98f4-4ee9-83e5-a26164ee5bb8"
```

---

## Frontend Integration Guide

### React Example

```tsx
// hooks/useSuggestions.ts
import { useState, useEffect } from 'react';
import { useAuth } from './useAuth';

interface ProductDTO {
  id: string;
  name: string;
  price: number;
  thumbnailUrl: string;
  rating: number;
  categoryName: string;
  inStock: boolean;
}

interface SuggestionsResponse {
  products: ProductDTO[];
  totalCount: number;
  personalized: boolean;
  strategies: Record<string, string>;
  userId: string | null;
}

export function useSuggestions(limit: number = 12) {
  const [suggestions, setSuggestions] = useState<SuggestionsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const { user } = useAuth();

  useEffect(() => {
    const fetchSuggestions = async () => {
      try {
        setLoading(true);

        const headers: HeadersInit = {
          'Content-Type': 'application/json',
        };

        // Add user ID header if logged in
        if (user?.id) {
          headers['X-User-Id'] = user.id;
        }

        const response = await fetch(
          `${API_BASE_URL}/api/suggestions?limit=${limit}`,
          { headers }
        );

        if (!response.ok) {
          throw new Error('Failed to fetch suggestions');
        }

        const data: SuggestionsResponse = await response.json();
        setSuggestions(data);
      } catch (err) {
        setError(err instanceof Error ? err : new Error('Unknown error'));
      } finally {
        setLoading(false);
      }
    };

    fetchSuggestions();
  }, [user?.id, limit]);

  return { suggestions, loading, error };
}
```

### Component Example

```tsx
// components/SuggestedForYou.tsx
import { useSuggestions } from '../hooks/useSuggestions';
import { ProductCard } from './ProductCard';

export function SuggestedForYou() {
  const { suggestions, loading, error } = useSuggestions(12);

  if (loading) {
    return <div className="suggestions-skeleton">Loading...</div>;
  }

  if (error || !suggestions) {
    return null; // Gracefully hide on error
  }

  return (
    <section className="suggested-for-you">
      <div className="section-header">
        <h2>
          {suggestions.personalized
            ? 'Suggested For You'
            : 'Trending Products'}
        </h2>
        {suggestions.personalized && (
          <span className="personalized-badge">Personalized</span>
        )}
      </div>

      <div className="products-grid">
        {suggestions.products.map((product) => (
          <ProductCard key={product.id} product={product} />
        ))}
      </div>
    </section>
  );
}
```

### When to Show Suggestions

| Page | Recommended Limit | Notes |
|------|-------------------|-------|
| Homepage | 12 | Main "Suggested For You" section |
| Product Detail | 6 | "You May Also Like" section |
| Cart | 4 | "Complete Your Purchase" |
| Search (No Results) | 8 | Fallback recommendations |
| Category Page | 8 | Additional recommendations below listings |

---

## Personalization Requirements

For personalized suggestions to work, the user must have:

1. **Generated activity** - Viewed products, added to cart, or used AI chat
2. **User ID passed** - Send `X-User-Id` header with the user's UUID
3. **Flink job running** - The `user-profiles` Flink SQL job must be active
4. **Recent activity** - User profile cache has 1-hour TTL

### Signals That Improve Personalization

| Signal Type | Weight | How to Generate |
|-------------|--------|-----------------|
| AI Chat Queries | Highest | User asks AI about products/categories |
| AI Budget Mentions | High | User mentions price range in chat |
| Product Views | Medium | User views product detail pages |
| Cart Actions | Medium | User adds/removes items from cart |
| Category Browsing | Low | User browses category pages |

---

## Debugging

### Check if User Has Profile

Use the internal debug endpoint to verify a user's profile exists:

```bash
curl -H "X-Internal-Api-Key: $INTERNAL_API_KEY" \
     "$BASE_URL/api/internal/debug/user-profiles/$USER_ID"
```

### Response Indicates Personalization Status

| `personalized` | `strategies` | Meaning |
|----------------|--------------|---------|
| `true` | Multiple strategies | User profile found, full personalization |
| `true` | Only `trending` | User profile found but no useful signals |
| `false` | Only `trending` | No user profile (anonymous or cold start) |

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| `personalized: false` for logged-in user | No profile in cache | User needs to generate activity first |
| Only trending products | User has no AI/view signals | User should browse products or use AI chat |
| Empty response | No products in database | Seed product data |
| Slow response | Vector search latency | Check Vertex AI Vector Search status |

---

## Rate Limiting

The endpoint has no rate limiting, but consider implementing client-side caching:

- **Cache duration**: 5 minutes recommended
- **Cache key**: `suggestions:${userId}:${limit}`
- **Invalidate**: When user performs new action (view, cart, chat)

---

## Error Responses

### 500 Internal Server Error

```json
{
  "timestamp": "2025-12-20T10:00:00.000+00:00",
  "status": 500,
  "error": "Internal Server Error",
  "message": "Failed to fetch suggestions",
  "path": "/api/suggestions"
}
```

**Possible causes:**
- Redis connection failure
- Vector Search service unavailable
- Database connection issue

**Frontend handling:** Show trending products as fallback or hide section gracefully.
