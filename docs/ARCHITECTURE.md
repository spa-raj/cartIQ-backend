# CartIQ - System Architecture

## Overview

CartIQ is an AI-powered e-commerce shopping assistant that uses real-time event streaming and RAG (Retrieval Augmented Generation) to deliver personalized recommendations. Built for the **AI Partner Catalyst Hackathon** (Confluent Challenge).

---

## Table of Contents

1. [Architecture Diagram](#architecture-diagram)
2. [Architecture Sections](#architecture-sections)
3. [Real-Time Event Streaming (Confluent)](#real-time-event-streaming-confluent)
4. [AI Chat - RAG Pipeline](#ai-chat---rag-pipeline)
5. [Personalized Suggestions API](#personalized-suggestions-api)
6. [Google Cloud Services](#google-cloud-services)
7. [Technology Stack](#technology-stack)
8. [Module Responsibilities](#module-responsibilities)
9. [Response Times](#response-times)
10. [Hackathon Alignment](#hackathon-alignment)
11. [Related Documentation](#related-documentation)

---

## Architecture Diagram

![CartIQ System Architecture](./images/cartIQ-architecture.png)

---

## Architecture Sections

The architecture is organized into three main sections:

### 1. User Journey Flow

Shows how CartIQ learns and personalizes in real-time as the user interacts:

| Step | User Action | System Response |
|------|-------------|-----------------|
| 1. ARRIVE | New user lands on homepage | Curated sections: Trending, Best of Electronics, Best of Fashion |
| 2. BROWSE | View products & categories | `user-events`, `product-views` → Kafka |
| 3. SEARCH | AI Chat query (e.g., "laptops under 50000") | `ai-events` → Kafka (strongest signal) |
| 4. CART | Add items to cart | `cart-events` → Kafka |
| 5. ORDER | Place order | `order-events` → Kafka |
| 6. RETURN | Return to homepage | Personalized "Suggested For You" section appears |

**Key Insight:** Traditional batch systems update user profiles overnight. CartIQ updates profiles continuously - users see personalized recommendations within seconds of their first interaction.

### 2. System Architecture

The core backend system showing:
- **API Layer** (Cloud Run): Event APIs, Chat API, Suggestions API, CRUD APIs
- **Confluent Cloud**: Kafka Topics, Apache Flink, Schema Registry
- **Google Cloud**: Vertex AI Services, Cloud Infrastructure

### 3. Batch Indexing Pipeline (Offline, Scheduled)

A batch pipeline runs periodically to keep the search index fresh:

1. **Export & Embed:** Product data is exported from the database, and text-embedding-004 generates embeddings via a Vertex AI batch job.
2. **Transform & Update:** The output is formatted and used to update the main Vertex AI Vector Search index.

```
Cloud SQL → GCS → Vertex AI Batch Embeddings → Vector Search Index
```

---

## Real-Time Event Streaming (Confluent)

### Kafka Topics

| Topic | Type | Description | Signal Strength |
|-------|------|-------------|-----------------|
| `user-events` | Input | Session events, navigation, page visits | Low |
| `product-views` | Input | Product page views, search clicks | Medium |
| `cart-events` | Input | Add to cart, remove, quantity changes | High |
| `order-events` | Input | Order placed, completed, cancelled | High |
| `ai-events` | Input | AI chat queries with intent & budget | **Strongest** |
| `user-profiles` | Output | Flink-aggregated user context | - |

### Apache Flink SQL

Flink performs **continuous aggregation** (upsert mode) to build user profiles:

- **Aggregation Mode:** Session-level (NOT time windows)
- **State TTL:** 1 hour
- **Output:** Unified user profile merged from all 5 event streams

**Profile Fields:**
- `recentProductIds` - Recently viewed products
- `recentCategories` - Browsed categories
- `aiSearchQueries` - AI chat queries (strongest intent signal)
- `aiMaxBudget` - Budget extracted from AI queries
- `cartItems` - Current cart contents
- `pricePreference` - Inferred price range
- `sessionDuration` - Time spent browsing

### UserProfileConsumer

Listens to the `user-profiles` Kafka topic and:
1. Merges new profile data with existing cached data
2. Stores the updated profile in Redis
3. Makes the profile immediately available for **Suggestions API**

> **Note:** AI Chat does not read user profiles from Redis. It uses client-provided context from the request and a 4-Way Hybrid Search pipeline. However, AI Chat **publishes** `ai-events` to Kafka, which Flink aggregates into user profiles—so chat queries influence future suggestions.

---

## AI Chat - RAG Pipeline

The AI Chat uses a sophisticated RAG (Retrieval Augmented Generation) pipeline:

```
User Query → Gemini (Function Calling) → Hybrid Search → Re-Ranker → Response
```

### 4-Way Hybrid Search Strategy

CartIQ uses **4-way Hybrid Search** combining multiple retrieval methods for maximum recall:

```
                    ┌─────────────────────┐
                    │    User Query       │
                    └──────────┬──────────┘
                               │
         ┌─────────────────────┼─────────────────────┐
         │                     │                     │
         ▼                     ▼                     ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  Vector Search  │  │   FTS Search    │  │ Category Search │
│   (semantic)    │  │   (keyword)     │  │  (structured)   │
│   50 candidates │  │  30 candidates  │  │  30 candidates  │
└────────┬────────┘  └────────┬────────┘  └────────┬────────┘
         │                     │                     │
         └─────────────────────┼─────────────────────┘
                               │
                    ┌──────────▼──────────┐
                    │  Brand Search (if   │
                    │  brand detected)    │
                    │   30 candidates     │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │ Merge & Deduplicate │
                    │ + Universal Filter  │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │  Cross-Encoder      │
                    │  Re-Ranker (Top 10) │
                    └─────────────────────┘
```

| Method | Description | Candidates |
|--------|-------------|------------|
| Vector Search | Semantic similarity via text-embedding-004 | 50 |
| Full-Text Search (FTS) | PostgreSQL keyword matching | 30 |
| Category Search | Products from target category | 30 |
| Brand Search | Products matching detected brand (e.g., "Samsung") | 30 |

Results are merged (brand first for priority), deduplicated, filtered, then re-ranked.

### RAG Pipeline Steps

| Step | Component | Description | Latency |
|------|-----------|-------------|---------|
| 1 | User Context | Client-provided via ChatRequest (preferences, recently viewed, cart) | - |
| 2 | Gemini | Function calling to extract search params (query, category, brand, budget) | ~50ms |
| 3 | 4-Way Search | Vector + FTS + Category + Brand (parallel) | ~50ms |
| 4 | Filter | Universal post-filter (price, rating, category constraints) | <1ms |
| 5 | Re-Ranker | Cross-encoder scoring (Ranking API) → Top 10 | ~100ms |
| 6 | Gemini Response | Generate personalized response with product context | 2-4s |
| 7 | Event Publishing | Publish `ai-events` to Kafka (feeds Flink for future suggestions) | async |
| | **Total** | **End-to-end** | **~2.5-4.5s** |

> **Key Distinction:** AI Chat context comes from the **frontend request**, not from Redis. The frontend tracks user preferences and sends them in the ChatRequest. AI Chat then publishes events to Kafka, which Flink aggregates for the Suggestions API.

---

## Personalized Suggestions API

The Suggestions API (`/api/suggestions`) provides personalized product recommendations in the "Suggested For You" section. This section only appears after the user has some browsing history.

### Suggestion Strategies (Priority Order)

Strategies are applied in priority order, each with a cap on how many products it can contribute:

| Priority | Strategy | Cap | Description |
|----------|----------|-----|-------------|
| 1 | AI Intent | 40% | Products matching AI chat queries and extracted budget |
| 2 | Similar Products | 30% | Vector similarity to most recently viewed product (same category only) |
| 3 | Category Affinity | Remaining | Top-rated products in browsed categories (round-robin for diversity) |

### New User Experience

New users without browsing history see curated homepage sections instead of personalized suggestions:

- **Trending Now** - Featured, highly-rated products across all categories
- **Best of Electronics** - Top electronics with category diversity
- **Best of Fashion** - Top fashion items with category diversity

The personalized "Suggested For You" section appears after the user interacts with the site (browses products, uses AI chat, or adds items to cart).

---

## Google Cloud Services

### Vertex AI Services

| Service | Model/Config | Purpose | Latency |
|---------|--------------|---------|---------|
| Gemini | gemini-2.0-flash | Chat responses, function calling | ~400ms |
| Embeddings | text-embedding-004 (768-dim) | Query & product embeddings | ~5ms |
| Vector Search | ANN, Cosine similarity, threshold 0.5 | Semantic product search | ~50ms |
| Ranking API | Cross-encoder | Re-rank search results | ~100ms |

### Cloud Infrastructure

| Service | Purpose |
|---------|---------|
| Cloud SQL | PostgreSQL database (users, products, orders, carts) |
| Cloud Memorystore | Redis cache (user profiles, embedding cache) |
| Cloud Storage | Batch indexing files (input/, embeddings/, vectors/) |
| Cloud Run | Backend & Frontend hosting |

---

## Technology Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| Frontend | React + TypeScript | User interface |
| Backend | Spring Boot 4.0 (Modular Monolith) | API + Business logic |
| Database | Cloud SQL (PostgreSQL) | Persistent storage |
| Cache | Cloud Memorystore (Redis) | User context + Embedding cache |
| Streaming | Apache Kafka (Confluent Cloud) | Event transport |
| Processing | Apache Flink (Confluent Cloud) | Continuous aggregation |
| Vector DB | Vertex AI Vector Search | Product embeddings index |
| Embeddings | Vertex AI Embeddings | text-embedding-004 (768-dim) |
| Re-ranking | Vertex AI Ranking API | Cross-encoder re-ranking |
| LLM | Gemini 2.0 Flash | Personalized responses |
| Hosting | Google Cloud Run | Deployment |

---

## Module Responsibilities

| Module | Responsibility |
|--------|----------------|
| `cartiq-common` | Shared DTOs, exceptions, utilities |
| `cartiq-user` | Authentication, profiles, JWT |
| `cartiq-product` | Product catalog, categories, FTS search |
| `cartiq-order` | Shopping cart, orders, checkout |
| `cartiq-kafka` | Kafka producers/consumers, event DTOs |
| `cartiq-ai` | Chat API, Suggestions API, Gemini integration |
| `cartiq-rag` | RAG orchestrator, Vector Search, batch indexing |
| `cartiq-seeder` | Database seeding with sample data |
| `cartiq-app` | Main application assembly |

---

## Response Times

| API | Latency   | Description |
|-----|-----------|-------------|
| AI Chat | 2-4s      | Full RAG pipeline with Gemini response generation |
| Suggestions | ~300ms    | Personalized homepage recommendations |
| User Profile | Real-time | Continuous updates via Flink |
| Product Search | ~200ms    | 4-way hybrid search + re-ranking |

---

## Hackathon Alignment

| Requirement | Implementation | Status |
|-------------|----------------|--------|
| Confluent Kafka | 6 topics (5 input + 1 output) | Done |
| Confluent Flink | Continuous user behavior aggregation | Done |
| Google Vertex AI | Embeddings, Vector Search, Ranking, Gemini | Done |
| Google Cloud Run | Backend + Frontend deployment | Done |
| Real-time AI | Suggestions: Flink-enriched profiles; AI Chat: 4-Way Hybrid RAG | Done |
| Response Time | 2-4s AI Chat, ~300ms Suggestions | Done |
| New User Experience | Curated sections (Trending, Best of Electronics/Fashion) | Done |

---

## Related Documentation

- [Challenges & Solutions](./CHALLENGES.md)
- [Deployment Guide](./DEPLOYMENT.md)
- [GCP Setup](./GCP_SETUP.md)
- [Batch Indexing Pipeline](./BATCH_INDEXING.md)
- [RAG vs Tool Use](./RAGvsToolUse.md)
- [Flink SQL Queries](./flink-sql/README.md)

### API Testing Guides
- [User API](./API-testing/USER_API_TESTING.md)
- [Product API](./API-testing/PRODUCT_API_TESTING.md)
- [Order API](./API-testing/ORDER_API_TESTING.md)
- [Chat API](./API-testing/CHAT_API_TESTING.md)
- [Suggestions API](./API-testing/SUGGESTIONS_API_TESTING.md)
- [Events API](./API-testing/EVENTS_API_TESTING.md)

---

*Last updated: December 28, 2025*
