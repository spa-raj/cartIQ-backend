# CartIQ - 19-Day Implementation Plan

**Hackathon:** AI Partner Catalyst: Accelerate Innovation (Confluent Challenge)
**Deadline:** December 31, 2025 (2:00 PM PT)
**Start Date:** December 12, 2025
**Days Remaining:** 19 days
**Daily Commitment:** 10-12 hours (avg 11h/day = 209 hours total)

---

## Progress Summary

### Completed Modules
- [x] cartiq-common (Shared utilities)
- [x] cartiq-user (Authentication, profiles, JWT)
- [x] cartiq-product (Product catalog, categories, search)
- [x] cartiq-order (Cart, orders, checkout)
- [x] cartiq-app (Main application assembly)
- [x] cartiq-kafka (Confluent Cloud setup, topics created, event publishing working)

### In Progress
- [ ] cartiq-rag (Production RAG module) **← NEW**
- [ ] cartiq-ai (Gemini integration pending)

### Remaining Work
- [ ] GCP RAG Infrastructure (Vector Search, Redis)
- [ ] Production RAG Pipeline (Embeddings, Indexing, Re-ranking)
- [ ] Flink Streaming (User context aggregation) **← Critical Path**
- [ ] Vertex AI / Gemini integration
- [ ] Data Seeder (Demo data + live simulation)
- [ ] Frontend (React + Firebase Hosting)
- [ ] Demo Video + Submission

---

## What's New: Production-Grade RAG

This plan includes a **production-grade RAG architecture** for product recommendations:

| Component | Technology | Purpose |
|-----------|------------|---------|
| **Vector Store** | Vertex AI Vector Search | Scalable semantic search |
| **Embedding Cache** | Cloud Memorystore (Redis) | Reduce API latency |
| **Incremental Indexing** | Spring Events + Async | Real-time index updates |
| **Re-ranking** | Vertex AI Ranking API | Two-stage retrieval precision |

See `docs/updateArchitecture.md` for full technical details.

---

## Judging Criteria & Strategy

| Criteria | Weight | Strategy |
|----------|--------|----------|
| **Technological Implementation** | 25% | Kafka → Flink → RAG → Gemini pipeline |
| **Design/UX** | 25% | Clean React UI + AI chat widget + Firebase Hosting |
| **Potential Impact** | 25% | "Democratizing personalization for small businesses" |
| **Quality of Idea** | 25% | "Cold Start Killer" + Production-grade RAG |

---

## 19-Day Timeline Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         19-DAY EXECUTION PLAN                               │
│                    Target: 11h/day avg (209h total)                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  PHASE 1: GCP INFRASTRUCTURE (Days 1-2)                      22h           │
│  ════════════════════════════════════════════════════════════════════════  │
│  Day 1:  Vertex AI Vector Search + Redis setup                11h          │
│  Day 2:  Gemini integration + basic chat endpoint             11h          │
│                                                                             │
│  PHASE 2: RAG PIPELINE (Days 3-6)                            44h           │
│  ════════════════════════════════════════════════════════════════════════  │
│  Day 3:  cartiq-rag module + EmbeddingService + cache         11h          │
│  Day 4:  VectorSearchService + startup indexer                11h          │
│  Day 5:  Incremental indexer + product CRUD events            11h          │
│  Day 6:  ReRanker + ProductRetriever + AI integration         11h          │
│                                                                             │
│  PHASE 3: FLINK STREAMING (Days 7-8)                         22h           │
│  ════════════════════════════════════════════════════════════════════════  │
│  Day 7:  Flink SQL tables + user behavior aggregation         11h          │
│  Day 8:  UserContext cache + Kafka consumer + testing         11h          │
│                                                                             │
│  PHASE 4: FRONTEND (Days 9-12)                               44h           │
│  ════════════════════════════════════════════════════════════════════════  │
│  Day 9:  React setup + routing + product listing              11h          │
│  Day 10: Product detail + cart functionality                  11h          │
│  Day 11: Checkout + order flow + auth                         11h          │
│  Day 12: AI chat widget + event tracking + home recs          11h          │
│                                                                             │
│  PHASE 5: INTEGRATION & POLISH (Days 13-15)                  33h           │
│  ════════════════════════════════════════════════════════════════════════  │
│  Day 13: End-to-end testing + cold start optimization         11h          │
│  Day 14: Data seeder + demo data preparation                  11h          │
│  Day 15: Deployment (Cloud Run + Firebase) + bug fixes        11h          │
│                                                                             │
│  PHASE 6: DEMO & SUBMIT (Days 16-19)                         44h           │
│  ════════════════════════════════════════════════════════════════════════  │
│  Day 16: Production smoke testing + final bug fixes           11h          │
│  Day 17: Demo video recording                                 11h          │
│  Day 18: Demo video editing + upload                          11h          │
│  Day 19: Final testing + Devpost submission                   11h          │
│                                                                             │
│  TOTAL: 209 hours                                                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Daily Plan

### Phase 1: GCP Infrastructure (Days 1-2)

#### Day 1 - GCP RAG Infrastructure (11h)

