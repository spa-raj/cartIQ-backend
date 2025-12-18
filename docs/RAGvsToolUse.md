# RAG vs Tool Use in CartIQ AI Chat

This document explains the two approaches for AI-powered product discovery in CartIQ and when to use each.

## Overview

| Approach | Purpose | Best For |
|----------|---------|----------|
| **RAG** | Semantic similarity search | Meaning-based queries |
| **Tool Use** | Structured API calls | Filter-based queries |

Both approaches return **real products** from the catalog, just through different paths.

---

## RAG (Retrieval-Augmented Generation)

### How It Works

```
User Query → Embed Query → Vector Search → Products with Metadata → Gemini Response
```

### Implementation

Most vector databases allow storing metadata alongside embeddings:

```java
// When indexing products
VectorEntry {
    embedding: [0.12, -0.34, 0.56, ...],  // From product name + description
    metadata: {
        productId: 123,
        name: "Sony WH-1000XM5",
        price: 24990,
        category: "Electronics/Headphones",
        rating: 4.7,
        imageUrl: "...",
        description: "..."
    }
}
```

### Query Flow

When user asks "best noise cancelling headphones":

```java
// 1. Embed the user query
float[] queryEmbedding = embeddingModel.embed("best noise cancelling headphones");

// 2. Vector search returns full product data (not just IDs)
List<ProductMetadata> similarProducts = vectorDB.search(
    embedding: queryEmbedding,
    topK: 5
);

// 3. Pass products to Gemini for response formulation
String aiResponse = gemini.generate(
    context: similarProducts,
    query: "best noise cancelling headphones"
);

// 4. Return both AI response and actual products
return ChatResponse {
    aiResponse: "Based on your query, here are the top noise-cancelling headphones...",
    products: similarProducts
};
```

### Response Format

```json
{
    "aiResponse": "Based on your query, here are the top noise-cancelling headphones. The Sony WH-1000XM5 leads with exceptional ANC and 30-hour battery life...",
    "products": [
        { "id": 123, "name": "Sony WH-1000XM5", "price": 24990, "rating": 4.7, ... },
        { "id": 456, "name": "Bose QC45", "price": 22990, "rating": 4.5, ... }
    ]
}
```

### Why Store Full Metadata (Not Just IDs)?

| Approach | Pros | Cons |
|----------|------|------|
| **IDs only → Query API** | Always fresh data | Extra API call, latency |
| **Full metadata** | Single query, fast | Need to sync if data changes |

For the hackathon (static catalog), storing full metadata is simpler and faster.

---

## Tool Use (Function Calling)

### How It Works

```
User Query → Gemini Decides Tool → API Call → Real Products → Gemini Response
```

### Available Tools

| Tool | Purpose | Parameters |
|------|---------|------------|
| `searchProducts` | Filter products | category, minPrice, maxPrice, minRating |
| `getProductDetails` | Get specific product | productId |
| `getCategories` | List categories | none |

### Implementation

```java
// Define tools for Gemini
FunctionDeclaration searchProducts = FunctionDeclaration.builder()
    .name("searchProducts")
    .description("Search products by category, price range, or rating")
    .parameters(Schema.builder()
        .addProperty("category", Schema.Type.STRING, "Product category")
        .addProperty("minPrice", Schema.Type.NUMBER, "Minimum price")
        .addProperty("maxPrice", Schema.Type.NUMBER, "Maximum price")
        .addProperty("minRating", Schema.Type.NUMBER, "Minimum rating (0-5)")
        .build())
    .build();

// Gemini decides when to call tools
GenerateContentResponse response = gemini.generateContent(
    userQuery,
    tools: [searchProducts, getProductDetails, getCategories]
);

// If Gemini calls a tool, execute it
if (response.hasFunctionCall()) {
    FunctionCall call = response.getFunctionCall();
    List<Product> results = executeToolCall(call);
    // Feed results back to Gemini for final response
}
```

### Query Flow

When user asks "Show me headphones under ₹500":

```
1. Gemini recognizes this needs structured filtering
2. Gemini calls: searchProducts(category="headphones", maxPrice=500)
3. ProductService queries MySQL with filters
4. Real products returned to Gemini
5. Gemini formulates response with actual product data
```

---

## When to Use Which?

| User Query | Approach | Reasoning |
|------------|----------|-----------|
| "best headphones for gym" | RAG | Semantic - "gym" implies sweat-resistant, secure fit |
| "headphones under ₹500" | Tool Use | Structured filter - exact price range |
| "compare Sony WH-1000XM5 vs Bose QC45" | Tool Use | Specific product lookup by name |
| "wireless earbuds with long battery" | RAG | Semantic - battery life isn't always filterable |
| "something for my morning runs" | RAG | Intent-based, needs semantic understanding |
| "top rated electronics" | Tool Use | Filter by rating + category |
| "comfortable headphones for long flights" | RAG | Semantic - comfort, travel context |

### Decision Logic

```
User Query
    │
    ├── Has explicit filters (price, rating, category)?
    │       └── YES → Tool Use
    │
    ├── Asks for specific products by name?
    │       └── YES → Tool Use (getProductDetails)
    │
    ├── Intent/meaning-based query?
    │       └── YES → RAG
    │
    └── Ambiguous?
            └── Let Gemini decide (it can use both)
```

---

## Combined Architecture for CartIQ

The most powerful approach is letting Gemini orchestrate both:

```
User: "I need comfortable headphones for long flights under ₹20000"
                            │
                            ▼
                    ┌───────────────┐
                    │    Gemini     │
                    │   Decides     │
                    └───────┬───────┘
                            │
            ┌───────────────┼───────────────┐
            ▼               ▼               ▼
    ┌──────────────┐ ┌─────────────┐ ┌─────────────┐
    │     RAG      │ │  Tool Call  │ │   Combine   │
    │  "comfortable│ │ maxPrice:   │ │   Results   │
    │  for flights"│ │   20000     │ │             │
    └──────────────┘ └─────────────┘ └─────────────┘
            │               │               │
            └───────────────┴───────────────┘
                            │
                            ▼
                    ┌───────────────┐
                    │ Final Response│
                    │ + Products    │
                    └───────────────┘
```

---

## Event-Driven Integration

Both RAG and Tool Use interactions should emit Kafka events:

```java
// After RAG search
eventProducer.send(AISearchEvent {
    userId: user.getId(),
    query: "comfortable headphones for flights",
    searchType: "RAG",
    returnedProductIds: [123, 456, 789],
    timestamp: now()
});

// After Tool call
eventProducer.send(AISearchEvent {
    userId: user.getId(),
    query: "headphones under 20000",
    searchType: "TOOL_CALL",
    filters: { maxPrice: 20000, category: "headphones" },
    returnedProductIds: [234, 567],
    timestamp: now()
});
```

These events feed into Flink for user profile aggregation, enabling personalized future responses.

---

## Summary

| Aspect | RAG | Tool Use |
|--------|-----|----------|
| **Query Type** | Semantic/meaning-based | Structured/filter-based |
| **Data Source** | Vector DB | MySQL via APIs |
| **Returns** | Similar products | Filtered products |
| **Best For** | "Find me something like..." | "Show me X under ₹Y" |
| **Implementation** | Embedding + vector search | Function declarations |

Both return **real products** and both emit **Kafka events** for the event-driven architecture.
