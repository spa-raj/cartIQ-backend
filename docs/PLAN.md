# CartIQ - 14-Day Implementation Plan

**Hackathon:** AI Partner Catalyst: Accelerate Innovation (Confluent Challenge)
**Deadline:** December 31, 2025 (2:00 PM PT)
**Start Date:** December 9, 2025
**Target Completion:** December 22, 2025 (8 days buffer)

---

## Progress Summary

### Completed Modules
- [x] cartiq-common (Shared utilities)
- [x] cartiq-user (Authentication, profiles, JWT)
- [x] cartiq-product (Product catalog, categories, search)
- [x] cartiq-order (Cart, orders, checkout)
- [x] cartiq-app (Main application assembly)

### In Progress
- [~] cartiq-kafka (Event producers ready, Confluent Cloud pending)
- [~] cartiq-ai (Basic structure exists, Vertex AI pending)

### Remaining Work
- [ ] Confluent Cloud setup + Kafka event publishing
- [ ] Flink Streaming (User context aggregation) **← Critical Path**
- [ ] Vertex AI / Gemini integration
- [ ] Data Seeder (Demo data + live simulation)
- [ ] Frontend (React + Firebase Hosting)
- [ ] Demo Video + Submission

---

## Judging Criteria & Strategy

| Criteria | Weight | Strategy |
|----------|--------|----------|
| **Technological Implementation** | 25% | Kafka → Flink → Gemini pipeline working end-to-end |
| **Design/UX** | 25% | Clean React UI + AI chat widget + Firebase Hosting |
| **Potential Impact** | 25% | "Democratizing personalization for small businesses" |
| **Quality of Idea** | 25% | "Cold Start Killer" - personalized in ~15 seconds |

---

## 14-Day Timeline

```
┌─────────────────────────────────────────────────────────────────────┐
│  PHASE 1: Core Pipeline (Days 1-6)                                  │
│  ───────────────────────────────────────────────────────────────────│
│  Days 1-2: Kafka + Confluent Cloud                                  │
│  Days 3-4: Vertex AI + Gemini integration                           │
│  Days 5-6: Flink Streaming (Critical Path)                          │
├─────────────────────────────────────────────────────────────────────┤
│  PHASE 2: Frontend + Data (Days 7-10)                               │
│  ───────────────────────────────────────────────────────────────────│
│  Day 7: Data Seeder + Frontend scaffold                             │
│  Days 8-9: Frontend pages (Products, Cart, Checkout)                │
│  Day 10: AI Chat widget + Event tracking                            │
├─────────────────────────────────────────────────────────────────────┤
│  PHASE 3: Polish + Submit (Days 11-14)                              │
│  ───────────────────────────────────────────────────────────────────│
│  Day 11: Cold Start optimization + Integration testing              │
│  Day 12: Deployment (Cloud Run + Firebase Hosting)                  │
│  Day 13: Demo video recording + editing                             │
│  Day 14: Final testing + Devpost submission                         │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Daily Plan

### Days 1-2: Confluent Cloud + Kafka Module

**Goals:**
- Set up Confluent Cloud infrastructure
- Complete Kafka event publishing
- Verify events flowing from Spring Boot → Confluent

**Day 1 Tasks:**
```
□ Create Confluent Cloud account (code: CONFLUENTDEV1)
□ Create Kafka cluster (Basic tier, GCP region matching Cloud Run)
□ Create Input topics (4):
  - user-events
  - product-views
  - cart-events
  - order-events
□ Create Output topic (1):
  - user-profiles (Flink will write aggregated context here)
□ Get cluster credentials (API key + secret)
```

**Day 2 Tasks:**
```
□ Update KafkaConfig.java for Confluent Cloud
□ Complete EventProducer.java
□ Add event publishing to existing controllers:
  - ProductController → product-views
  - CartController → cart-events
  - OrderController → order-events
□ Test: Verify events appear in Confluent Cloud Console
```

**Deliverables:**
- Working Kafka producer → Confluent Cloud
- Events visible in Confluent Console

---

### Days 3-4: AI Module (Vertex AI + Gemini)

**Goals:**
- Set up Vertex AI in GCP
- Complete Gemini chat endpoint
- Shopping assistant persona working

**Day 3 Tasks:**
```
□ GCP Setup:
  - Enable Vertex AI API
  - Create service account with Vertex AI permissions
  - Download credentials JSON
  - Configure application.properties

□ AI Module structure:
  - VertexAIConfig.java
  - GeminiService.java