**Goals:**
- Set up Vertex AI Vector Search index
- Set up Cloud Memorystore Redis
- Configure networking and IAM

**Tasks:**
```
□ Vertex AI Vector Search:
  □ Enable Vertex AI API in GCP Console
  □ Create Vector Search index:
    - Display name: cartiq-products-index
    - Dimensions: 768 (for text-embedding-004)
    - Distance measure: COSINE_DISTANCE
    - Update method: STREAM_UPDATE
  □ Create index endpoint
  □ Deploy index to endpoint
  □ Note: Index creation takes ~30-60 minutes

□ Cloud Memorystore Redis:
  □ Create Redis instance (1GB Basic tier)
  □ Note VPC network and IP address
  □ Configure firewall rules if needed

□ IAM & Service Accounts:
  □ Create service account for RAG
  □ Grant roles:
    - Vertex AI User
    - Vertex AI Feature Store User
    - Redis Client (if using IAM auth)
  □ Download credentials JSON

□ Test connectivity:
  □ Verify Vector Search endpoint accessible
  □ Verify Redis connection from local
```

**Deliverables:**
- Vector Search index deployed and ready
- Redis instance running
- Service account configured

---

#### Day 2 - Gemini Integration (11h)

**Goals:**
- Complete Vertex AI Gemini setup
- Implement basic chat endpoint
- Test shopping assistant responses

**Tasks:**
```
□ Vertex AI Gemini Setup:
  □ Verify Vertex AI API enabled
  □ Create VertexAIConfig.java in cartiq-ai module
  □ Configure application.properties:
    - GCP_PROJECT_ID
    - GCP_LOCATION (us-central1)
    - GOOGLE_APPLICATION_CREDENTIALS

□ Implement GeminiService.java:
  □ Initialize GenerativeModel client
  □ Create generateContent() method
  □ Add system prompt for shopping assistant persona
  □ Handle streaming responses (optional)

□ Implement Chat Endpoint:
  □ Create ChatController.java (/api/chat/**)
  □ Create ChatRequest DTO (userId, message)
  □ Create ChatResponse DTO (response, contextUsed, productIds)
  □ Wire up controller → service

□ Test scenarios:
  □ "Recommend me a laptop under $1000"
  □ "What's the best phone for photography?"
  □ "Compare iPhone 15 and Samsung S24"
  □ Verify responses are helpful and on-topic
```

**Deliverables:**
- Working /api/chat endpoint
- Gemini responding as shopping assistant
- Basic conversation flow tested

---

### Phase 2: RAG Pipeline (Days 3-6)

#### Day 3 - RAG Module: Embeddings + Cache (11h)

**Goals:**
- Create cartiq-rag module structure
- Implement embedding service with Redis caching
- Test embedding generation

**Tasks:**
```
□ Create cartiq-rag module:
  □ Create directory structure:
    cartiq-rag/
    ├── pom.xml
    └── src/main/java/com/cartiq/rag/
        ├── config/
        ├── embedding/
        ├── vectorstore/
        ├── indexing/
        ├── reranking/
        ├── retrieval/
        └── dto/

  □ Create pom.xml with dependencies:
    - cartiq-common
    - cartiq-product
    - spring-boot-starter-data-redis
    - google-cloud-aiplatform

□ Implement VertexEmbeddingClient.java:
  □ Initialize PredictionServiceClient
  □ Create embed(String text) method
  □ Use text-embedding-004 model
  □ Return float[] embedding (768 dimensions)

□ Implement CachedEmbeddingService.java:
  □ Inject RedisTemplate<String, byte[]>
  □ Cache keys: emb:product:{id}, emb:query:{hash}
  □ Implement embedProduct(Long productId, String text)
  □ Implement embedQuery(String query)
  □ Add cache invalidation methods
  □ Add metrics (cache hit/miss counters)

□ Add Redis configuration:
  □ Create RedisConfig.java
  □ Configure connection factory
  □ Configure serializers

□ Unit tests:
  □ Test embedding generation
  □ Test cache hit/miss behavior
  □ Test cache invalidation
```

**Deliverables:**
- cartiq-rag module compiles
- Embeddings generated via Vertex AI
- Redis caching working

---

#### Day 4 - RAG Module: Vector Store (11h)

**Goals:**
- Implement Vertex AI Vector Search client
- Implement startup product indexer
- Test semantic search

**Tasks:**
```
□ Implement VectorSearchService.java:
  □ Initialize MatchServiceClient
  □ Configure index endpoint URL
  □ Implement upsertDatapoint(ProductDatapoint)
  □ Implement upsertDatapoints(List<ProductDatapoint>) for batch
  □ Implement removeDatapoint(String id)
  □ Implement search(float[] embedding, int topK, filters)

□ Create supporting classes:
  □ ProductDatapoint.java (id, embedding, category, inStock)
  □ RetrievalFilters.java (categories, priceRange, inStockOnly)
  □ ProductTextBuilder.java (builds text for embedding)

□ Implement StartupProductIndexer.java:
  □ @EventListener(ApplicationReadyEvent.class)
  □ Fetch all products from ProductRepository
  □ Generate embeddings (batch)
  □ Upsert to Vector Search index
  □ Log progress (indexed X/Y products)

□ Test semantic search:
  □ Index sample products
  □ Search for "wireless headphones"
  □ Verify relevant products returned
  □ Test with category filters
```

