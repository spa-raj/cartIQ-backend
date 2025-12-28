# CartIQ Events API Testing Guide

This guide explains how to integrate and test the Event Tracking API for streaming user behavior to Kafka.

## Table of Contents

1. [Base URL](#base-url)
2. [Endpoints Overview](#endpoints-overview)
3. [Why Event Tracking Matters](#why-event-tracking-matters)
4. [Endpoints](#endpoints)
   - [Track User Event](#1-track-user-event)
   - [Track Product View](#2-track-product-view)
   - [Track Cart Event](#3-track-cart-event)
   - [Track Order Event](#4-track-order-event)
5. [Frontend Integration Guide](#frontend-integration-guide)
6. [Testing Scenarios](#testing-scenarios)
7. [Debugging](#debugging)
8. [Common Issues](#common-issues)

---

## Base URL

Set the `BASE_URL` variable before running the commands:

```bash
# Local development
export BASE_URL="http://localhost:8080"

# Production (Cloud Run)
export BASE_URL="https://cartiq-backend-886147182338.us-central1.run.app"
```

---

## Endpoints Overview

| Endpoint | Method | Kafka Topic | Description |
|----------|--------|-------------|-------------|
| `/api/events/user` | POST | `user-events` | Track page navigation, login/logout |
| `/api/events/product-view` | POST | `product-views` | Track product detail page views |
| `/api/events/cart` | POST | `cart-events` | Track add/remove cart actions |
| `/api/events/order` | POST | `order-events` | Track order placement |

> **Important:** All enum values must be sent in **UPPERCASE** (e.g., `PAGE_VIEW`, `AI_CHAT`, `ADD`).

---

## Why Event Tracking Matters

Event tracking is **critical** for personalized suggestions. The Flink SQL pipeline aggregates events into user profiles:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EVENT FLOW TO PERSONALIZATION                        │
└─────────────────────────────────────────────────────────────────────────────┘

  User Action          API Call                  Kafka Topic         Flink Table
  ───────────          ────────                  ───────────         ───────────

  View product    →  /api/events/product-view  →  product-views  →  user-product-activity
       │                                                                    │
       │                                                                    ▼
  Add to cart     →  /api/events/cart          →  cart-events    →  user-cart-activity
       │                                                                    │
       │                                                                    ▼
  Use AI chat     →  (internal)                →  ai-events      →  user-ai-activity
       │                                                                    │
       │                                                                    ▼
  Place order     →  /api/events/order         →  order-events   →  user-order-activity
       │                                                                    │
       └────────────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
                              ┌─────────────────────┐
                              │   user-profiles     │  ← Flink JOIN output
                              │   (Kafka topic)     │
                              └──────────┬──────────┘
                                         │
                                         ▼
                              ┌─────────────────────┐
                              │   Redis Cache       │
                              │   (1-hour TTL)      │
                              └──────────┬──────────┘
                                         │
                                         ▼
                              ┌─────────────────────┐
                              │  GET /api/suggestions│
                              │  (personalized)     │
                              └─────────────────────┘
```

### Critical: Product Views Drive Profiles

**`product-views` is the driving table in Flink SQL.** Without product view events, no user profile is created—even if the user has cart, AI, or order activity.

| Event Type | Required for Profile? | Signal Strength |
|------------|----------------------|-----------------|
| `product-views` | **YES (driving table)** | Medium |
| `cart-events` | No (LEFT JOIN) | High |
| `ai-events` | No (LEFT JOIN) | Highest |
| `order-events` | No (LEFT JOIN) | Highest |
| `user-events` | No (LEFT JOIN) | Low |

---

## Endpoints

### 1. Track User Event

Track page navigation, login, logout, and session events.

**Endpoint:** `POST /api/events/user`

**Headers:**

| Header | Required | Description |
|--------|----------|-------------|
| `X-Session-Id` | No | Session UUID (auto-generated if not provided) |

**Request Body:**

```json
{
  "userId": "bcbf2881-e8d3-43e4-a8d5-57c2b7973aee",
  "eventType": "PAGE_VIEW",
  "pageType": "PRODUCT",
  "pageUrl": "/products/c3304726-5e25-41c1-a200-a8b78127b7e9",
  "deviceType": "DESKTOP",
  "referrer": "https://google.com"
}
```

**Field Reference:**

| Field | Type | Required | Values |
|-------|------|----------|--------|
| `userId` | string | Yes | User UUID |
| `eventType` | enum | Yes | `PAGE_VIEW`, `LOGIN`, `LOGOUT`, `SESSION_START`, `SESSION_END` |
| `pageType` | enum | No | `HOME`, `CATEGORY`, `PRODUCT`, `CART`, `CHECKOUT` |
| `pageUrl` | string | No | Current page URL |
| `deviceType` | enum | No | `DESKTOP`, `MOBILE`, `TABLET` |
| `referrer` | string | No | Referrer URL |

**Example:**

```bash
curl -X POST "$BASE_URL/api/events/user" \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: a3f14c69-1a7a-4403-8feb-6bba2013cc32" \
  -d '{
    "userId": "bcbf2881-e8d3-43e4-a8d5-57c2b7973aee",
    "eventType": "PAGE_VIEW",
    "pageType": "PRODUCT",
    "pageUrl": "/products/c3304726-5e25-41c1-a200-a8b78127b7e9",
    "deviceType": "DESKTOP"
  }'
```

**Response (200 OK):**

```json
{
  "status": "tracked",
  "eventId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

---

### 2. Track Product View

**CRITICAL:** Track when a user views a product detail page. This is required for Flink to create user profiles.

**Endpoint:** `POST /api/events/product-view`

**Headers:**

| Header | Required | Description |
|--------|----------|-------------|
| `X-Session-Id` | No | Session UUID |

**Request Body:**

```json
{
  "userId": "bcbf2881-e8d3-43e4-a8d5-57c2b7973aee",
  "productId": "c3304726-5e25-41c1-a200-a8b78127b7e9",
  "productName": "Samsung Galaxy S24 Ultra",
  "category": "Smartphones",
  "price": 134999.00,
  "source": "AI_CHAT",
  "searchQuery": "recommend me samsung phones under 150000",
  "viewDurationMs": 5000
}
```

**Field Reference:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `userId` | string | Yes | User UUID |
| `productId` | string | Yes | Product UUID |
| `productName` | string | Yes | Product name |
| `category` | string | Yes | Category name |
| `price` | decimal | Yes | Product price |
| `source` | enum | Yes | Where user came from |
| `searchQuery` | string | No | Search query that led to this view |
| `viewDurationMs` | long | No | Time spent viewing (milliseconds) |

**Source Values (UPPERCASE required):**

| Source | When to Use |
|--------|-------------|
| `SEARCH` | User clicked from search results |
| `CATEGORY` | User clicked from category listing |
| `RECOMMENDATION` | User clicked from "Suggested For You" |
| `AI_CHAT` | User clicked product from AI chat response |
| `DIRECT` | User navigated directly (URL, bookmark) |
| `CART` | User clicked from cart page |
| `HOME` | User clicked from homepage sections |

**Example:**

```bash
curl -X POST "$BASE_URL/api/events/product-view" \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: a3f14c69-1a7a-4403-8feb-6bba2013cc32" \
  -d '{
    "userId": "bcbf2881-e8d3-43e4-a8d5-57c2b7973aee",
    "productId": "c3304726-5e25-41c1-a200-a8b78127b7e9",
    "productName": "Samsung Galaxy S24 Ultra",
    "category": "Smartphones",
    "price": 134999.00,
    "source": "CATEGORY",
    "viewDurationMs": 8500
  }'
```

**Response (200 OK):**

```json
{
  "status": "tracked",
  "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

---

### 3. Track Cart Event

Track when a user adds or removes items from cart.

**Endpoint:** `POST /api/events/cart`

**Headers:**

| Header | Required | Description |
|--------|----------|-------------|
| `X-Session-Id` | No | Session UUID |

**Request Body:**

```json
{
  "userId": "bcbf2881-e8d3-43e4-a8d5-57c2b7973aee",
  "action": "ADD",
  "productId": "c3304726-5e25-41c1-a200-a8b78127b7e9",
  "productName": "Samsung Galaxy S24 Ultra",
  "category": "Smartphones",
  "quantity": 1,
  "price": 134999.00,
  "cartTotal": 134999.00,
  "cartItemCount": 1
}
```

**Field Reference:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `userId` | string | Yes | User UUID |
| `action` | enum | Yes | `ADD`, `REMOVE`, `UPDATE_QUANTITY`, `CLEAR` |
| `productId` | string | Yes | Product UUID |
| `productName` | string | Yes | Product name |
| `category` | string | Yes | Category name |
| `quantity` | int | Yes | Quantity added/removed |
| `price` | decimal | Yes | Unit price |
| `cartTotal` | decimal | Yes | Current cart total after action |
| `cartItemCount` | int | Yes | Total items in cart after action |

**Example:**

```bash
curl -X POST "$BASE_URL/api/events/cart" \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: a3f14c69-1a7a-4403-8feb-6bba2013cc32" \
  -d '{
    "userId": "bcbf2881-e8d3-43e4-a8d5-57c2b7973aee",
    "action": "ADD",
    "productId": "c3304726-5e25-41c1-a200-a8b78127b7e9",
    "productName": "Samsung Galaxy S24 Ultra",
    "category": "Smartphones",
    "quantity": 1,
    "price": 134999.00,
    "cartTotal": 134999.00,
    "cartItemCount": 1
  }'
```

**Response (200 OK):**

```json
{
  "status": "tracked",
  "eventId": "b2c3d4e5-f6a7-8901-bcde-f12345678901"
}
```

---

### 4. Track Order Event

Track when a user places an order.

**Endpoint:** `POST /api/events/order`

**Request Body:**

```json
{
  "userId": "bcbf2881-e8d3-43e4-a8d5-57c2b7973aee",
  "orderId": "6c43a084-702c-4d97-a334-7345d4050d41",
  "items": [
    {
      "productId": "c3304726-5e25-41c1-a200-a8b78127b7e9",
      "productName": "Samsung Galaxy S24 Ultra",
      "category": "Smartphones",
      "quantity": 1,
      "price": 134999.00
    },
    {
      "productId": "6aabe670-fc8a-44a2-a12f-7468c2175e10",
      "productName": "Samsung Galaxy Buds3 Pro",
      "category": "Earphones",
      "quantity": 1,
      "price": 19999.00
    }
  ],
  "subtotal": 154998.00,
  "discount": 5000.00,
  "total": 149998.00,
  "paymentMethod": "UPI",
  "status": "PLACED",
  "shippingCity": "Mumbai",
  "shippingState": "Maharashtra"
}
```

**Field Reference:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `userId` | string | Yes | User UUID |
| `orderId` | string | Yes | Order UUID |
| `items` | array | Yes | List of order items |
| `subtotal` | decimal | Yes | Subtotal before discount |
| `discount` | decimal | No | Discount amount |
| `total` | decimal | Yes | Final total |
| `paymentMethod` | enum | Yes | `UPI`, `CARD`, `NETBANKING`, `COD`, `WALLET` |
| `status` | enum | Yes | `PENDING`, `PLACED`, `CONFIRMED`, `PROCESSING`, `SHIPPED`, `DELIVERED`, `CANCELLED`, `REFUNDED` |
| `shippingCity` | string | No | Delivery city |
| `shippingState` | string | No | Delivery state |

**Example:**

```bash
curl -X POST "$BASE_URL/api/events/order" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "bcbf2881-e8d3-43e4-a8d5-57c2b7973aee",
    "orderId": "6c43a084-702c-4d97-a334-7345d4050d41",
    "items": [
      {
        "productId": "c3304726-5e25-41c1-a200-a8b78127b7e9",
        "productName": "Samsung Galaxy S24 Ultra",
        "category": "Smartphones",
        "quantity": 1,
        "price": 134999.00
      }
    ],
    "subtotal": 134999.00,
    "discount": 0,
    "total": 134999.00,
    "paymentMethod": "UPI",
    "status": "PLACED",
    "shippingCity": "Mumbai",
    "shippingState": "Maharashtra"
  }'
```

**Response (200 OK):**

```json
{
  "status": "tracked",
  "eventId": "c3d4e5f6-a7b8-9012-cdef-123456789012"
}
```

---

## Frontend Integration Guide

### When to Call Each Endpoint

> **Note:** All enum values must be **UPPERCASE** (e.g., `AI_CHAT`, not `ai_chat`)

| User Action | Endpoint | Key Fields |
|-------------|----------|------------|
| User opens product detail page | `/api/events/product-view` | userId, productId, productName, category, price, source |
| User clicks product from AI chat | `/api/events/product-view` | + source: `AI_CHAT`, searchQuery |
| User clicks product from search | `/api/events/product-view` | + source: `SEARCH`, searchQuery |
| User clicks product from suggestions | `/api/events/product-view` | + source: `RECOMMENDATION` |
| User clicks product from homepage | `/api/events/product-view` | + source: `HOME` |
| User adds item to cart | `/api/events/cart` | userId, productId, action: `ADD`, quantity, price, cartTotal, cartItemCount |
| User removes item from cart | `/api/events/cart` | userId, productId, action: `REMOVE` |
| User places order | `/api/events/order` | userId, orderId, items, total, paymentMethod, status |
| User navigates to new page | `/api/events/user` | userId, eventType, pageType, pageUrl |

### React Hook Example

```tsx
// hooks/useEventTracking.ts
import { useCallback } from 'react';
import { useAuth } from './useAuth';
import { useSession } from './useSession';

const API_BASE_URL = process.env.REACT_APP_API_URL;

interface ProductViewData {
  productId: string;
  productName: string;
  category: string;
  price: number;
  source: 'SEARCH' | 'CATEGORY' | 'RECOMMENDATION' | 'AI_CHAT' | 'DIRECT' | 'CART' | 'HOME';
  searchQuery?: string;
}

interface CartEventData {
  action: 'ADD' | 'REMOVE' | 'UPDATE_QUANTITY' | 'CLEAR';
  productId: string;
  productName: string;
  category: string;
  quantity: number;
  price: number;
  cartTotal: number;
  cartItemCount: number;
}

export function useEventTracking() {
  const { user } = useAuth();
  const { sessionId } = useSession();

  const trackProductView = useCallback(async (data: ProductViewData) => {
    if (!user?.id) return;

    try {
      await fetch(`${API_BASE_URL}/api/events/product-view`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Session-Id': sessionId,
        },
        body: JSON.stringify({
          userId: user.id,
          ...data,
        }),
      });
    } catch (error) {
      console.error('Failed to track product view:', error);
    }
  }, [user?.id, sessionId]);

  const trackCartEvent = useCallback(async (data: CartEventData) => {
    if (!user?.id) return;

    try {
      await fetch(`${API_BASE_URL}/api/events/cart`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Session-Id': sessionId,
        },
        body: JSON.stringify({
          userId: user.id,
          ...data,
        }),
      });
    } catch (error) {
      console.error('Failed to track cart event:', error);
    }
  }, [user?.id, sessionId]);

  return { trackProductView, trackCartEvent };
}
```

### Product Detail Page Example

```tsx
// pages/ProductDetail.tsx
import { useEffect, useRef } from 'react';
import { useParams, useLocation } from 'react-router-dom';
import { useEventTracking } from '../hooks/useEventTracking';
import { useProduct } from '../hooks/useProduct';

export function ProductDetail() {
  const { productId } = useParams();
  const location = useLocation();
  const { product, loading } = useProduct(productId);
  const { trackProductView } = useEventTracking();
  const hasTracked = useRef(false);

  // Determine source from navigation state or referrer
  const getSource = () => {
    const state = location.state as { source?: string; searchQuery?: string } | null;
    if (state?.source) return state.source;

    const referrer = document.referrer;
    if (referrer.includes('/search')) return 'SEARCH';
    if (referrer.includes('/category')) return 'CATEGORY';
    if (referrer.includes('/cart')) return 'CART';
    return 'DIRECT';
  };

  useEffect(() => {
    // Track product view on mount (once)
    if (product && !hasTracked.current) {
      hasTracked.current = true;

      const state = location.state as { searchQuery?: string } | null;

      trackProductView({
        productId: product.id,
        productName: product.name,
        category: product.categoryName,
        price: product.price,
        source: getSource() as any,
        searchQuery: state?.searchQuery,
      });
    }
  }, [product, trackProductView, location.state]);

  if (loading) return <div>Loading...</div>;
  if (!product) return <div>Product not found</div>;

  return (
    <div className="product-detail">
      {/* Product content */}
    </div>
  );
}
```

### AI Chat Product Click Example

```tsx
// components/AIChatProductCard.tsx
import { useEventTracking } from '../hooks/useEventTracking';
import { useNavigate } from 'react-router-dom';

interface Props {
  product: ProductDTO;
  chatQuery: string;  // The AI query that returned this product
}

export function AIChatProductCard({ product, chatQuery }: Props) {
  const { trackProductView } = useEventTracking();
  const navigate = useNavigate();

  const handleClick = () => {
    // Track before navigating
    trackProductView({
      productId: product.id,
      productName: product.name,
      category: product.categoryName,
      price: product.price,
      source: 'AI_CHAT',
      searchQuery: chatQuery,
    });

    // Navigate with state for the product page
    navigate(`/products/${product.id}`, {
      state: { source: 'AI_CHAT', searchQuery: chatQuery }
    });
  };

  return (
    <div className="ai-product-card" onClick={handleClick}>
      <img src={product.thumbnailUrl} alt={product.name} />
      <h3>{product.name}</h3>
      <p>₹{product.price.toLocaleString()}</p>
    </div>
  );
}
```

---

## Testing Scenarios

### Scenario 1: Complete User Journey

```bash
# 1. User views a product from category
curl -X POST "$BASE_URL/api/events/product-view" \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: test-session-001" \
  -d '{
    "userId": "test-user-001",
    "productId": "prod-001",
    "productName": "Test Product",
    "category": "Electronics",
    "price": 999.00,
    "source": "CATEGORY"
  }'

# 2. User adds to cart
curl -X POST "$BASE_URL/api/events/cart" \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: test-session-001" \
  -d '{
    "userId": "test-user-001",
    "action": "ADD",
    "productId": "prod-001",
    "productName": "Test Product",
    "category": "Electronics",
    "quantity": 1,
    "price": 999.00,
    "cartTotal": 999.00,
    "cartItemCount": 1
  }'

# 3. Wait 30 seconds for Flink processing

# 4. Check suggestions
curl -X GET "$BASE_URL/api/suggestions?limit=12" \
  -H "X-User-Id: test-user-001"
```

### Scenario 2: AI Chat to Product View

```bash
# 1. User uses AI chat (internal - simulated by backend)
# 2. User clicks product from AI response
curl -X POST "$BASE_URL/api/events/product-view" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-user-002",
    "productId": "prod-002",
    "productName": "Samsung Phone",
    "category": "Smartphones",
    "price": 79999.00,
    "source": "AI_CHAT",
    "searchQuery": "recommend me samsung phones under 80000"
  }'
```

---

## Debugging

### Check if Events Are Published to Kafka

Look for these log entries in Cloud Run:

```
Tracked product view: user=<userId>, product=<productId>
Sent to product-views partition 0 offset <n>
```

### Verify with gcloud

```bash
gcloud run services logs read cartiq-backend \
  --region=us-central1 \
  --limit=100 2>&1 | grep -i "product-views"
```

### Check User Profile Creation

```bash
# After tracking events, wait 30 seconds, then check profile
curl -H "X-Internal-Api-Key: $INTERNAL_API_KEY" \
     "$BASE_URL/api/internal/debug/user-profiles/<USER_ID>"
```

---

## Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| `personalized: false` despite activity | No `product-views` events | Ensure frontend calls `/api/events/product-view` |
| Events not in Kafka | Kafka producer error | Check Cloud Run logs for producer errors |
| Profile not created | Flink job not running | Verify Flink job status in Confluent Cloud |
| Delayed personalization | Normal Flink processing | Wait 30+ seconds after events |
| Missing session ID | Header not sent | Include `X-Session-Id` header |
| Enum parsing error / 400 | Lowercase enum values | Use UPPERCASE: `AI_CHAT` not `ai_chat` |

---

## Response Codes

| Code | Meaning |
|------|---------|
| 200 | Event tracked successfully |
| 400 | Invalid request body (missing required fields) |
| 500 | Server error (Kafka unavailable, etc.) |

---

*Last updated: December 28, 2025*
