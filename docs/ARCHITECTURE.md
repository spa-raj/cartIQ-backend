# CartIQ - System Architecture

## Overview

CartIQ is an AI-powered e-commerce shopping assistant that uses real-time event streaming and RAG (Retrieval Augmented Generation) to deliver personalized recommendations. Built for the **AI Partner Catalyst Hackathon** (Confluent Challenge).

---

## Architecture Diagram

![CartIQ System Architecture](./images/cartIQ-architecture.png)

---

## Why CartIQ?

**Problem:** Traditional e-commerce uses stale batch data = generic recommendations that miss the moment.

**Solution:** CartIQ uses Real-time Context + RAG Architecture

- Kafka captures every browse, click, cart action
- Flink builds user profiles in 15-second windows
- RAG retrieves relevant products via Vector Search
- Gemini delivers personalized recommendations

**Result:**
- Personalized response in ~560ms
- Context updates every 15 seconds
- Cold start → Newest products (no delay)

---

## Flow Legend

### User Browsing & Context Building Flow (1-7)

| Step | Description |
|------|-------------|
| 1 | User browses the web page |
| 2 | Frontend calls backend API layer via REST APIs for user actions and events |
| 3 | API layer performs CRUD operations to PostgreSQL |
| 4 | Kafka module publishes events to Kafka topics in Confluent Cloud |
| 5 | Apache Flink aggregates events (15 sec tumbling period) to create user profile |
| 6 | Apache Flink writes aggregated events to a dedicated Kafka topic |
| 7 | User context cache (Redis) updated with profile |

### User Chat Recommendations Flow - RAG Pipeline (A.1-A.8)

| Step | Description | Latency |
|------|-------------|---------|
| A.1 | User sends chat message or requests recommendations | - |
| A.2a | Cache hit - Chat controller gets user context from Redis | <1ms |
| A.2b | Cache miss - Chat controller responds with newest products (cold start) | - |
| A.3 | Chat controller sends user context to RAG orchestrator → Query Builder | <1ms |
| A.4a | RAG orchestrator checks embedding cache for query | ~5ms (hit) |
| A.4b | Cache miss - embeds query via Vertex AI, stores in Redis | ~100ms (miss) |
| A.5 | Vector Search (ANN) returns Top-50 candidates | ~50ms |
| A.6 | Re-Ranker (Cross-Encoder) selects Top-10 products | ~100ms |
| A.7 | Gemini receives prompt (query + context + top-10) and generates response | ~400ms |
| A.8 | Response returned to user | - |
| | **TOTAL** | **~560ms** |

### Product Indexing Flow (B)

| Step | Description | Latency |
|------|-------------|---------|
| B | Product CRUD → Build Text → Embed (cache) → Upsert to Vector Index | ~100-200ms |

*Async operation - doesn't block API response*

---

## Core Architecture Patterns

### 1. Flink-Enriched Context Pattern

Stream processing continuously aggregates user behavior, making it instantly available for AI recommendations.

```
User Action → Kafka → Flink (15s windows) → User Profile → Redis Cache
```

### 2. Two-Stage RAG Retrieval

High recall followed by high precision for optimal results.

```
Query → Embedding → Vector Search (Top-50) → Re-Ranker (Top-10) → Gemini
```

### 3. Incremental Product Indexing

Real-time index updates when products change.

```
Product CRUD → Spring Event → Embed → Upsert to Vector Index (async)
```