**Deliverables:**
- Products indexed in Vector Search
- Semantic search returning relevant results
- Startup indexing working

---

#### Day 5 - RAG Module: Incremental Indexing (11h)

**Goals:**
- Implement event-driven indexing for CRUD operations
- Update cartiq-product to publish events
- Test real-time index updates

**Tasks:**
```
□ Create event classes in cartiq-rag:
  □ ProductEvent.java (sealed interface)
  □ ProductCreatedEvent.java (record)
  □ ProductUpdatedEvent.java (record with changedFields)
  □ ProductDeletedEvent.java (record)

□ Update ProductService in cartiq-product:
  □ Inject ApplicationEventPublisher
  □ Publish ProductCreatedEvent in createProduct()
  □ Publish ProductUpdatedEvent in updateProduct()
    - Only if embedding-relevant fields changed
    - Track changedFields (name, description, category)
  □ Publish ProductDeletedEvent in deleteProduct()

□ Implement IncrementalProductIndexer.java:
  □ @EventListener for ProductCreatedEvent
    - Generate embedding
    - Upsert to Vector Search
  □ @EventListener for ProductUpdatedEvent
    - Invalidate embedding cache
    - Generate new embedding
    - Upsert to Vector Search
  □ @EventListener for ProductDeletedEvent
    - Invalidate embedding cache
    - Remove from Vector Search
  □ All listeners @Async("indexingExecutor")

□ Create AsyncConfig.java:
  □ Define indexingExecutor ThreadPoolTaskExecutor
  □ Core pool: 2, Max pool: 5, Queue: 100

□ Test incremental indexing:
  □ Create product → appears in search
  □ Update product name → search reflects change
  □ Delete product → removed from search
```

**Deliverables:**
- Product CRUD triggers index updates
- Cache invalidation working
- Real-time index updates verified

---

#### Day 6 - RAG Module: Retrieval Pipeline (11h)

**Goals:**
- Implement re-ranker for precision
- Implement two-stage ProductRetriever
- Integrate RAG with ChatService

**Tasks:**
```
□ Implement ReRanker interface:
  □ rerank(String query, List<ProductDocument> candidates)
  □ Returns List<RankedProduct> (product + score)

□ Implement VertexReRanker.java:
  □ Initialize RankServiceClient
  □ Build RankRequest with query and candidates
  □ Parse RankResponse and map back to products
  □ Return top-N results

□ Implement QueryBuilder.java:
  □ buildRetrievalQuery(UserContext ctx, String userMessage)
  □ Combine user message + browsing context
  □ Add interested categories
  □ Add recently viewed products (top 3)

□ Implement ProductRetriever.java:
  □ Inject EmbeddingService, VectorSearchService, ReRanker
  □ retrieve(String query, RetrievalFilters filters):
    1. Embed query (check cache)
    2. Vector search → top-50 candidates
    3. Re-rank → top-10 results
    4. Return RankedProduct list
  □ Add metrics (latency, candidate counts)

□ Implement PromptBuilder.java:
  □ buildRagPrompt(UserContext, List<RankedProduct>, userMessage)
  □ Format products with ID, name, category, price, description
  □ Include user context
  □ Add instructions for AI

□ Update ChatService.java:
  □ Inject ProductRetriever, PromptBuilder
  □ In chat():
    1. Get UserContext (placeholder for now)
    2. Build retrieval query
    3. Retrieve relevant products
    4. Build RAG-enhanced prompt
    5. Call Gemini
    6. Return response with product IDs

□ End-to-end test:
  □ Send chat message "recommend a laptop"
  □ Verify products retrieved from Vector Search
  □ Verify re-ranking applied
  □ Verify Gemini response references actual products
```

**Deliverables:**
- Two-stage retrieval (recall + precision) working
- RAG-enhanced chat responses
- Products referenced by actual IDs

---

### Phase 3: Flink Streaming (Days 7-8)

#### Day 7 - Flink Setup + Aggregation (11h)

**Goals:**
- Set up Flink on Confluent Cloud
- Create source tables for Kafka topics
- Implement user behavior aggregation

