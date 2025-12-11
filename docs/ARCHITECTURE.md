# CartIQ - System Architecture

## Overview

CartIQ is an AI-powered e-commerce shopping assistant that uses real-time event streaming to deliver personalized recommendations. Built for the **AI Partner Catalyst Hackathon** (Confluent Challenge).

---

## Architecture Diagram

![CartIQ System Architecture](./images/cartIQ-architecture.png)

---

## Why CartIQ?

**Traditional e-commerce:** Stale batch data = generic recommendations that miss the moment.

**CartIQ:** Flink-Enriched Context architecture
- Kafka captures every browse, click, cart action
- Flink aggregates user behavior into real-time profiles
- AI module caches enriched context for instant access
- Gemini delivers personalized recommendations with full context

**Result:** < 500ms from action to AI insight | Personalized in ~15 seconds (Cold Start)

---

## Core Pattern: Flink-Enriched Context

CartIQ uses a **Flink-Enriched Context** pattern where stream processing continuously aggregates user behavior, making it instantly available for AI recommendations.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              DATA FLOW                                          │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  ┌──────────┐                                                                   │
│  │ Frontend │ (React + Firebase)                                                │
│  └────┬─────┘                                                                   │
│       │                                                                         │
│       ├────────────────────────────┬──────────────────────┐                     │
│       │ (events)                   │ (page load)          │ (chat)              │
│       ▼                            ▼                      ▼                     │
│  ┌─────────┐              ┌─────────────────┐    ┌─────────────────┐            │
│  │ Kafka   │              │ GET /api/       │    │ POST /api/chat  │            │
│  │ Input   │              │ recommendations │    │                 │            │
│  └────┬────┘              └────────┬────────┘    └────────┬────────┘            │
│       │                            │                      │                     │
│       ▼                            └──────────┬───────────┘                     │
│  ┌─────────┐                                  │                                 │
│  │ Flink   │ (15-sec windows)                 ▼                                 │
│  └────┬────┘                        ┌───────────────────┐                       │
│       │                             │ UserContext Cache │ (Kafka replay)        │
│       │ write profiles              └─────────┬─────────┘                       │
│       ▼                                       │                                 │
│  ┌─────────┐        consume                   │ lookup                          │
│  │ Kafka   │ ─────────────────────────────────┤                                 │
│  │ Output  │ (user-profiles)                  ▼                                 │
│  └─────────┘                           ┌────────────┐                           │
│                                        │ AI Module  │                           │
│                                        └─────┬──────┘                           │
│                                              │                                  │
│                                              ▼                                  │
│                                        ┌──────────┐                             │
│                                        │  Gemini  │ ──▶ Personalized Response   │
│                                        └──────────┘                             │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### How It Works

1. **Event Capture**: User actions (views, cart, search) are published to Kafka topics
2. **Stream Aggregation**: Flink continuously processes events, aggregating per-user behavior
3. **Profile Output**: Flink writes enriched user profiles to `user-profiles` topic
4. **Context Caching**: AI module consumes profiles and maintains in-memory cache
5. **Instant Lookup**: When user visits home page or sends chat, context is retrieved from cache (< 1ms)
6. **AI Generation**: Gemini receives enriched context and generates personalized response
7. **Restart Recovery**: On app restart, cache rebuilds by replaying `user-profiles` topic from Kafka

### Two Recommendation Surfaces

| Surface | Endpoint | Trigger | Use Case |
|---------|----------|---------|----------|
| **Home Page** | `GET /api/recommendations` | Page load | Passive, visual recommendations |
| **AI Chat** | `POST /api/chat` | User asks question | Interactive, conversational |

Both surfaces use the same `UserContext Cache`, ensuring consistent personalization.

### Why This Pattern?

| Benefit | Description |
|---------|-------------|
| **Low Latency** | Context pre-computed, no on-demand aggregation |
| **Cold Start** | New users get personalized responses within ~15 seconds |
| **Scalability** | Flink handles aggregation, Spring Boot stays lightweight |
| **Decoupling** | AI module doesn't need to know about event sources |
| **Restart Recovery** | Cache rebuilds from Kafka replay—no Redis needed |

---

## End-to-End Data Flow

```
① User browses products on the web app (Client Layer - Firebase)
                    │
                    ▼
② API Layer (Cloud Run) serves data via HTTP/REST
                    │
        ┌───────────┼───────────┐
        ▼           │           ▼
③ Admin Sender      │      ④ Queries to Data Layer
   (Demo Data +     │         (PostgreSQL: users, products, orders, cart_items)
    Live Simulation)│
                    │
                    ▼
              ⑤ Publish Events to Kafka (Input)
                 (user-events, product-views, cart-events, order-events)
                    │
                    ▼
              ⑥ Flink Stream Processing
                 (User Behavior Aggregation - 15-sec tumbling windows)
                    │
                    ▼
              ⑦ Kafka (Output)
                 (user-profiles: Flink aggregated context)
                    │
                    ▼
              ⑧ Consume Profiles → User Context Cache
                 (In-memory, rebuilds from Kafka on restart)
                    │
                    ▼
              AI Module → Gemini API → Personalized Response
```

---

## Flink SQL: User Behavior Aggregation