---

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              COMPLETE DATA FLOW                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌──────────┐                                                                    │
│  │ Frontend │ (React + Firebase)                                                 │
│  └────┬─────┘                                                                    │
│       │                                                                          │
│       ├──────────────────────────┬────────────────────────┐                      │
│       │ (1-2 events)             │ (A.1 chat request)     │                      │
│       ▼                          ▼                        │                      │
│  ┌─────────────┐         ┌─────────────────┐              │                      │
│  │ API Layer   │         │ Chat Controller │              │                      │
│  │ (Kafka mod) │         │     (A.2)       │              │                      │
│  └──────┬──────┘         └────────┬────────┘              │                      │
│         │                         │                        │                      │
│         │ (4)                     │ (A.3)                  │                      │
│         ▼                         ▼                        │                      │
│  ┌─────────────┐         ┌─────────────────────────────────────────────────┐    │
│  │ Kafka Input │         │              AI/ML RAG Layer                     │    │
│  └──────┬──────┘         │                                                  │    │
│         │                │  ┌─────────────┐    ┌─────────────────────────┐  │    │
│         │ (5)            │  │Query Builder│───▶│ Embedding Service (A.4) │  │    │
│         ▼                │  │   (A.3)     │    └───────────┬─────────────┘  │    │
│  ┌─────────────┐         │  └─────────────┘                │                │    │
│  │   Flink     │         │        ▲                        ▼                │    │
│  │ (15s window)│         │        │              ┌─────────────────────────┐│    │
│  └──────┬──────┘         │   User Context        │ Vector Search (A.5)     ││    │
│         │                │        │              │ ANN - Top 50            ││    │
│         │ (6)            │        │              └───────────┬─────────────┘│    │
│         ▼                │  ┌─────┴─────┐                    │              │    │
│  ┌─────────────┐         │  │   Redis   │                    ▼              │    │
│  │Kafka Output │         │  │  - User   │        ┌─────────────────────────┐│    │
│  │user-profiles│         │  │  Context  │        │ Re-Ranker (A.6)         ││    │
│  └──────┬──────┘         │  │  - Embed  │        │ Cross-Encoder - Top 10  ││    │
│         │                │  │  Cache    │        └───────────┬─────────────┘│    │
│         │ (7)            │  └───────────┘                    │              │    │
│         ▼                │                                   ▼              │    │
│  ┌─────────────┐         │                       ┌─────────────────────────┐│    │
│  │Redis Cache  │◀────────│                       │ Gemini 2.5 Pro (A.7)    ││    │
│  │(User Contxt)│         │                       │ Personalized Response   ││    │
│  └─────────────┘         │                       └───────────┬─────────────┘│    │
│                          │                                   │              │    │
│                          └───────────────────────────────────┼──────────────┘    │
│                                                              │                   │
│  ┌─────────────┐                                             │ (A.8)             │
│  │Product CRUD │──(B)──▶ Vector Search Index                 ▼                   │
│  │  (async)    │         (Incremental Indexing)      Response to User            │
│  └─────────────┘                                                                 │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## RAG Pipeline Details

### Query Builder

Enriches the user's query with context for better search results.

```
Input:  "I need headphones" + User Context (likes running, budget-conscious)
Output: "wireless headphones running sports fitness under 100"
```

### Embedding Service

Generates 768-dimensional vectors using Vertex AI Embeddings (text-embedding-004).

- **Cache Hit:** Return from Redis (~5ms)
- **Cache Miss:** Call Vertex AI API → Cache result (~100ms)

### Vector Search

Uses Vertex AI Vector Search with Approximate Nearest Neighbor (ANN).

- **Algorithm:** Tree-AH
- **Distance Metric:** Cosine Distance
- **Output:** Top-50 candidates
- **Latency:** ~50ms

### Re-Ranker

Cross-Encoder model for higher precision scoring.

- **Model:** Vertex AI Ranking API (semantic-ranker-512)
- **Input:** Query + 50 product descriptions
- **Output:** Top-10 products with relevance scores
- **Latency:** ~100ms

### Gemini

Generates personalized, conversational responses.

- **Model:** Gemini 2.5 Pro
- **Input:** User query + User context + Top-10 products + System instructions
- **Output:** Personalized recommendation with explanations
- **Latency:** ~400ms

---

## Cold Start Handling

When a user has no browsing history (cache miss), the system returns newest products.