**Tasks:**
```
□ Confluent Cloud Flink Setup:
  □ Create Flink compute pool (us-central1)
  □ Create Flink SQL workspace
  □ Connect to Kafka cluster

□ Create source tables:
  □ product_views table:
    CREATE TABLE product_views (
      user_id STRING,
      product_id STRING,
      product_name STRING,
      category STRING,
      price DECIMAL(10,2),
      event_time TIMESTAMP(3),
      WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND
    ) WITH (
      'connector' = 'kafka',
      'topic' = 'product-views',
      'properties.bootstrap.servers' = '...',
      'properties.security.protocol' = 'SASL_SSL',
      'properties.sasl.mechanism' = 'PLAIN',
      'properties.sasl.jaas.config' = '...',
      'format' = 'json'
    );

  □ cart_events table (similar structure)
  □ order_events table (similar structure)

□ Create aggregation query (15-sec windows):
  CREATE TABLE user_profiles WITH (
    'connector' = 'kafka',
    'topic' = 'user-profiles',
    ...
  ) AS
  SELECT
    user_id,
    LISTAGG(product_name) as viewed_products,
    LISTAGG(DISTINCT category) as interested_categories,
    COUNT(*) as view_count,
    MAX(price) as max_price_viewed,
    MIN(price) as min_price_viewed,
    TUMBLE_START(event_time, INTERVAL '15' SECOND) as window_start,
    TUMBLE_END(event_time, INTERVAL '15' SECOND) as window_end
  FROM product_views
  GROUP BY user_id, TUMBLE(event_time, INTERVAL '15' SECOND);

□ Test aggregation:
  □ Send test events via API
  □ Verify user-profiles topic receives aggregated data
  □ Check 15-second window behavior
```

**Deliverables:**
- Flink compute pool running
- User behavior aggregation working
- user-profiles topic populated

---

#### Day 8 - UserContext Cache (11h)

**Goals:**
- Implement in-memory UserContext cache
- Create Kafka consumer for user-profiles
- Integrate context with RAG pipeline

**Tasks:**
```
□ Create UserContext model:
  □ userId, viewedProducts, interestedCategories
  □ viewCount, maxPriceViewed, minPriceViewed
  □ windowStart, windowEnd, lastUpdated

□ Implement UserContextCache.java:
  □ ConcurrentHashMap<String, UserContext>
  □ get(String userId) → UserContext or null
  □ put(String userId, UserContext)
  □ clear()

□ Implement UserProfileConsumer.java:
  □ @KafkaListener(topics = "user-profiles")
  □ Parse JSON message to UserContext
  □ Update cache with latest context
  □ Log updates for debugging

□ Implement cache rebuild on startup:
  □ Configure consumer to read from beginning
  □ On ApplicationReadyEvent:
    - Seek to beginning of user-profiles topic
    - Consume all messages to rebuild cache
    - Log "Cache rebuilt with X user contexts"

□ Integrate with ChatService:
  □ Inject UserContextCache
  □ Get context at start of chat()
  □ Pass to QueryBuilder and PromptBuilder
  □ Include contextUsed in response

□ End-to-end test:
  □ Browse products on API (simulate events)
  □ Wait 15+ seconds for Flink window
  □ Call /api/chat
  □ Verify response mentions browsed products
```

**Deliverables:**
- UserContext cache populated from Kafka
- Cache rebuilds on app restart
- AI responses use real-time context

---

### Phase 4: Frontend (Days 9-12)

#### Day 9 - React Setup + Products (11h)

**Goals:**
- Set up React application
- Implement product listing
- Connect to backend API

**Tasks:**
```
□ Create React app:
  npx create-vite cartiq-frontend --template react-ts
  cd cartiq-frontend

□ Install dependencies:
  npm install @shadcn/ui axios zustand react-router-dom
  npm install -D tailwindcss postcss autoprefixer
  npx tailwindcss init -p
  npx shadcn-ui@latest init

□ Setup project structure:
  src/
  ├── components/
  │   ├── ui/           (shadcn components)
  │   ├── layout/       (Header, Footer)
  │   └── product/      (ProductCard, ProductGrid)
  ├── pages/
  │   ├── Home.tsx
  │   ├── Products.tsx
  │   ├── ProductDetail.tsx
  │   ├── Cart.tsx
  │   └── Checkout.tsx
  ├── store/            (zustand stores)
  ├── api/              (axios client)
  └── types/            (TypeScript types)

□ Setup routing (react-router-dom):
  /              → Home
  /products      → Products
  /products/:id  → ProductDetail
  /cart          → Cart
  /checkout      → Checkout

□ Implement API client:
  □ Configure axios base URL
  □ Add auth interceptor (JWT)
  □ Create productApi.ts

□ Implement Products page:
  □ Fetch products from /api/products
  □ ProductCard component
  □ ProductGrid layout
  □ Category filter (sidebar or dropdown)
  □ Search bar

□ Create basic layout:
  □ Header with logo, nav, cart icon
  □ Footer
```

**Deliverables:**
- React app running locally
- Product listing working
- API connection established

---

#### Day 10 - Product Detail + Cart (11h)

**Goals:**
- Implement product detail page
- Implement cart functionality
- Track product views to Kafka