```sql
-- Aggregate user behavior in 15-second tumbling windows (optimized for demo)
CREATE TABLE user_profiles AS
SELECT
    user_id,
    COLLECT(product_name) AS viewed_products,
    COLLECT(DISTINCT category) AS interested_categories,
    COUNT(*) AS view_count,
    MAX(price) AS max_price_viewed,
    MIN(price) AS min_price_viewed,
    window_start,
    window_end
FROM TABLE(
    TUMBLE(TABLE product_views, DESCRIPTOR(event_time), INTERVAL '15' SECONDS)
)
GROUP BY user_id, window_start, window_end;
```

This query:
- Groups events by user in 15-second windows
- Collects all viewed product names
- Identifies interested categories
- Tracks price range preferences
- Outputs to `user-profiles` topic for AI consumption

---

## Kafka Topics

### Input Topics (Event Streaming Layer)

| Topic | Direction | Description |
|-------|-----------|-------------|
| `user-events` | Input | User session events, login, logout, page visits |
| `product-views` | Input | Product page views, search clicks |
| `cart-events` | Input | Add to cart, remove, quantity changes |
| `order-events` | Input | Order placed, completed, cancelled |

### Output Topics (Flink Aggregated)

| Topic | Direction | Description |
|-------|-----------|-------------|
| `user-profiles` | Output | Flink-aggregated user context (consumed by AI module) |

### Logging Topics (Optional)

| Topic | Direction | Description |
|-------|-----------|-------------|
| `chat-input` | Logging | Chat messages (for analytics) |
| `chat-response` | Logging | AI responses (for analytics) |

---

## Recommendations API

### Home Page Recommendations

```
GET /api/recommendations?userId={userId}

Response:
{
  "recommendations": [
    { "productId": "123", "name": "Phone Case", "reason": "Based on your interest in phones" },
    { "productId": "456", "name": "Wireless Charger", "reason": "Popular with phone buyers" }
  ],
  "contextAvailable": true,
  "fallback": false
}
```

### Fallback Behavior

| Scenario | `contextAvailable` | `fallback` | What's Returned |
|----------|-------------------|------------|-----------------|
| User has profile in cache | `true` | `false` | Personalized recommendations |
| New user, no profile yet | `false` | `true` | Trending/popular products |
| Cache rebuilding after restart | `false` | `true` | Trending products (temporary) |

### AI Chat

```
POST /api/chat
{
  "userId": "user123",
  "message": "What accessories would you recommend?"
}

Response:
{
  "response": "Based on your recent interest in smartphones, I'd recommend...",
  "contextUsed": ["viewed: iPhone 15", "viewed: Samsung Galaxy", "category: Electronics"]
}
```

---

## Admin Sender (Demo Data)

The API Layer includes an **Admin Sender** component for demo and testing purposes:

| Feature | Description |
|---------|-------------|
| **Demo Data** | Pre-populated product catalog, sample users |
| **Live Simulation** | Generates realistic user events for Flink processing |
| **Purpose** | Enables hackathon demo without real user traffic |

This component publishes simulated events to Kafka, allowing the Flink pipeline to aggregate behavior and demonstrate personalization in real-time.

---

## Latency Breakdown

| Stage | Component | Latency | Notes |
|-------|-----------|---------|-------|
| Event Capture | Kafka Producer | < 10ms | Async, non-blocking |
| Stream Processing | Flink | < 100ms | 15-sec windows, continuous |
| Context Lookup | Cache | < 1ms | In-memory HashMap (Kafka replay on restart) |
| AI Inference | Gemini | < 400ms | API call to Vertex AI |
| **Total (Chat)** | **End-to-end** | **< 500ms** | From request to response |

---

## Technology Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| Frontend | React + TypeScript | User interface |
| Backend | Spring Boot 3.2 (Modular Monolith) | API + Business logic |
| Database | Cloud SQL (PostgreSQL) | Persistent storage |
| Streaming | Apache Kafka (Confluent Cloud) | Event transport |
| Processing | Apache Flink (Confluent Cloud) | Stream aggregation |
| AI/ML | Vertex AI + Gemini 2.5 Flash | Recommendations |
| Hosting | Google Cloud Run + Firebase | Deployment |

---

## Module Responsibilities

| Module | Responsibility |
|--------|----------------|
| `cartiq-common` | Shared DTOs, exceptions, utilities |
| `cartiq-user` | Authentication, profiles, JWT |
| `cartiq-product` | Product catalog, categories, search |
| `cartiq-order` | Shopping cart, orders, checkout |
| `cartiq-kafka` | Kafka producers, event DTOs, topic config |
| `cartiq-ai` | Gemini integration, context cache, recommendations API, chat API |
| `cartiq-app` | Main application assembly |

---

## Hackathon Alignment

| Requirement | Implementation | Status |
|-------------|----------------|--------|
| Confluent Kafka | 5 topics (4 input + 1 output profile) | ✓ |
| Confluent Flink | User behavior aggregation | ✓ |
| Google Vertex AI | Gemini for recommendations | ✓ |
| Google Cloud Run | Backend deployment | ✓ |
| Real-time AI | Flink-Enriched Context pattern | ✓ |
| Cold Start | ~15 seconds to personalization | ✓ |

---

## Related Documentation

- [Demo Script](./demo.md)
- [Implementation Plan](./PLAN.md)
- [Product API Testing](./PRODUCT_API_TESTING.md)
- [User API Testing](./USER_API_TESTING.md)

---

*Last updated: December 11, 2025*