```

**Day 4 Tasks:**
```
□ Complete AI implementation:
  - ChatController.java (/api/chat/**)
  - ChatRequest, ChatResponse DTOs
  - System prompt for shopping assistant persona

□ Test scenarios:
  - "Recommend me a laptop under $1000"
  - "What's the difference between iPhone 15 and Samsung S24?"
  - Basic conversation flow
```

**Deliverables:**
- Working /api/chat endpoint
- Gemini responding as shopping assistant

---

### Days 5-6: Flink Streaming (Critical Path)

**Goals:**
- Set up Flink on Confluent Cloud
- Real-time user behavior aggregation
- AI receives streaming context

**Day 5 Tasks:**
```
□ Confluent Flink setup:
  - Create Flink compute pool
  - Create Flink SQL workspace
  - Connect to existing Kafka topics

□ Flink SQL tables:
  CREATE TABLE product_views (
    user_id STRING,
    product_id STRING,
    product_name STRING,
    category STRING,
    event_time TIMESTAMP(3),
    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND
  ) WITH (
    'connector' = 'kafka',
    'topic' = 'product-views',
    ...
  );
```

**Day 6 Tasks:**
```
□ User behavior aggregation (15-second windows for demo):
  CREATE TABLE user_profiles AS
  SELECT
    user_id,
    COLLECT(product_name) as viewed_products,
    COLLECT(DISTINCT category) as interested_categories,
    COUNT(*) as view_count,
    MAX(price) as max_price_viewed,
    MIN(price) as min_price_viewed,
    window_start,
    window_end
  FROM TABLE(
    TUMBLE(TABLE product_views, DESCRIPTOR(event_time), INTERVAL '15' SECONDS)
  )
  GROUP BY user_id, window_start, window_end;

□ Create user-profiles output topic in Confluent Cloud
□ Implement UserContext Cache in AI module (with Kafka replay on restart)
□ Test: Browse products → wait 15 sec → context available in AI chat
```

**Deliverables:**
- Flink processing events in real-time
- User context available for AI recommendations

---

### Day 7: Data Seeder + Frontend Scaffold

**Goals:**
- Demo data generation
- Frontend project setup

**Morning (Data Seeder):**
```
□ DataSeederService.java:
  - Fetch products from DummyJSON API
  - Create test users
  - Seed database on startup (dev profile)

□ EventSimulator.java:
  - Simulate user browsing (for demo)
  - Admin endpoint: POST /api/admin/simulate/start
```

**Afternoon (Frontend):**
```
□ Create React app:
  npx create-vite cartiq-frontend --template react-ts

□ Install dependencies:
  - shadcn/ui (components)
  - axios (API calls)
  - zustand (state management)
  - react-router-dom (routing)

□ Setup routing structure:
  /              → Home
  /products      → Product listing
  /products/:id  → Product detail
  /cart          → Cart
  /checkout      → Checkout
  /chat          → AI Chat (or widget)
```

**Deliverables:**
- Seeded database with demo products
- Frontend scaffold with routing

---

### Days 8-9: Frontend Pages

**Day 8 - Products:**
```
□ Home page:
  - Hero section
  - Featured products grid

□ Product listing page:
  - Product cards
  - Category filter
  - Search bar

□ Product detail page:
  - Product info
  - Add to cart button
  - Track product view → Kafka event
```

**Day 9 - Cart & Checkout:**
```
□ Cart page:
  - Cart items list
  - Update quantity
  - Remove item
  - Cart total
  - Proceed to checkout

□ Checkout page:
  - Order summary
  - Place order button
  - Order confirmation

□ Auth pages:
  - Login
  - Register
  - Auth state management
```

**Deliverables:**
- Functional e-commerce flow
- Product → Cart → Checkout working

---

### Day 10: Two Recommendation Surfaces + Event Tracking

**Goals:**
- Home page "Recommended For You" section
- AI chat widget integrated into frontend
- All user actions tracked to Kafka

**Tasks:**
```
□ Home Page Recommendations (Visual Proof):
  - "Recommended For You" section on home page
  - Calls GET /api/recommendations on page load
  - Shows personalized products based on browsing
  - Fallback to trending products for new users

□ AI Chat widget (Interactive Proof):
  - Floating chat button (bottom-right)
  - Chat modal/drawer
  - Message input + send via POST /api/chat
  - Display AI responses
  - Show product recommendations as cards

□ Event tracking integration:
  - Product view → POST /api/events/product-view
  - Add to cart → POST /api/events/cart-add
  - Search → POST /api/events/search

□ Context-aware responses:
  - Both surfaces use same UserContext Cache
  - AI references recently viewed products
  - "Based on your interest in MacBook Pro..."
```

**Deliverables:**
- Working home page recommendations
- Working AI chat widget
- Events flowing from frontend → Kafka

---

### Day 11: Cold Start Optimization + Testing

**Goals:**
- Achieve ~15 second personalization (15-sec tumbling windows)
- End-to-end integration testing

**Tasks:**
```
□ Cold Start measurement:
  - New user arrives (no history)
  - Show home page with trending products (fallback)
  - Browse 3-4 products (~20 seconds)
  - Return to home page after Flink window closes
  - "Recommended For You" shows personalized products!
  - Total time: ~15-20 seconds

□ Demo flow verification:
  - Home page (empty) → Browse → Home page (personalized)
  - Open AI chat, ask for recommendations
  - Both surfaces show same context

□ Flink tuning:
  - Verify 15-second window configuration
  - Test Kafka replay on app restart
  - Ensure cache rebuilds correctly

□ Integration testing:
  - Full flow: Register → Browse → Cart → Checkout
  - Verify Kafka events at each step (4 input topics)
  - Verify Flink aggregation → user-profiles topic
  - Verify AI context awareness

□ Bug fixes:
  - Fix any broken flows
  - Handle edge cases
```

**Deliverables:**
- Documented Cold Start timing (~15 sec)
- All integrations working
- Kafka replay verified on restart

---

### Day 12: Deployment

**Goals:**
- Backend on Cloud Run
- Frontend on Firebase Hosting
- Production environment working

**Morning (Backend):**
```
□ Build Docker image:
  mvn clean package -DskipTests
  docker build -t cartiq-backend .

□ Deploy to Cloud Run:
  gcloud run deploy cartiq-backend \
    --image gcr.io/PROJECT_ID/cartiq-backend \
    --platform managed \
    --region us-central1 \
    --allow-unauthenticated

□ Configure environment:
  - Confluent Cloud credentials
  - Vertex AI credentials
  - Database connection (Cloud SQL or H2 for demo)
```

**Afternoon (Frontend):**
```
□ Firebase setup:
  firebase init hosting

□ Build and deploy:
  npm run build
  firebase deploy

□ Configure:
  - API URL pointing to Cloud Run
  - CORS settings
```

**Evening:**
```
□ Smoke testing:
  - Test all flows on production URLs
  - Verify Kafka events in Confluent Console
  - Verify AI responses
```

**Deliverables:**
- Live backend: https://cartiq-backend-xxx.run.app
- Live frontend: https://cartiq-xxx.web.app

---

### Day 13: Demo Video

**Goals:**
- Record 3-minute demo video
- Edit and upload to YouTube

**Morning (Script + Recording):**
```
□ Demo script (3 minutes) - See docs/demo.md for full script:
  0:00-0:20 - Intro + Show empty recommendations state
  0:20-0:50 - Browse products (events to Kafka)
  0:50-1:20 - Return to home page → Visual wow moment!
             (Recommendations changed without typing!)
  1:20-1:50 - Show AI chat with same context
  1:50-2:30 - Explain architecture diagram
  2:30-3:00 - Wrap up (Kafka replay, future: collaborative filtering)

□ Record demo:
  - Clean browser (incognito), no distractions
  - Show home page "Recommended For You" section
  - Capture the before/after recommendation change
  - Show both surfaces (home page + chat) using same context
```

**Afternoon (Editing):**
```
□ Edit video:
  - Trim dead time
  - Add captions/subtitles
  - Add architecture diagram overlay
  - Background music (optional)

□ Export and upload:
  - 1080p minimum
  - Upload to YouTube (public or unlisted)
  - Add description and tags
```

**Deliverables:**
- 3-minute demo video on YouTube
- Backup recording saved locally

---

### Day 14: Final Testing + Submission

**Goals:**
- Final verification
- Complete Devpost submission

**Morning (Final Testing):**
```
□ End-to-end verification:
  - Test on different browsers
  - Test mobile responsiveness
  - Verify all features work on production

□ Documentation:
  - Update README.md with setup instructions
  - Ensure LICENSE file exists (MIT recommended)
  - Architecture diagram in repo
```

**Afternoon (Submission):**
```
□ Devpost submission:
  - Project title: "CartIQ - AI Shopping Assistant"
  - Tagline: "Personalized recommendations in ~15 seconds"
  - Description:
    • Flink-Enriched Context architecture
    • Two recommendation surfaces (home page + AI chat)
    • 15-sec tumbling windows for fast cold start
    • Kafka replay for cache rebuild (no Redis)
  - Links:
    ✓ Hosted URL (Firebase)
    ✓ GitHub repo (public)
    ✓ Demo video (YouTube)
  - Select: Confluent Challenge
  - Add screenshots (3-5):
    • Home page with "Recommended For You" section
    • AI chat widget
    • Architecture diagram
  - Team information

□ Final checklist:
  ✓ Hosted URL works
  ✓ Video is accessible
  ✓ Repo is public
  ✓ LICENSE file present
  ✓ All required fields filled
```

**Deliverables:**
- Complete Devpost submission
- 8 days buffer before deadline

---

## Technology Stack

| Layer | Technology | Deployment |
|-------|------------|------------|
| Frontend | React + TypeScript + shadcn/ui | Firebase Hosting |
| Backend | Spring Boot 3.2 (Modular Monolith) | Google Cloud Run |
| Database | H2 (demo) / Cloud SQL (prod) | GCP |
| Streaming | Apache Kafka | Confluent Cloud |
| Processing | Apache Flink | Confluent Cloud |
| AI/ML | Gemini 2.5 Flash | Vertex AI |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Client Layer - Firebase Hosting                   │
│                    ┌──────────────────────┐                         │
│                    │   React Frontend     │                         │
│                    └──────────┬───────────┘                         │
│                               │                                      │
│              ┌────────────────┼────────────────┐                    │
│              │ (events)       │ (page load)    │ (chat)             │
│              ▼                ▼                ▼                    │
│         to Kafka    GET /api/recommendations  POST /api/chat        │
└─────────────────────────────────────────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────────┐
│                    API Layer - Google Cloud Run                      │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │              CartIQ Backend (Spring Boot)                       │ │
│  │  ┌──────┐ ┌─────────┐ ┌───────┐ ┌───────┐ ┌────────────┐ ┌────┐│ │
│  │  │ User │ │ Product │ │ Order │ │ Kafka │ │ UserContext│ │ AI ││ │
│  │  │      │ │         │ │       │ │       │ │   Cache    │ │    ││ │
│  │  └──────┘ └─────────┘ └───────┘ └───┬───┘ │(Kafka Rply)│ └──┬─┘│ │
│  │                                     │     └─────┬──────┘    │  │ │
│  │         Admin Sender (Demo Data + Live Sim)     │           │  │ │
│  └─────────────────────────────────────┼───────────┼───────────┼──┘ │
└────────────────────────────────────────┼───────────┼───────────┼────┘
                                         │   consume │    lookup │
┌────────────────────────────────────────▼───────────┼───────────┼────┐
│                      Confluent Cloud               │           │    │
│  ┌─────────────────────────────────────┐           │           │    │
│  │       Kafka Topics (Input)          │           │           │    │
│  │  • user-events    • product-views   │           │           │    │
│  │  • cart-events    • order-events    │           │           │    │
│  └─────────────────┬───────────────────┘           │           │    │
│                    │                               │           │    │
│  ┌─────────────────▼───────────────────┐           │           │    │
│  │          Apache Flink               │           │           │    │
│  │  (User Behavior Aggregation)        │           │           │    │
│  │    15-sec tumbling windows          │           │           │    │
│  └─────────────────┬───────────────────┘           │           │    │
│                    │ write profiles                │           │    │
│  ┌─────────────────▼───────────────────┐           │           │    │
│  │       Kafka Topics (Output)         │───────────┘           │    │
│  │         • user-profiles             │                       │    │
│  └─────────────────────────────────────┘                       │    │
└────────────────────────────────────────────────────────────────┼────┘
                                                                 │
┌────────────────────────────────────────────────────────────────▼────┐
│                    AI/ML Layer - Vertex AI                          │
│                    ┌──────────────────────┐                         │
│                    │   Gemini 2.5 Flash   │                         │
│                    │  (Recommendations)   │                         │
│                    └──────────────────────┘                         │
└─────────────────────────────────────────────────────────────────────┘
```

**Key Data Flow:**
1. Frontend sends events → Kafka Input topics
2. Flink aggregates in 15-sec windows → writes to user-profiles topic
3. UserContext Cache consumes profiles (rebuilds from Kafka on restart)
4. Both `/api/recommendations` and `/api/chat` lookup from same cache
5. AI Module calls Gemini with enriched context

---

## Risk Mitigation

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Flink setup complex | Medium | Start Day 5, fallback: direct Kafka → AI |
| Vertex AI quota issues | Low | Request quota increase early |
| Frontend delays | Medium | Use shadcn/ui for rapid development |
| Integration issues | Medium | Test components independently first |
| Demo failure | Low | Pre-record backup video Day 13 |

---

## Success Criteria

### Must Achieve
1. Events flowing: Spring Boot → Kafka (4 input topics) → Flink → user-profiles topic
2. Two recommendation surfaces: Home page + AI chat (same context)
3. Cold Start: Personalized in ~15 seconds (15-sec tumbling windows)
4. UserContext Cache with Kafka replay on restart
5. Live demo URL working
6. Complete Devpost submission

### Differentiators
- "Cold Start Killer" - personalized in ~15 seconds without historical data
- Visual proof: Home page recommendations change after browsing
- Interactive proof: AI chat with same context
- Kafka replay: No Redis needed, cache rebuilds from Kafka
- SMB impact narrative

---

## Daily Checklist Template

```
Date: ___________
Day: ___ of 14

Goals for today:
□ _________________
□ _________________

Completed:
□ _________________

Blockers:
- _________________

Tomorrow:
□ _________________
```

---

*Last updated: December 11, 2025*