**Tasks:**
```
□ Implement ProductDetail page:
  □ Fetch product by ID
  □ Display product info (image, name, description, price)
  □ Add to cart button
  □ Quantity selector
  □ Related products section (optional)

□ Implement cart store (zustand):
  □ Cart state: items[], total
  □ Actions: addItem, removeItem, updateQuantity, clearCart
  □ Persist to localStorage

□ Implement Cart page:
  □ List cart items
  □ Update quantity controls
  □ Remove item button
  □ Cart total calculation
  □ "Proceed to Checkout" button

□ Track product views:
  □ On ProductDetail mount:
    POST /api/events/product-view
    { userId, productId, productName, category, price }
  □ Add debounce to prevent duplicate events

□ Track cart events:
  □ On addItem:
    POST /api/events/cart-add
    { userId, productId, quantity }
  □ On removeItem:
    POST /api/events/cart-remove
    { userId, productId }
```

**Deliverables:**
- Product detail page working
- Cart add/remove/update working
- Events flowing to Kafka

---

#### Day 11 - Checkout + Auth (11h)

**Goals:**
- Implement checkout flow
- Implement authentication pages
- Integrate JWT auth

**Tasks:**
```
□ Implement Checkout page:
  □ Order summary (items, quantities, total)
  □ Shipping address form (mock for demo)
  □ Payment section (mock "Pay Now" button)
  □ Place order → POST /api/orders
  □ Order confirmation display

□ Track order events:
  □ On order placed:
    POST /api/events/order-placed
    { userId, orderId, items, total }

□ Implement auth store (zustand):
  □ State: user, token, isAuthenticated
  □ Actions: login, logout, register
  □ Persist token to localStorage

□ Implement Login page:
  □ Email/password form
  □ POST /api/auth/login
  □ Store token, redirect to home

□ Implement Register page:
  □ Name, email, password form
  □ POST /api/auth/register
  □ Auto-login after register

□ Add auth interceptor:
  □ Attach Authorization header to requests
  □ Handle 401 → redirect to login

□ Protected routes:
  □ Wrap routes requiring auth
  □ Redirect to login if not authenticated
```

**Deliverables:**
- Checkout flow working
- User authentication working
- Orders created successfully

---

#### Day 12 - AI Chat Widget + Recommendations (11h)

**Goals:**
- Implement AI chat widget
- Implement home page recommendations
- Complete frontend feature set

**Tasks:**
```
□ Implement AI Chat Widget:
  □ Floating chat button (bottom-right corner)
  □ Chat drawer/modal component
  □ Message list (user + AI messages)
  □ Input field + send button
  □ POST /api/chat on send
  □ Display AI responses
  □ Show recommended products as clickable cards
  □ Loading state while AI responds

□ Implement Home page recommendations:
  □ "Recommended For You" section
  □ GET /api/recommendations?userId={userId}
  □ Display personalized product grid
  □ Fallback to "Trending Products" if no context

□ Update Home page:
  □ Hero section with tagline
  □ Recommended For You section
  □ Featured categories
  □ Call-to-action buttons

□ Polish UI:
  □ Loading states for all API calls
  □ Error handling and display
  □ Empty states (empty cart, no results)
  □ Responsive design (mobile-friendly)

□ Test complete flow:
  □ Browse products → events tracked
  □ Wait 15+ seconds
  □ Check home page → recommendations changed!
  □ Open chat → AI knows what you browsed
```

**Deliverables:**
- AI chat widget working
- Home page recommendations personalized
- Complete e-commerce flow

---

### Phase 5: Integration & Polish (Days 13-15)

#### Day 13 - Testing + Cold Start (11h)

**Goals:**
- End-to-end integration testing
- Measure and optimize cold start
- Fix any integration bugs

**Tasks:**
```
□ Cold Start measurement:
  □ New user arrives (no history)
  □ Note: Home page shows trending products
  □ Browse 3-4 products (~20 seconds)
  □ Return to home page after Flink window
  □ Measure time to personalized recommendations
  □ Target: ~15-20 seconds

□ End-to-end flow testing:
  □ Register → Browse → Add to Cart → Checkout
  □ Verify Kafka events at each step
  □ Verify Flink aggregation timing
  □ Verify RAG retrieval quality
  □ Verify AI response relevance

□ Integration bug fixes:
  □ Fix any CORS issues
  □ Fix any auth token issues
  □ Fix any event schema mismatches
  □ Fix any timing issues

□ RAG quality testing:
  □ Test various queries
  □ Verify retrieved products are relevant
  □ Verify re-ranking improves results
  □ Adjust similarity thresholds if needed

□ Performance testing:
  □ Measure API response times
  □ Measure chat response times
  □ Identify any bottlenecks
```

**Deliverables:**
- Cold start documented (~15 sec)
- All integrations verified
- Major bugs fixed

---

#### Day 14 - Data Seeder (11h)

**Goals:**
- Create demo data seeder
- Prepare realistic product catalog
- Create event simulator for demo

**Tasks:**
```
□ Implement DataSeederService.java:
  □ Fetch products from DummyJSON API or custom JSON
  □ Categories: Electronics, Clothing, Home, Sports, etc.
  □ ~100-200 products for demo
  □ Create 5-10 test users
  □ Run on startup (dev profile only)

□ Product data requirements:
  □ Realistic names and descriptions
  □ Appropriate price ranges
  □ Good category distribution
  □ Product images (URLs or placeholders)

□ Implement EventSimulator.java (optional):
  □ Admin endpoint: POST /api/admin/simulate/start
  □ Simulates user browsing patterns
  □ Generates realistic event sequences
  □ Useful for demo if no live users

□ Seed data for demo scenarios:
  □ User "alice" interested in Electronics
  □ User "bob" interested in Sports
  □ User "carol" interested in Home & Kitchen
  □ Pre-populate some browsing history

□ Test with seeded data:
  □ Verify products indexed in Vector Search
  □ Verify semantic search works
  □ Verify recommendations quality
```

