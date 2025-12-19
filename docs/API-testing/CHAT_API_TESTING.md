# CartIQ Chat API Testing Guide

This guide explains how to test the AI Chat API using curl commands.

## Base URL

Set the `BASE_URL` variable before running the commands:

```bash
# Local development
export BASE_URL="http://localhost:8082"

# Production (Cloud Run)
export BASE_URL="https://your-cloud-run-url.run.app"
```

---

## Endpoints Overview

| Endpoint | Method | Description | Auth |
|----------|--------|-------------|------|
| `/api/chat` | POST | Send chat message to AI assistant | None |
| `/api/chat/message` | POST | Legacy endpoint (same as above) | None |
| `/api/chat/health` | GET | Health check for AI service | None |

---

## Request Schema

### ChatRequest

```json
{
  "message": "string (required) - User's chat message",
  "userId": "string (optional) - User ID for tracking",
  "recentlyViewedProductIds": ["string"] ,
  "recentCategories": ["string"],
  "cartProductIds": ["string"],
  "cartTotal": 0.00,
  "pricePreference": "budget | mid-range | premium",
  "preferredCategories": ["string"]
}
```

### Headers (Optional)

| Header | Description |
|--------|-------------|
| `X-Session-Id` | Session ID for conversation continuity |
| `X-User-Id` | Alternative way to pass user ID |

---

## Response Schema

### ChatResponse

```json
{
  "sessionId": "string - Session ID for follow-up messages",
  "message": "string - AI-generated response",
  "products": [ProductDTO],
  "hasProducts": true/false,
  "processingTimeMs": 1234
}
```

### ProductDTO (in response)

```json
{
  "id": "uuid",
  "name": "Product Name",
  "description": "Product description",
  "price": 999.00,
  "brand": "Brand Name",
  "categoryName": "Electronics",
  "rating": 4.5,
  "thumbnailUrl": "https://...",
  "inStock": true
}
```

---

## Test Examples

### 1. Basic Product Search

```bash
curl -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "show me headphones",
    "userId": "test-user-001"
  }'
```

### 2. Search with Price Filter

```bash
curl -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "laptops under 50000",
    "userId": "test-user-002"
  }'
```

### 3. Search with Category

```bash
curl -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "show me wireless earbuds in electronics",
    "userId": "test-user-003"
  }'
```

### 4. Product Comparison

```bash
curl -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "compare Sony headphones vs JBL headphones",
    "userId": "test-user-004"
  }'
```

### 5. Brand Search

```bash
curl -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "show me boAt products",
    "userId": "test-user-005"
  }'
```

### 6. Featured Products

```bash
curl -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "what are your best sellers?",
    "userId": "test-user-006"
  }'
```

### 7. Category Discovery

```bash
curl -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "what categories do you have?",
    "userId": "test-user-007"
  }'
```

### 8. With User Context (Personalization)

```bash
curl -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "recommend something for me",
    "userId": "test-user-008",
    "pricePreference": "budget",
    "preferredCategories": ["Electronics", "Accessories"],
    "recentCategories": ["Headphones", "Mobile Accessories"]
  }'
```

### 9. With Session ID (Conversation Continuity)

```bash
curl -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: my-session-123" \
  -d '{
    "message": "show me more like this",
    "userId": "test-user-009"
  }'
```

### 10. Health Check

```bash
curl -X GET "$BASE_URL/api/chat/health"
```

---

## Parse Response with jq

### Get message and product count

```bash
curl -s -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{"message": "show me headphones", "userId": "test"}' \
  | jq '{message: .message[0:200], productCount: (.products | length), processingTimeMs}'
```

### Get product names and prices

```bash
curl -s -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{"message": "laptops under 50000", "userId": "test"}' \
  | jq '.products[] | {name: .name[0:50], price, brand}'
```

### Get just product IDs

```bash
curl -s -X POST "$BASE_URL/api/chat" \
  -H "Content-Type: application/json" \
  -d '{"message": "headphones", "userId": "test"}' \
  | jq '[.products[].id]'
```

---

## Kafka Events

Each chat query that triggers a tool call publishes an event to the `ai-events` Kafka topic:

```json
{
  "eventId": "uuid",
  "userId": "test-user-001",
  "sessionId": "session-uuid",
  "query": "laptops under 50000",
  "searchType": "HYBRID",
  "toolName": "searchProducts",
  "category": "Electronics",
  "minPrice": 0,
  "maxPrice": 50000,
  "resultsCount": 10,
  "returnedProductIds": ["uuid1", "uuid2", ...],
  "processingTimeMs": 1234,
  "timestamp": "2025-12-19T10:02:39.456Z"
}
```

### Search Types

| searchType | Description |
|------------|-------------|
| `HYBRID` | Vector Search + FTS + Reranker (used by `searchProducts`) |
| `FTS` | Full-text search only (used by other tools) |

---

## Tool Functions

The AI uses these tools internally:

| Tool | Trigger Examples | Search Type |
|------|------------------|-------------|
| `searchProducts` | "show me laptops", "headphones under 5000" | HYBRID |
| `getProductDetails` | "tell me about Sony XM5" | FTS |
| `getCategories` | "what categories do you have?" | N/A |
| `getFeaturedProducts` | "what's popular?", "best sellers" | FTS |
| `compareProducts` | "compare iPhone vs Samsung" | FTS |
| `getProductsByBrand` | "show me Apple products" | FTS |

---

## Common Issues

| Issue | Solution |
|-------|----------|
| Empty products array | Try different search terms; check if products exist in DB |
| Slow response (>5s) | Gemini API latency; check Vector Search availability |
| No AI response | Check Vertex AI credentials and project configuration |

---

*Last updated: December 19, 2025*
