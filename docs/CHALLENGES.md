# CartIQ Backend - Challenges & Solutions

This document captures key technical challenges encountered during development and how they were solved.

---

## Table of Contents

1. [Suggestions API Showing Wrong Products](#1-suggestions-api-showing-wrong-products)
2. ["Trending Now" Showing Duplicate/Homogeneous Products](#2-trending-now-showing-duplicatehomogeneous-products)
3. [Category API Returning productCount: 0](#3-category-api-returning-productcount-0)
4. [Parent Categories Returning 0 Products](#4-parent-categories-returning-0-products)
5. [Similar Products Showing Unrelated Items](#5-similar-products-showing-unrelated-items)
6. [AI Chat Returning Wrong Products for Category Queries](#6-ai-chat-returning-wrong-products-for-category-queries)
7. [Hybrid Search Returning 0 Results for Brand + Category + Price Queries](#7-hybrid-search-returning-0-results-for-brand--category--price-queries)
8. [Key Learnings](#key-learnings)

---

## 1. Suggestions API Showing Wrong Products

**Date:** December 2024

### Problem
The "Suggested For You" section was showing completely irrelevant products. For example:
- User searched for "headphones" in AI chat
- User profile showed `aiSearchQueries: ["Recommend me headphones under 6000"]`
- But suggestions displayed jeans, bio-oil, and women's t-shirts instead of headphones

### Root Cause
The suggestions service was using `aiSearchCategories` for vector search instead of the actual search queries. The category inference was flawed:

1. Full-text search (FTS) for "headphones" returned wrong products (t-shirts, cameras) due to search indexing issues
2. Category was inferred from the first FTS result, leading to wrong categories like "Electronics" (generic) instead of "On-Ear" (specific)
3. Vector search on "Electronics" returned random electronics products, not headphones

**Data flow that caused the bug:**
```
User query: "Recommend me headphones under 6000"
    ↓
FTS search for "headphones" → Returns wrong products (indexing issue)
    ↓
Category inference from first result → "Electronics" (too generic)
    ↓
aiSearchCategories stored: ["Electronics"]
    ↓
Suggestions API uses "Electronics" for vector search
    ↓
Returns random electronics (jeans were miscategorized) instead of headphones
```

### Solution
Modified `getAIIntentProducts()` in `SuggestionsService.java` to use search queries directly for vector search instead of relying on inferred categories.

**New search priority:**
1. **`aiSearchQueries`** (highest) - User's actual AI chat queries like "Recommend me headphones under 6000"
2. **`recentSearchQueries`** - User's search bar queries like "iphone", "kurta"
3. **Categories** (fallback) - Only if queries don't provide enough results

**Code change summary:**
```java
// BEFORE: Used categories (which were often wrong)
List<String> categories = profile.getAiSearchCategories();
vectorSearchService.search(category, limit, filters);

// AFTER: Use actual queries first (much more reliable)
if (hasValidStrings(profile.getAiSearchQueries())) {
    for (String query : profile.getAiSearchQueries()) {
        vectorSearchService.searchWithExpansion(query, limit, filters, 2);
    }
}
if (hasValidStrings(profile.getRecentSearchQueries())) {
    for (String query : profile.getRecentSearchQueries()) {
        vectorSearchService.search(query, limit, filters);
    }
}
// Fall back to categories only if needed
```

**Result:** Suggestions now show headphones when user searched for headphones, because vector search on "Recommend me headphones under 6000" semantically matches headphone products.

---

## 2. "Trending Now" Showing Duplicate/Homogeneous Products

**Date:** December 2024

### Problem
The "Trending Now" section displayed all similar products from a single category (e.g., 8 essential oil products in a row), making it look repetitive and unhelpful.

### Root Cause
`getTopFeaturedProducts()` in `ProductService.java` sorted by `featured`, `rating`, `reviewCount` without considering category diversity. Products from well-rated categories dominated the results.

### Solution
Implemented round-robin category selection with randomization:

```java
// Group products by category
Map<String, List<Product>> productsByCategory = allProducts.stream()
    .collect(Collectors.groupingBy(p -> p.getCategory().getName()));

// Shuffle within each category for variety
productsByCategory.values().forEach(list -> Collections.shuffle(list, random));

// Round-robin select from each category
for (int i = 0; i < maxIterations && results.size() < limit; i++) {
    for (List<Product> categoryProducts : productsByCategory.values()) {
        if (i < categoryProducts.size()) {
            results.add(categoryProducts.get(i));
        }
    }
}
```

**Result:** Trending section now shows diverse products from multiple categories, refreshed order on each page load.

---

## 3. Category API Returning productCount: 0

**Date:** December 2024

### Problem
`GET /api/categories` returned `productCount: 0` for all categories, even though products existed.

### Root Cause
The `Category` entity had a lazy-loaded `products` collection. Calling `category.getProducts().size()` outside a transaction returned 0 because the collection wasn't fetched.

### Solution
Added explicit COUNT queries in `CategoryRepository`:

```java
@Query("SELECT c.id, COUNT(p) FROM Category c LEFT JOIN c.products p " +
       "WHERE c.active = true GROUP BY c.id")
List<Object[]> getProductCountsByCategory();
```

Used the counts map in `CategoryService` to set product counts without relying on lazy loading.

---

## 4. Parent Categories Returning 0 Products

**Date:** December 2024

### Problem
`GET /api/products/category/{id}` returned 0 products for parent categories like "Electronics" even though child categories like "Headphones > On-Ear" had products.

### Root Cause
Products are assigned to leaf categories only. The query `findByCategoryId()` only searched the exact category, not descendants.

### Solution
Implemented hierarchical category queries using path-based descendant lookup:

```java
// Get all descendant category IDs
List<UUID> descendantIds = categoryRepository.findDescendantCategoryIds(category.getPath());
allCategoryIds.add(categoryId);
allCategoryIds.addAll(descendantIds);

// Search across all categories
productRepository.findByCategoryIdInAndStatusActive(allCategoryIds, pageable);
```

Also updated `CategoryService` to show hierarchical product counts (parent shows sum of all descendants).

---

## 5. Similar Products Showing Unrelated Items

**Date:** December 2024

### Problem
"Similar to [Product X]" suggestions showed products from completely different categories. For example, viewing a laptop showed bio-oil as "similar."

### Root Cause
Vector search returns semantically similar products based on embeddings, but semantic similarity doesn't always mean category relevance. A "premium quality" laptop might match a "premium quality" skincare product.

### Solution
Added category filtering to the similar products strategy:

```java
// Get the category of the source product
String sourceCategory = recentProduct.getCategoryName();

// Filter vector search results to same category only
List<ProductDTO> filteredProducts = allProducts.stream()
    .filter(p -> sourceCategory.equalsIgnoreCase(p.getCategoryName()))
    .limit(limit)
    .toList();

// If no products in same category, return empty (don't show irrelevant "similar" products)
if (filteredProducts.isEmpty()) {
    return List.of();
}
```

**Result:** "Similar to X" now only shows products from the same category, or nothing at all (better than misleading suggestions).

---

## 6. AI Chat Returning Wrong Products for Category Queries

**Date:** December 2024

### Problem
When users asked the AI chatbot for specific product categories, it returned completely irrelevant products:
- Query: "Recommend me Samsung mobile phones"
- Results: iPhones, Samsung TVs, desk lamps, floor cleaners, chips

### Root Cause Analysis

**Multiple issues contributed to this problem:**

#### Issue 1: FTS Index Missing Category Name
The PostgreSQL full-text search `search_vector` column only included:
```sql
to_tsvector('english', name || ' ' || description || ' ' || brand)
```

When searching for "Samsung smartphones", the word "smartphones" wasn't in the search vector, so FTS returned 0 results or irrelevant matches.

#### Issue 2: Vector Search Semantic Ambiguity
Vector search matched "Samsung" strongly but didn't distinguish between product types. "Samsung Galaxy S24" (phone) and "Samsung 55-inch TV" both contain "Samsung" with high semantic similarity.

#### Issue 3: Category Filter Too Aggressive
Initial fix attempted to filter results by category, but when category filter found 0 matches (due to naming mismatches like "Smartphones" vs "mobile phones"), it returned empty or fell back to unfiltered results.

#### Issue 4: Embedding Text Format
Product embeddings placed category at the end with less semantic weight:
```
"Samsung Galaxy S24. 256GB storage... Brand: Samsung. Category: Smartphones."
```
The category "Smartphones" didn't match queries like "mobile phones" semantically.

### Solution

**1. Added Category to FTS Index** (`db/migrations/V002__add_category_to_fts.sql`)

Updated the `search_vector` trigger to include category name with weighted search:
```sql
CREATE OR REPLACE FUNCTION products_search_vector_trigger() RETURNS trigger AS $$
DECLARE
    category_name TEXT;
BEGIN
    SELECT c.name INTO category_name FROM categories c WHERE c.id = NEW.category_id;

    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(NEW.name, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(category_name, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.brand, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C');
    RETURN NEW;
END
$$ LANGUAGE plpgsql;
```

**2. Improved Embedding Text with Category Synonyms** (`EmbeddingService.java`)

Moved category to the start of embedding text and added synonyms:
```java
public String buildProductEmbeddingText(String name, String description, String brand, String categoryName) {
    StringBuilder sb = new StringBuilder();

    // Start with category and synonyms for stronger semantic matching
    if (categoryName != null && !categoryName.isBlank()) {
        sb.append("Category: ").append(categoryName);
        String synonyms = getCategorySynonyms(categoryName);
        if (!synonyms.isEmpty()) {
            sb.append(" (").append(synonyms).append(")");
        }
        sb.append(". ");
    }
    // ... rest of the text
}

private String getCategorySynonyms(String categoryName) {
    String lower = categoryName.toLowerCase();
    if (lower.contains("smartphone") || lower.equals("mobiles")) {
        return "mobile phones, cell phones, handsets, cellular phones";
    }
    if (lower.contains("television") || lower.equals("tvs")) {
        return "TVs, TV sets, smart TVs, LED TV, OLED TV";
    }
    // ... more mappings
}
```

**New embedding text format:**
```
"Category: Smartphones (mobile phones, cell phones, handsets). Samsung Galaxy S24. Brand: Samsung. 256GB storage..."
```

**3. Category Boosting Instead of Filtering** (`ProductToolService.java`)

Changed from strict filtering to boosting - category matches come first but don't exclude other results:
```java
// 1. Vector Search (semantic) - NO category filter
vectorResults = executeVectorSearch(query, null, minPrice, maxPrice, minRating);

// 2. FTS Search (keyword) - NO category filter
ftsResults = executeFtsSearch(query, null, minPrice, maxPrice, minRating);

// 3. Category-specific search (ensure target category products are included)
if (category != null) {
    categoryResults = productService.getProductsByCategoryWithFilters(categoryId, ...);
}

// 4. Combine with category results first (boosting)
Map<UUID, ProductDTO> combined = new LinkedHashMap<>();
for (ProductDTO p : categoryResults) combined.put(p.getId(), p);  // First
for (ProductDTO p : vectorResults) combined.putIfAbsent(p.getId(), p);
for (ProductDTO p : ftsResults) combined.putIfAbsent(p.getId(), p);
```

**4. AI-Powered Category Selection** (`GeminiService.java`)

Added all available categories to the AI system prompt so Gemini can select the correct category name:
```java
private String getCategoryListForPrompt() {
    List<CategoryDTO> categories = categoryService.getAllCategories();
    return categories.stream()
        .map(CategoryDTO::getName)
        .distinct()
        .sorted()
        .collect(Collectors.joining(", "));
}
```

System prompt now includes:
```
AVAILABLE CATEGORIES: Smartphones, Televisions, Laptops, Headphones, ...

CATEGORY SELECTION RULES:
- Use EXACT category names from the list above
- "mobile phones" → use "Smartphones"
- "TVs" → use "Televisions"
```

### Deployment Steps

1. **Apply FTS migration:**
   ```bash
   psql -h <host> -U <user> -d cartiq -f db/migrations/V002__add_category_to_fts.sql
   ```

2. **Deploy code changes** (new embedding text format)

3. **Re-index vector search** (to regenerate embeddings with synonyms):
   ```bash
   curl -X POST "https://<api>/api/internal/indexing/batch/start" \
     -H "X-Internal-Api-Key: <key>"
   ```

### Result
After applying all fixes:
- **FTS**: "Samsung smartphones" matches products in "Smartphones" category
- **Vector Search**: "mobile phones" semantically matches embeddings containing "mobile phones, cell phones"
- **Category boost**: Even if vector/FTS return some wrong results, category-specific products appear first

---

## 7. Hybrid Search Returning 0 Results for Brand + Category + Price Queries

**Date:** December 2024

### Problem
AI chatbot returned 0 results when users asked for brand + category + price combinations:
- Query: "Recommend me Samsung mobile phones under 30000"
- Expected: Budget Samsung phones
- Actual: 0 results (or expensive products >₹45,000)

### Root Cause Analysis

**Issue 1: Same Query Sent to Both Vector Search and FTS**

The hybrid search passed the identical query to both search engines:
```java
// Both received: "Samsung mobile phones under 30000"
vectorResults = executeVectorSearch(query, ...);
ftsResults = executeFtsSearch(query, ...);
```

**Issue 2: PostgreSQL FTS Uses AND Logic**

FTS requires ALL terms to match. The query "Samsung mobile phones" failed because:
- `FTS("Samsung")` → 18 results ✓
- `FTS("Samsung mobile phones")` → 0 results ✗ (products don't contain "mobile")

PostgreSQL FTS tokenizes and requires all tokens to be present in the search vector.

**Issue 3: Brand Search Ignored Price Filters**

`getProductsByBrand("Samsung")` returned top 30 Samsung products by default ordering:
- 27 Samsung TVs (₹30,000 - ₹200,000)
- 2 Samsung phones (both >₹45,000)
- 1 Samsung tablet

No budget phones appeared because price filters weren't applied to brand search.

### Solution

**1. Split Query Strategy** (`ProductToolService.java`)

Use different queries for different search engines based on their strengths:

```java
// Vector search: full natural language (semantic understanding)
String vectorQuery = (query != null && !query.isBlank()) ? query : brand;

// FTS search: brand keyword only (exact matching, won't break)
String ftsQuery = (brand != null && !brand.isBlank()) ? brand : query;

log.info("Search queries: vectorQuery='{}', ftsQuery='{}'", vectorQuery, ftsQuery);

// 1. Vector Search - understands "mobile phones" semantically
vectorResults = executeVectorSearch(vectorQuery, ...);

// 2. FTS Search - robust exact match on "Samsung"
ftsResults = executeFtsSearchWithLimit(ftsQuery, ...);
```

**2. Brand Search with Price Filters** (`ProductRepository.java`)

Added new query that filters by price and orders by price ASC:

```java
@Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' " +
       "AND LOWER(p.brand) = LOWER(:brand) " +
       "AND (:minPrice IS NULL OR p.price >= :minPrice) " +
       "AND (:maxPrice IS NULL OR p.price <= :maxPrice) " +
       "AND (:minRating IS NULL OR p.rating >= :minRating) " +
       "ORDER BY p.price ASC")
Page<Product> findByBrandWithFilters(@Param("brand") String brand,
                                     @Param("minPrice") BigDecimal minPrice,
                                     @Param("maxPrice") BigDecimal maxPrice,
                                     @Param("minRating") BigDecimal minRating,
                                     Pageable pageable);
```

**3. Updated Hybrid Search Flow**

```
User: "Samsung mobile phones under 30000"
         ↓
    ┌────┴────┐
    │ Gemini  │ → Extracts: query="Samsung mobile phones", brand="Samsung", maxPrice=30000
    └────┬────┘
         ↓
   ┌─────┴─────┐
   │  Hybrid   │
   │  Search   │
   └─────┬─────┘
         ↓
┌────────┼────────┬────────────┐
↓        ↓        ↓            ↓
Vector   FTS    Category    Brand+Price
"Samsung "Samsung" Smartphones Samsung
mobile               filter   <30000
phones"                       ASC
    ↓        ↓        ↓            ↓
Semantic  Exact   Category    Budget
matches   brand   products    Samsung
          match              phones first
    └────────┴────────┴────────────┘
                  ↓
           Merge + Dedupe
           (Brand results first)
                  ↓
           Rerank top 10
                  ↓
           Return results
```

### Files Changed

| File | Change |
|------|--------|
| `ProductToolService.java:101-108` | Split `vectorQuery` vs `ftsQuery` |
| `ProductToolService.java:137-147` | Use `getProductsByBrandWithFilters` |
| `ProductRepository.java:46-56` | Added `findByBrandWithFilters()` |
| `ProductService.java:87-106` | Added `getProductsByBrandWithFilters()` |

### Result
- Query "Samsung mobile phones under 30000" now returns Samsung phones under ₹30,000
- Vector search handles semantic understanding ("mobile phones" → Smartphones)
- FTS provides robust brand matching without breaking on natural language
- Brand search surfaces budget products first (ordered by price ASC)

---

## Key Learnings

1. **Don't trust inferred data over raw data** - Using actual search queries is more reliable than categories inferred from search results

2. **Diversity matters in recommendations** - Round-robin selection across categories prevents monotonous results

3. **Lazy loading pitfalls** - Always use explicit queries for counts instead of collection.size() outside transactions

4. **Hierarchical data needs special handling** - Parent-child relationships require recursive or path-based queries

5. **Vector search needs guardrails** - Semantic similarity should be constrained by categorical relevance for "similar products" features

6. **FTS indexes must include all searchable fields** - If users search by category, category name must be in the search vector

7. **Synonyms improve semantic search** - Adding "mobile phones" to "Smartphones" embeddings helps match varied user queries

8. **Boost, don't filter** - When search relevance is uncertain, boost preferred results to the top rather than filtering out potentially relevant items

9. **Give AI the vocabulary** - Provide LLMs with exact category names to prevent mismatches between user language and database values

10. **Different search engines need different queries** - Vector search handles natural language semantically; FTS needs exact keywords. Passing the same query to both causes failures when queries contain words not in the index