**Deliverables:**
- Demo database seeded
- Realistic product catalog
- Demo scenarios prepared

---

#### Day 15 - Deployment (11h)

**Goals:**
- Deploy backend to Cloud Run
- Deploy frontend to Firebase Hosting
- Verify production environment

**Tasks:**
```
□ Backend deployment:
  □ Build JAR: mvn clean package -DskipTests
  □ Create Dockerfile (if not exists)
  □ Build Docker image
  □ Push to Google Container Registry
  □ Deploy to Cloud Run:
    gcloud run deploy cartiq-backend \
      --image gcr.io/PROJECT_ID/cartiq-backend \
      --platform managed \
      --region us-central1 \
      --allow-unauthenticated \
      --set-env-vars "..."

□ Configure Cloud Run environment:
  □ CONFLUENT_BOOTSTRAP_SERVERS
  □ CONFLUENT_API_KEY / SECRET
  □ GCP_PROJECT_ID
  □ REDIS_HOST / PORT
  □ VECTOR_SEARCH_INDEX_ENDPOINT
  □ Database connection (Cloud SQL or H2)

□ Frontend deployment:
  □ Update API base URL to Cloud Run URL
  □ Build: npm run build
  □ Firebase init: firebase init hosting
  □ Deploy: firebase deploy

□ Post-deployment verification:
  □ Test all API endpoints
  □ Test frontend flows
  □ Verify Kafka connectivity
  □ Verify Vector Search connectivity
  □ Verify Redis connectivity
  □ Test AI chat responses

□ CORS configuration:
  □ Allow Firebase hosting domain
  □ Test cross-origin requests
```

**Deliverables:**
- Backend live on Cloud Run
- Frontend live on Firebase
- All services connected

---

### Phase 6: Demo & Submit (Days 16-19)

#### Day 16 - Production Testing (11h)

**Goals:**
- Comprehensive production testing
- Fix any production-specific bugs
- Prepare demo environment

**Tasks:**
```
□ Production smoke testing:
  □ Test on multiple browsers (Chrome, Firefox, Safari)
  □ Test on mobile devices
  □ Test all user flows end-to-end
  □ Verify no console errors

□ Production bug fixes:
  □ Fix any production-only issues
  □ Handle edge cases
  □ Improve error messages

□ Performance verification:
  □ Check Cloud Run logs for errors
  □ Monitor response times
  □ Check Redis cache hit rates
  □ Verify Flink processing

□ Demo preparation:
  □ Create demo user account
  □ Clear/reset demo data if needed
  □ Prepare demo script walkthrough
  □ Test demo flow multiple times
```

**Deliverables:**
- Production fully tested
- Demo environment ready
- No critical bugs

---

#### Day 17 - Demo Video Recording (11h)

**Goals:**
- Write demo script
- Record demo video
- Capture all key features

**Tasks:**
```
□ Write demo script (3 minutes):

  0:00-0:15 - Intro
  "Hi, I'm [name] and this is CartIQ - an AI shopping assistant
   that personalizes recommendations in just 15 seconds."

  0:15-0:30 - Show empty state
  "When a new user visits, they see trending products.
   No personalization yet."

  0:30-1:00 - Browse products
  "Watch as I browse some electronics...
   [Click through 3-4 products]
   Each view is streamed to Kafka in real-time."

  1:00-1:30 - Show personalization (WOW moment!)
  "Now I return to the home page... and look!
   The recommendations changed - all electronics!
   This happened in just 15 seconds with NO account needed."

  1:30-2:00 - AI Chat
  "Let me ask the AI for recommendations...
   [Type: 'recommend a laptop for programming']
   Notice it references products from our actual catalog,
   not generic suggestions. That's RAG in action."

  2:00-2:30 - Architecture explanation
  [Show architecture diagram]
  "Here's how it works:
   - Kafka streams user events
   - Flink aggregates behavior in 15-sec windows
   - RAG retrieves relevant products from Vector Search
   - Gemini generates personalized responses"

  2:30-3:00 - Wrap up
  "CartIQ solves the cold start problem for small businesses.
   Personalization that used to take weeks now takes seconds.
   Thank you!"

□ Recording setup:
  □ Clean browser (incognito mode)
  □ Hide bookmarks bar
  □ 1080p screen recording
  □ Clear, quiet audio
  □ Good lighting if showing face

□ Record demo:
  □ Practice 2-3 times first
  □ Record full demo
  □ Capture screen + audio
  □ Record backup take
```

**Deliverables:**
- Demo script finalized
- Raw video recorded
- Backup recording saved

