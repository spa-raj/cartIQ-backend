# CartIQ Backend - Challenges & Solutions

This document captures key technical challenges encountered during development and how they were solved.

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

## Key Learnings

1. **Don't trust inferred data over raw data** - Using actual search queries is more reliable than categories inferred from search results

2. **Diversity matters in recommendations** - Round-robin selection across categories prevents monotonous results

3. **Lazy loading pitfalls** - Always use explicit queries for counts instead of collection.size() outside transactions

4. **Hierarchical data needs special handling** - Parent-child relationships require recursive or path-based queries

5. **Vector search needs guardrails** - Semantic similarity should be constrained by categorical relevance for "similar products" features
