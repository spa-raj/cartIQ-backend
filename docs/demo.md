# CartIQ Demo Script

**Duration:** 3 minutes
**Audience:** AI Partner Catalyst Hackathon Judges (Confluent Challenge)

---

## Pre-Demo Checklist

- [ ] Backend running (Cloud Run or local)
- [ ] Frontend running (Firebase or local)
- [ ] Kafka topics created and accessible
- [ ] Flink job running with **15-second tumbling windows**
- [ ] Fresh browser / incognito mode (no cached user data)
- [ ] Architecture diagram ready to show
- [ ] Home page has "Recommended For You" section visible

---

## Demo Script

### 0:00 - 0:20 | Introduction + Show Empty State

**Say:**
> "CartIQ is an AI-powered shopping assistant that delivers personalized recommendations in real-time. Unlike traditional e-commerce that uses stale batch data, CartIQ uses Confluent Kafka and Flink to stream user behavior directly to our AI."

**Show:** Home page with "Recommended For You" section

**Say:**
> "Notice the recommendations section is showing trending products right now—we don't know this user's preferences yet."

**Expected:** Generic/trending products or empty state

---

### 0:20 - 0:50 | Generate User Events

**Do:**
1. Browse to a product category (e.g., Electronics)
2. Click on 3-4 products (phones, laptops, headphones)
3. Add one item to cart

**Say:**
> "Every action I take—browsing, clicking, adding to cart—is published to Kafka topics in real-time. Flink is aggregating my behavior as we speak in 15-second windows."

---

### 0:50 - 1:20 | Return to Home Page (Visual Wow Moment!)

**Do:**
1. Wait a moment for Flink window to close (~15 seconds from first browse)
2. Navigate back to the home page

**Say:**
> "Now let's go back to the home page..."

**Expected:** "Recommended For You" section now shows electronics/phones!

**Say:**
> "Look at that! Without typing anything, the recommendations have changed. The system recognized I was browsing electronics and is now suggesting phone accessories and related products. This happened automatically through our streaming pipeline."

---

### 1:20 - 1:50 | Show Personalized Chat (Additional Proof)

**Do:**
1. Open the AI chat
2. Ask: "What accessories would go well with my interests?"

**Say:**
> "We also have an AI chat that uses the same context. Let me ask for recommendations..."

**Expected:** Gemini responds with electronics-related suggestions, mentioning the user's browsing history

**Say:**
> "The AI knows exactly what I was looking at and gives personalized suggestions. Both the home page recommendations and chat use the same Flink-enriched context."

---

### 1:50 - 2:30 | Explain Architecture

**Do:** Show architecture diagram

**Say:**
> "Here's how it works:
> 1. User events flow to Kafka topics
> 2. Flink aggregates behavior in 15-second tumbling windows
> 3. Enriched user profiles are written back to Kafka
> 4. Our AI module caches these profiles—and rebuilds from Kafka on restart
> 5. Both the home page recommendations API and the chat use this cached context
> 6. Gemini generates personalized responses with full browsing history
>
> The entire flow from action to AI insight takes under 500 milliseconds."

---

### 2:30 - 3:00 | Wrap Up & Future Vision

**Say:**
> "Currently we use individual behavior for personalization. The architecture supports collaborative filtering by adding a second Flink job that clusters users by behavior patterns—that's a future enhancement."

> "We use in-memory cache with Kafka replay—on restart, we replay the user-profiles topic to rebuild the cache in seconds. No Redis needed."

> "CartIQ demonstrates how Confluent's streaming platform enables real-time AI that traditional batch systems simply can't match. Thank you!"

---

## Two Ways to Show Personalization

| Method | Endpoint | User Action | Best For |
|--------|----------|-------------|----------|
| **Home Page Section** | `GET /api/recommendations` | Just visits page | Visual proof, no typing |
| **AI Chat** | `POST /api/chat` | Asks a question | Interactive proof, conversational |

Both use the same `UserContext Cache` → same Flink-enriched context.

---

## Talking Points for Q&A

| Question | Answer |
|----------|--------|
| Why Kafka over REST? | Decoupling + replay capability + real-time streaming to Flink |
| Why Flink over Kafka Streams? | SQL interface, managed on Confluent Cloud, better for windowed aggregations |
| Cold start time? | ~15 seconds with current window configuration |
| Why not collaborative filtering? | Individual behavior is clearer for demo; architecture supports adding it via second Flink job |
| Latency breakdown? | Kafka: <10ms, Flink: <100ms, Cache: <1ms, Gemini: <400ms |
| Why not Redis for cache? | We use in-memory cache with Kafka replay. On restart, we replay the `user-profiles` topic to rebuild the cache in seconds—no Redis needed. This leverages Kafka's retention as our persistence layer. For production scale with multiple instances, we'd add Redis. |
| Why two recommendation methods? | Home page is passive (visual proof), chat is active (interactive proof). Same underlying context powers both. |

---

## Backup Plans

| Issue | Solution |
|-------|----------|
| Flink job not running | Show pre-recorded video of the flow |
| Kafka connection fails | Use local Docker Kafka as fallback |
| Gemini API slow | Have cached responses ready to show |
| Demo user has stale data | Use incognito browser / new user ID |
| Recommendations not updating | Refresh page, check Flink job status |

---

## Key Metrics to Mention

- **7 Kafka topics** for event streaming
- **< 500ms** end-to-end latency
- **~15 seconds** cold start to personalization
- **2 recommendation surfaces**: Home page + AI chat
- **Modular monolith** architecture with 7 modules

---

## API Endpoints for Demo

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

*Last updated: December 11, 2025*