---

#### Day 18 - Demo Video Editing (11h)

**Goals:**
- Edit demo video
- Add polish (captions, overlays)
- Upload to YouTube

**Tasks:**
```
□ Video editing:
  □ Trim dead time and mistakes
  □ Adjust audio levels
  □ Add intro/outro slides
  □ Add architecture diagram overlay
  □ Add captions/subtitles
  □ Add transitions between sections
  □ Optional: background music (subtle)

□ Export settings:
  □ Resolution: 1080p minimum
  □ Format: MP4 (H.264)
  □ Frame rate: 30fps

□ Upload to YouTube:
  □ Title: "CartIQ - AI Shopping Assistant | Confluent Hackathon"
  □ Description with:
    - Brief project summary
    - Technologies used
    - Team members
    - Links to GitHub and live demo
  □ Tags: AI, Kafka, Flink, Gemini, RAG, e-commerce
  □ Visibility: Public or Unlisted
  □ Thumbnail: Custom with CartIQ branding

□ Final review:
  □ Watch full video
  □ Check audio sync
  □ Verify all sections clear
  □ Get feedback if possible
```

**Deliverables:**
- Polished demo video
- Uploaded to YouTube
- Shareable link ready

---

#### Day 19 - Final Submission (11h)

**Goals:**
- Final production verification
- Complete Devpost submission
- Submit before deadline!

**Tasks:**
```
□ Final verification:
  □ Test live URLs one more time
  □ Verify video is accessible
  □ Test on friend's device if possible

□ Documentation:
  □ Update README.md with:
    - Project description
    - Architecture overview
    - Setup instructions
    - Environment variables
    - API documentation
  □ Ensure LICENSE file exists (MIT)
  □ Add architecture diagram to repo

□ Devpost submission:
  □ Project title: "CartIQ - AI Shopping Assistant"
  □ Tagline: "Personalized recommendations in ~15 seconds"

  □ Description:
    ## What it does
    CartIQ is an AI-powered shopping assistant that delivers
    personalized product recommendations in just 15 seconds -
    solving the e-commerce cold start problem.

    ## How we built it
    - **Kafka** streams user events in real-time
    - **Flink** aggregates behavior in 15-second windows
    - **RAG** retrieves products from Vertex AI Vector Search
    - **Gemini** generates personalized recommendations
    - **React** frontend with AI chat widget

    ## Key Features
    - Cold start personalization (~15 seconds)
    - Two recommendation surfaces (home page + AI chat)
    - Production-grade RAG with re-ranking
    - Real-time streaming architecture

  □ Links:
    ✓ Live demo: https://cartiq-xxx.web.app
    ✓ GitHub: https://github.com/xxx/cartiq-backend
    ✓ Demo video: https://youtube.com/watch?v=xxx

  □ Screenshots (5):
    1. Home page with personalized recommendations
    2. AI chat widget conversation
    3. Product browsing page
    4. Architecture diagram
    5. Confluent Cloud dashboard (optional)

  □ Built with:
    Apache Kafka, Apache Flink, Google Cloud Vertex AI,
    Gemini, React, Spring Boot, Redis

  □ Select challenge: Confluent Challenge

□ Final checklist:
  ✓ Hosted URL works
  ✓ Video is accessible (not private)
  ✓ GitHub repo is public
  ✓ All Devpost fields filled
  ✓ Submitted before 2:00 PM PT deadline

□ SUBMIT! 🎉
```

**Deliverables:**
- Complete Devpost submission
- All links verified working
- Submitted before deadline!

---