```java
if (userContext == null || userContext.isEmpty()) {
    // Cold start - return newest products
    return productRepository.findTop10ByOrderByCreatedAtDesc();
}
// Normal RAG flow with user context
return ragPipeline.retrieve(query, userContext);
```

| Approach | Chosen | Reason |
|----------|--------|--------|
| Newest Products | ✅ | Zero additional work, shows fresh inventory |
| Random Products | ❌ | Inconsistent, not "smart" |
| Trending (view count) | ❌ | Requires additional tracking |

---

## Kafka Topics

### Input Topics (Event Streaming)

| Topic | Description |
|-------|-------------|
| `user-events` | User session events, login, logout, page visits |
| `product-views` | Product page views, search clicks |
| `cart-events` | Add to cart, remove, quantity changes |
| `order-events` | Order placed, completed, cancelled |

### Output Topics (Flink Aggregated)

| Topic | Description |
|-------|-------------|
| `user-profiles` | Flink-aggregated user context (consumed by backend) |

---

## Latency Breakdown

| Stage | Component | Latency |
|-------|-----------|---------|
| User Context Lookup | Redis | <1ms |
| Query Building | Query Builder | <1ms |
| Embedding (cached) | Redis | ~5ms |
| Embedding (uncached) | Vertex AI API | ~100ms |
| Vector Search | Vertex AI Vector Search | ~50ms |
| Re-Ranking | Vertex AI Ranking API | ~100ms |
| LLM Inference | Gemini 2.5 Pro | ~400ms |
| **Total (cached)** | **End-to-end** | **~560ms** |

---

## Technology Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| Frontend | React + TypeScript | User interface |
| Backend | Spring Boot 3.2 (Modular Monolith) | API + Business logic |
| Database | Cloud SQL (PostgreSQL) | Persistent storage |
| Cache | Cloud Memorystore (Redis) | User context + Embedding cache |
| Streaming | Apache Kafka (Confluent Cloud) | Event transport |
| Processing | Apache Flink (Confluent Cloud) | Stream aggregation |
| Vector DB | Vertex AI Vector Search | Product embeddings index |
| Embeddings | Vertex AI Embeddings | text-embedding-004 (768-dim) |
| Re-ranking | Vertex AI Ranking API | Cross-encoder re-ranking |
| LLM | Gemini 2.5 Pro | Personalized responses |
| Hosting | Google Cloud Run + Firebase | Deployment |

---

## Module Responsibilities

| Module | Responsibility |
|--------|----------------|
| `cartiq-common` | Shared DTOs, exceptions, utilities |
| `cartiq-user` | Authentication, profiles, JWT |
| `cartiq-product` | Product catalog, categories, search |
| `cartiq-order` | Shopping cart, orders, checkout |
| `cartiq-kafka` | Kafka producers/consumers, event DTOs |
| `cartiq-ai` | RAG orchestrator, Gemini integration, chat API |
| `cartiq-app` | Main application assembly |

---

## Hackathon Alignment

| Requirement | Implementation | Status |
|-------------|----------------|--------|
| Confluent Kafka | 5 topics (4 input + 1 output) | ✅ |
| Confluent Flink | User behavior aggregation (15s windows) | ✅ |
| Google Vertex AI | Embeddings, Vector Search, Ranking, Gemini | ✅ |
| Google Cloud Run | Backend deployment | ✅ |
| Real-time AI | Flink-Enriched Context + RAG | ✅ |
| Response Time | ~560ms end-to-end | ✅ |
| Cold Start | Newest products fallback | ✅ |

---

## Related Documentation

- [RAG Architecture Details](./updateArchitecture.md)
- [GCP Setup Guide](./GCP_SETUP.md)
- [Deployment Guide](./DEPLOYMENT.md)
- [Demo Script](./demo.md)
- [Product API Testing](./PRODUCT_API_TESTING.md)
- [User API Testing](./USER_API_TESTING.md)
- [Order API Testing](./ORDER_API_TESTING.md)

---

*Last updated: December 13, 2025*
