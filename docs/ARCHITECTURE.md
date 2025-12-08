# CartIQ - System Architecture

## Overview

CartIQ is an AI-powered e-commerce shopping assistant that uses real-time event streaming to deliver personalized recommendations. Built for the **AI Partner Catalyst Hackathon** (Confluent Challenge).

---

## Architecture Diagram

![CartIQ System Architecture](./images/cartIQ-architecture.png)

*See the diagram above for the complete system architecture. The following sections explain the data flow and key design decisions.*

---

## Why CartIQ?

**Traditional e-commerce:** Stale batch data = generic recommendations that miss the moment.

**CartIQ:** Real-time Streaming Agents architecture
- Kafka captures every browse, click, cart action
- Flink aggregates behavior instantly
- Gemini delivers personalized AI recommendations

**Result:** < 500ms from action to AI insight

---

## End-to-End Data Flow

```
① User browses products on the web app
                    │
                    ▼
② API Layer serves data and publishes events
                    │
        ┌───────────┴───────────┐
        ▼                       ▼
③ Data Layer              ④ Kafka Topics
   (Queries/Writes)          (Real-time events)
                                    │
                                    ▼
                            ⑤ Flink Processing
                               (Behavior aggregation)
                                    │
                                    ▼
                            ⑥ AI/ML Layer
                               (Gemini with real-time context)
                                    │
                                    ▼
                            ⑦ Personalized recommendations
                               returned to user
```

---

## Streaming Agents Pattern

CartIQ implements the **Streaming Agents** pattern - Confluent's architecture for real-time AI:

| Stage | Component | Latency |
|-------|-----------|---------|
| Event Capture | Kafka | < 10ms |
| Stream Processing | Flink | < 100ms |
| AI Inference | Gemini | < 400ms |
| **Total** | **End-to-end** | **< 500ms** |

---

## Kafka Topics

| Topic | Events Published |
|-------|------------------|
| `user-events` | Login, logout, profile updates |
| `product-views` | Product page views, search clicks |
| `cart-events` | Add to cart, remove, quantity changes |
| `order-events` | Order placed, completed, cancelled |

---

## Technology Stack

| Layer | Technology |
|-------|------------|
| Frontend | React + TypeScript |
| Backend | Spring Boot 4.0 (Modular Monolith) |
| Database | Cloud SQL (PostgreSQL) |
| Streaming | Apache Kafka (Confluent Cloud) |
| Processing | Apache Flink (Confluent Cloud) |
| AI/ML | Vertex AI + Gemini 2.5 Flash |
| Hosting | Google Cloud Run |

---

## Hackathon Alignment

| Requirement | Status |
|-------------|--------|
| Confluent Kafka | ✓ 4 topics for real-time events |
| Confluent Flink | ✓ Stream processing for AI context |
| Google Vertex AI | ✓ Gemini for recommendations |
| Google Cloud Run | ✓ Backend deployment |
| Streaming Agents | ✓ Full implementation |

---

## Related Documentation

- [Product API Testing](./PRODUCT_API_TESTING.md)
- [User API Testing](./USER_API_TESTING.md)

---

*Last updated: December 8, 2025*