## Updated Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         CARTIQ ARCHITECTURE (with RAG)                          │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  ┌──────────┐                                                                   │
│  │ Frontend │ (React + Firebase Hosting)                                        │
│  └────┬─────┘                                                                   │
│       │                                                                         │
│       ├────────────────────────────┬──────────────────────┐                     │
│       │ (events)                   │ (page load)          │ (chat)              │
│       ▼                            ▼                      ▼                     │
│  ┌─────────┐              ┌─────────────────┐    ┌─────────────────┐            │
│  │ Kafka   │              │ GET /api/       │    │ POST /api/chat  │            │
│  │ Topics  │              │ recommendations │    │                 │            │
│  └────┬────┘              └────────┬────────┘    └────────┬────────┘            │
│       │                            │                      │                     │
│       ▼                            └──────────┬───────────┘                     │
│  ┌─────────┐                                  │                                 │
│  │ Flink   │ (15-sec windows)                 ▼                                 │
│  └────┬────┘                        ┌───────────────────┐                       │
│       │                             │ UserContext Cache │                       │
│       │ write profiles              └─────────┬─────────┘                       │
│       ▼                                       │                                 │
│  ┌─────────┐        consume                   │                                 │
│  │ user-   │ ─────────────────────────────────┤                                 │
│  │ profiles│                                  ▼                                 │
│  └─────────┘                     ┌─────────────────────────────────────────┐    │
│                                  │              AI MODULE                   │    │
│                                  │                                          │    │
│  ┌─────────────────────────────┐ │  UserContext + Query                     │    │
│  │      RAG PIPELINE           │ │         │                                │    │
│  │                             │ │         ▼                                │    │
│  │  ┌─────────┐  ┌──────────┐  │ │  ┌─────────────┐    ┌────────────────┐   │    │
│  │  │ Product │─▶│ Startup  │  │ │  │   Query     │───▶│ Embedding Cache│   │    │
│  │  │   DB    │  │ Indexer  │  │ │  │  Builder    │    │    (Redis)     │   │    │
│  │  └────┬────┘  └────┬─────┘  │ │  └──────┬──────┘    └───────┬────────┘   │    │
│  │       │            │        │ │         │                   │            │    │
│  │       │ CRUD       │embed   │ │         │ embed      miss   │            │    │
│  │       ▼            ▼        │ │         ▼                   ▼            │    │
│  │  ┌──────────┐ ┌──────────┐  │ │  ┌─────────────────────────────────┐     │    │
│  │  │Incremental│ │ Vertex AI│  │ │  │    Vertex AI Vector Search     │     │    │
│  │  │ Indexer  │─▶│ Vector   │◀─┼─┼──│      (semantic search)         │     │    │
│  │  └──────────┘ │ Search   │  │ │  └──────────────┬──────────────────┘     │    │
│  │               └──────────┘  │ │                 │ top-50                 │    │
│  └─────────────────────────────┘ │                 ▼                        │    │
│                                  │  ┌─────────────────────────────────┐     │    │
│                                  │  │    Cross-Encoder Re-Ranker      │     │    │
│                                  │  │    (Vertex AI Ranking API)      │     │    │
│                                  │  └──────────────┬──────────────────┘     │    │
│                                  │                 │ top-10                 │    │
│                                  │                 ▼                        │    │
│                                  │  ┌─────────────────────────────────┐     │    │
│                                  │  │       Prompt Builder            │     │    │
│                                  │  │  (context + products + query)   │     │    │
│                                  │  └──────────────┬──────────────────┘     │    │
│                                  │                 │                        │    │
│                                  │                 ▼                        │    │
│                                  │  ┌─────────────────────────────────┐     │    │
│                                  │  │         Gemini 2.5 Flash        │     │    │
│                                  │  │    (personalized response)      │     │    │
│                                  │  └─────────────────────────────────┘     │    │
│                                  └──────────────────────────────────────────┘    │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Technology Stack

| Layer | Technology | Deployment |
|-------|------------|------------|
| Frontend | React + TypeScript + shadcn/ui | Firebase Hosting |
| Backend | Spring Boot 3.2 (Modular Monolith) | Google Cloud Run |
| Database | H2 (demo) / Cloud SQL (prod) | GCP |
| Streaming | Apache Kafka | Confluent Cloud |
| Processing | Apache Flink | Confluent Cloud |
| Vector Store | Vertex AI Vector Search | GCP |
| Embedding Cache | Cloud Memorystore (Redis) | GCP |
| AI/ML | Gemini 2.5 Flash + RAG | Vertex AI |

---

## Risk Mitigation

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Vector Search setup issues | Medium | Day 1 dedicated; fallback to in-memory |
| Re-ranker API issues | Low | Can disable re-ranking (config flag) |
| Flink setup complex | Medium | Start Day 7; fallback: direct Kafka → cache |
| Frontend delays | Medium | Use shadcn/ui heavily; cut animations |
| Integration bugs | Medium | Days 13-15 buffer for fixes |
| Demo failure | Low | Pre-record backup video Day 17 |

---

## Success Criteria

### Must Achieve
1. ✅ Events flowing: Spring Boot → Kafka (working)
2. [ ] Flink aggregation → user-profiles topic
3. [ ] RAG pipeline: Vector Search → Re-rank → Gemini
4. [ ] Two recommendation surfaces: Home page + AI chat
5. [ ] Cold Start: Personalized in ~15 seconds
6. [ ] Live demo URL working
7. [ ] Complete Devpost submission

### Differentiators
- **Production-grade RAG** with Vector Search + Re-ranking
- **Embedding cache** for low latency
- **Real-time indexing** on product changes
- **Cold Start Killer** - personalized in ~15 seconds
- **SMB impact narrative** - democratizing personalization

---

## Go/No-Go Checkpoints

| Day | Checkpoint | Go Criteria | Fallback |
|-----|------------|-------------|----------|
| 1 | GCP Infrastructure | Vector Search deployed | Use in-memory store |
| 6 | RAG Pipeline | End-to-end retrieval working | Simplify to basic search |
| 8 | Flink + Context | User context in AI responses | Direct Kafka → cache |
| 12 | Frontend | Core flows working | Simplify UI |
| 15 | Deployment | Production URLs live | Debug and fix |

---

## Daily Checklist Template

```
Date: ___________
Day: ___ of 19

Goals for today:
□ _________________
□ _________________
□ _________________

Completed:
✓ _________________
✓ _________________

Blockers:
- _________________

Tomorrow:
□ _________________
□ _________________
```

---

*Last updated: December 12, 2025*
