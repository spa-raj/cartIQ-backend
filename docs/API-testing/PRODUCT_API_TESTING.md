# CartIQ Product API Testing Guide

This guide explains how to test the `cartiq-product` module APIs using Postman.

## Prerequisites

1. **Java 17+** installed
2. **Postman** installed
3. **Maven** installed (or use the wrapper)
4. **Admin account** - Write operations require ADMIN role

---

## Authentication Requirements

The Product API has different access levels:

| Operation | Authentication | Role Required |
|-----------|----------------|---------------|
| **Read** (GET all, search, filter) | None | Public |
| **Read by IDs** (POST /batch) | None | Public |
| **Create** (POST) | Required | ADMIN |
| **Update** (PUT/PATCH) | Required | ADMIN |

### Getting an Admin Token

Before testing write operations, you need to login as an admin:

```bash
# Login as admin
curl -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@cartiq.com","password":"Admin123!"}'
```

Save the `accessToken` from the response for authenticated requests.

See [Creating an Admin User](USER_API_TESTING.md#creating-an-admin-user) for setup instructions.

---

## Step 1: Start the Application

From the project root directory, run:

```bash
# Using Maven wrapper (if available)
./mvnw spring-boot:run -pl cartiq-app

# Or using Maven directly
mvn spring-boot:run -pl cartiq-app

# Or build and run the JAR
mvn clean package -DskipTests
java -jar cartiq-app/target/cartiq-app-1.0.0-SNAPSHOT.jar
```

The application will start on **http://localhost:8082**

### Verify the Application is Running

Open in browser: http://localhost:8082/actuator/health

Expected response:
```json
{"status":"UP"}
```

---

## Step 2: Import Postman Collection

Create a new Postman Collection called **"CartIQ Product APIs"** and add the following requests.

### Environment Setup

Create a Postman environment with these variables:
- `base_url`: `http://localhost:8082`
- `token`: (set after login - needed for write operations)
- `category_id`: (set after creating a category)
- `product_id`: (set after creating a product)

---

## Step 3: Category API Endpoints

### 3.1 Get All Categories

**GET** `{{base_url}}/api/categories`

**Headers:**
```
(none required)
```

**Expected Response (200 OK):**
```json
[
    {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "name": "Electronics",
        "description": "Electronic devices and gadgets",
        "imageUrl": "https://example.com/electronics.jpg",
        "parentCategoryId": null,
        "active": true,
        "productCount": 25,
        "subCategories": null,
        "createdAt": "2025-12-07T10:00:00",
        "updatedAt": "2025-12-07T10:00:00"
    },
    {
        "id": "550e8400-e29b-41d4-a716-446655440001",
        "name": "Fashion",
        "description": "Clothing and accessories",
        "imageUrl": "https://example.com/fashion.jpg",
        "parentCategoryId": null,
        "active": true,
        "productCount": 50,
        "subCategories": null,
        "createdAt": "2025-12-07T10:00:00",
        "updatedAt": "2025-12-07T10:00:00"
    }
]
```

---

### 3.2 Get Category Tree (Hierarchical)

**GET** `{{base_url}}/api/categories/tree`

Returns root categories with their subcategories nested.

**Expected Response (200 OK):**
```json
[
    {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "name": "Electronics",
        "description": "Electronic devices and gadgets",
        "imageUrl": "https://example.com/electronics.jpg",
        "parentCategoryId": null,
        "active": true,
        "productCount": 25,
        "subCategories": [
            {
                "id": "550e8400-e29b-41d4-a716-446655440010",
                "name": "Smartphones",
                "description": "Mobile phones",
                "parentCategoryId": "550e8400-e29b-41d4-a716-446655440000",
                "active": true,
                "productCount": 15
            },
            {
                "id": "550e8400-e29b-41d4-a716-446655440011",
                "name": "Laptops",
                "description": "Portable computers",
                "parentCategoryId": "550e8400-e29b-41d4-a716-446655440000",
                "active": true,
                "productCount": 10
            }
        ],
        "createdAt": "2025-12-07T10:00:00",
        "updatedAt": "2025-12-07T10:00:00"
    }
]
```

---

### 3.3 Get Category by ID

**GET** `{{base_url}}/api/categories/{id}`

**Example:** `GET http://localhost:8082/api/categories/550e8400-e29b-41d4-a716-446655440000`

**Expected Response (200 OK):**
```json
{
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Electronics",
    "description": "Electronic devices and gadgets",
    "imageUrl": "https://example.com/electronics.jpg",
    "parentCategoryId": null,
    "active": true,
    "productCount": 25,
    "subCategories": [
        {
            "id": "550e8400-e29b-41d4-a716-446655440010",
            "name": "Smartphones",
            "parentCategoryId": "550e8400-e29b-41d4-a716-446655440000",
            "active": true
        }
    ],
    "createdAt": "2025-12-07T10:00:00",
    "updatedAt": "2025-12-07T10:00:00"
}
```

---

### 3.4 Get Subcategories

**GET** `{{base_url}}/api/categories/{id}/subcategories`

**Example:** `GET http://localhost:8082/api/categories/550e8400-e29b-41d4-a716-446655440000/subcategories`

**Expected Response (200 OK):**
```json
[
    {
        "id": "550e8400-e29b-41d4-a716-446655440010",
        "name": "Smartphones",
        "description": "Mobile phones",
        "parentCategoryId": "550e8400-e29b-41d4-a716-446655440000",
        "active": true,
        "productCount": 15
    },
    {
        "id": "550e8400-e29b-41d4-a716-446655440011",
        "name": "Laptops",
        "description": "Portable computers",
        "parentCategoryId": "550e8400-e29b-41d4-a716-446655440000",
        "active": true,
        "productCount": 10
    }
]
```

---

### 3.5 Create Category (Admin Only)

**POST** `{{base_url}}/api/categories`

**Required Role:** `ADMIN`

**Headers:**
```
Content-Type: application/json
Authorization: Bearer {{token}}
```

**Request Body:**
```json
{
    "name": "Electronics",
    "description": "Electronic devices and gadgets",
    "imageUrl": "https://example.com/electronics.jpg"
}
```

#### Validation Rules

| Field | Validation |
|-------|------------|
| `name` | **Required**, 2-100 characters, must be unique |
| `description` | Max 500 characters |
| `imageUrl` | Optional URL string |
| `parentCategoryId` | Optional UUID of parent category |

**Expected Response (201 Created):**
```json
{
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Electronics",
    "description": "Electronic devices and gadgets",
    "imageUrl": "https://example.com/electronics.jpg",
    "parentCategoryId": null,
    "active": true,
    "productCount": 0,
    "subCategories": null,
    "createdAt": "2025-12-07T10:00:00",
    "updatedAt": "2025-12-07T10:00:00"
}
```

#### Postman Script (Tests tab):
```javascript
if (pm.response.code === 201) {
    var jsonData = pm.response.json();
    pm.environment.set("category_id", jsonData.id);
}
```

---

### 3.6 Create Subcategory (Admin Only)

**POST** `{{base_url}}/api/categories`

**Required Role:** `ADMIN`

**Headers:**
```
Content-Type: application/json
Authorization: Bearer {{token}}
```

**Request Body:**
```json
{
    "name": "Smartphones",
    "description": "Mobile phones and accessories",
    "parentCategoryId": "{{category_id}}"
}
```

**Expected Response (201 Created):**
```json
{
    "id": "550e8400-e29b-41d4-a716-446655440010",
    "name": "Smartphones",
    "description": "Mobile phones and accessories",
    "parentCategoryId": "550e8400-e29b-41d4-a716-446655440000",
    "active": true,
    "productCount": 0
}
```

---

### 3.7 Update Category (Admin Only)

**PUT** `{{base_url}}/api/categories/{id}`

**Required Role:** `ADMIN`

**Headers:**
```
Content-Type: application/json
Authorization: Bearer {{token}}
```

**Request Body:**
```json
{
    "name": "Consumer Electronics",
    "description": "Updated description for electronics category"
}
```

All fields are optional - only provided fields are updated.

**Expected Response (200 OK):**
```json
{
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Consumer Electronics",
    "description": "Updated description for electronics category",
    "imageUrl": "https://example.com/electronics.jpg",
    "parentCategoryId": null,
    "active": true,
    "productCount": 25,
    "createdAt": "2025-12-07T10:00:00",
    "updatedAt": "2025-12-07T10:30:00"
}
```

---

## Step 4: Product API Endpoints

### 4.1 Get All Products (Paginated)

**GET** `{{base_url}}/api/products`

**Query Parameters:**
| Parameter | Default | Description |
|-----------|---------|-------------|
| `page` | 0 | Page number (0-indexed) |
| `size` | 20 | Items per page |
| `sort` | `createdAt,desc` | Sort field and direction |

**Example:** `GET http://localhost:8082/api/products?page=0&size=10&sort=price,asc`

**Expected Response (200 OK):**
```json
{
    "content": [
        {
            "id": "660e8400-e29b-41d4-a716-446655440000",
            "sku": "IPHONE-15-PRO-256",
            "name": "iPhone 15 Pro 256GB",
            "description": "Latest Apple iPhone with A17 Pro chip",
            "price": 999.99,
            "compareAtPrice": 1099.99,
            "stockQuantity": 50,
            "brand": "Apple",
            "categoryId": "550e8400-e29b-41d4-a716-446655440010",
            "categoryName": "Smartphones",
            "imageUrls": [
                "https://example.com/iphone15-1.jpg",
                "https://example.com/iphone15-2.jpg"
            ],
            "thumbnailUrl": "https://example.com/iphone15-thumb.jpg",
            "rating": 4.8,
            "reviewCount": 1250,
            "status": "ACTIVE",
            "featured": true,
            "inStock": true,
            "createdAt": "2025-12-07T10:00:00",
            "updatedAt": "2025-12-07T10:00:00"
        }
    ],
    "pageable": {
        "pageNumber": 0,
        "pageSize": 10,
        "sort": {
            "sorted": true,
            "unsorted": false
        }
    },
    "totalElements": 150,
    "totalPages": 15,
    "last": false,
    "first": true,
    "numberOfElements": 10
}
```

---

### 4.2 Get Product by ID

**GET** `{{base_url}}/api/products/{id}`

**Example:** `GET http://localhost:8082/api/products/660e8400-e29b-41d4-a716-446655440000`

**Expected Response (200 OK):**
```json
{
    "id": "660e8400-e29b-41d4-a716-446655440000",
    "sku": "IPHONE-15-PRO-256",
    "name": "iPhone 15 Pro 256GB",
    "description": "Latest Apple iPhone with A17 Pro chip",
    "price": 999.99,
    "compareAtPrice": 1099.99,
    "stockQuantity": 50,
    "brand": "Apple",
    "categoryId": "550e8400-e29b-41d4-a716-446655440010",
    "categoryName": "Smartphones",
    "imageUrls": [
        "https://example.com/iphone15-1.jpg",
        "https://example.com/iphone15-2.jpg"
    ],
    "thumbnailUrl": "https://example.com/iphone15-thumb.jpg",
    "rating": 4.8,
    "reviewCount": 1250,
    "status": "ACTIVE",
    "featured": true,
    "inStock": true,
    "createdAt": "2025-12-07T10:00:00",
    "updatedAt": "2025-12-07T10:00:00"
}
```

---

### 4.3 Get Product by SKU

**GET** `{{base_url}}/api/products/sku/{sku}`

**Example:** `GET http://localhost:8082/api/products/sku/IPHONE-15-PRO-256`

Returns the same structure as Get by ID.

---

### 4.4 Get Products by Category (Hierarchical)

**GET** `{{base_url}}/api/products/category/{categoryId}`

**Query Parameters:**
| Parameter | Default | Description |
|-----------|---------|-------------|
| `page` | 0 | Page number (0-indexed) |
| `size` | 20 | Items per page |
| `sort` | `rating,desc` | Sort field and direction |

**Example:** `GET http://localhost:8082/api/products/category/550e8400-e29b-41d4-a716-446655440010?page=0&size=20`

Returns paginated products in the specified category **AND all its subcategories**.

**Important:** This endpoint is hierarchical - querying a parent category returns products from all descendant categories:
- `GET /api/products/category/{electronics-id}` returns products from:
  - Electronics (direct)
  - ├── Mobiles & Accessories
  - │   └── Smartphones (products here)
  - ├── Headphones, Earbuds & Accessories
  - │   ├── Headphones
  - │   │   ├── On-Ear (products here)
  - │   │   └── Over-Ear (products here)
  - └── ...and all other descendants

**Expected Response (200 OK):**
```json
{
    "content": [
        {
            "id": "660e8400-e29b-41d4-a716-446655440000",
            "name": "iPhone 15 Pro 256GB",
            "price": 999.99,
            "categoryName": "Smartphones",
            "rating": 4.8
        },
        {
            "id": "660e8400-e29b-41d4-a716-446655440001",
            "name": "Sony WH-1000XM5",
            "price": 349.99,
            "categoryName": "Over-Ear",
            "rating": 4.7
        }
    ],
    "totalElements": 150,
    "totalPages": 8
}
```

**Note:** The `productCount` in category responses also includes products from all subcategories.

---

### 4.5 Get Products by Brand

**GET** `{{base_url}}/api/products/brand/{brand}`

**Example:** `GET http://localhost:8082/api/products/brand/Apple?page=0&size=20`

Returns paginated products from the specified brand.

---

### 4.6 Search Products

**GET** `{{base_url}}/api/products/search`

Searches product name, description, and brand with optional price and rating filters.

**Query Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `q` | String | Yes | Search query text |
| `minPrice` | BigDecimal | No | Minimum price filter (inclusive) |
| `maxPrice` | BigDecimal | No | Maximum price filter (inclusive) |
| `minRating` | BigDecimal | No | Minimum rating filter (inclusive) |
| `page` | int | No | Page number (default: 0) |
| `size` | int | No | Items per page (default: 20) |

**Examples:**
```
GET /api/products/search?q=iphone&page=0&size=20
GET /api/products/search?q=iphone&maxPrice=100000
GET /api/products/search?q=laptop&minPrice=50000&maxPrice=150000
GET /api/products/search?q=headphones&minRating=4.0
GET /api/products/search?q=phone&minPrice=10000&maxPrice=50000&minRating=4.5
```

**Expected Response (200 OK):**
```json
{
    "content": [
        {
            "id": "660e8400-e29b-41d4-a716-446655440000",
            "sku": "IPHONE-15-PRO-256",
            "name": "iPhone 15 Pro 256GB",
            "description": "Latest Apple iPhone with A17 Pro chip",
            "price": 999.99,
            "brand": "Apple",
            "rating": 4.8,
            "inStock": true
        }
    ],
    "totalElements": 5,
    "totalPages": 1
}
```

**Error Response (400 Bad Request) - Invalid Price Range:**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Minimum price cannot exceed maximum price",
    "errorCode": "INVALID_PRICE_RANGE"
}
```

---

### 4.7 Filter Products by Price Range

**GET** `{{base_url}}/api/products/price-range`

**Query Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `minPrice` | No | Minimum price (inclusive) |
| `maxPrice` | No | Maximum price (inclusive) |

**Example:** `GET http://localhost:8082/api/products/price-range?minPrice=500&maxPrice=1000`

---

### 4.8 Get Featured Products (Diverse & Randomized)

**GET** `{{base_url}}/api/products/featured`

**Query Parameters:**
| Parameter | Default | Description |
|-----------|---------|-------------|
| `page` | 0 | Page number (0-indexed) |
| `size` | 10 | Items per page |

**Example:** `GET http://localhost:8082/api/products/featured?size=10`

Returns featured products with **category diversity and randomization**:
- Products are selected from different categories (round-robin)
- Products within each category are shuffled randomly
- Each request may return products in different order

**Why Diversity?** Without diversity, featured products could all be from one category (e.g., all essential oils). This ensures a varied product mix.

**Expected Response (200 OK):**
```json
{
    "content": [
        {"name": "iPhone 15 Pro", "categoryName": "Smartphones"},
        {"name": "Nike Air Max", "categoryName": "Sports Shoes"},
        {"name": "Sony Headphones", "categoryName": "On-Ear"},
        {"name": "Samsung TV", "categoryName": "Televisions"},
        {"name": "Dyson Vacuum", "categoryName": "Home Appliances"}
    ],
    "totalElements": 500,
    "totalPages": 50
}
```

---

### 4.9 Get All Brands

**GET** `{{base_url}}/api/products/brands`

**Expected Response (200 OK):**
```json
[
    "Apple",
    "Nike",
    "Samsung",
    "Sony"
]
```

---

### 4.10 Get Products by IDs (Batch)

**POST** `{{base_url}}/api/products/batch`

**Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
[
    "660e8400-e29b-41d4-a716-446655440000",
    "660e8400-e29b-41d4-a716-446655440001",
    "660e8400-e29b-41d4-a716-446655440002"
]
```

**Expected Response (200 OK):**
```json
[
    {
        "id": "660e8400-e29b-41d4-a716-446655440000",
        "name": "iPhone 15 Pro 256GB",
        "price": 999.99
    },
    {
        "id": "660e8400-e29b-41d4-a716-446655440001",
        "name": "Samsung Galaxy S24",
        "price": 899.99
    }
]
```

Useful for cart/wishlist where you have product IDs.

---

### 4.11 Create Product (Admin Only)

**POST** `{{base_url}}/api/products`

**Required Role:** `ADMIN`

**Headers:**
```
Content-Type: application/json
Authorization: Bearer {{token}}
```

**Request Body:**
```json
{
    "sku": "IPHONE-15-PRO-256",
    "name": "iPhone 15 Pro 256GB",
    "description": "Latest Apple iPhone with A17 Pro chip, titanium design, and advanced camera system.",
    "price": 999.99,
    "compareAtPrice": 1099.99,
    "stockQuantity": 50,
    "brand": "Apple",
    "categoryId": "550e8400-e29b-41d4-a716-446655440010",
    "imageUrls": [
        "https://example.com/iphone15-1.jpg",
        "https://example.com/iphone15-2.jpg"
    ],
    "thumbnailUrl": "https://example.com/iphone15-thumb.jpg",
    "featured": true
}
```

#### Validation Rules

| Field | Validation |
|-------|------------|
| `sku` | **Required**, 3-50 characters, must be unique |
| `name` | **Required**, 2-200 characters |
| `description` | Max 2000 characters |
| `price` | **Required**, must be > 0.00 |
| `compareAtPrice` | Optional, must be > 0.00 if provided |
| `stockQuantity` | Optional, must be >= 0 (defaults to 0) |
| `brand` | Optional string |
| `categoryId` | Optional UUID of category |
| `imageUrls` | Optional list of URLs |
| `thumbnailUrl` | Optional URL |
| `featured` | Optional boolean (defaults to false) |

**Expected Response (201 Created):**
```json
{
    "id": "660e8400-e29b-41d4-a716-446655440000",
    "sku": "IPHONE-15-PRO-256",
    "name": "iPhone 15 Pro 256GB",
    "description": "Latest Apple iPhone with A17 Pro chip, titanium design, and advanced camera system.",
    "price": 999.99,
    "compareAtPrice": 1099.99,
    "stockQuantity": 50,
    "brand": "Apple",
    "categoryId": "550e8400-e29b-41d4-a716-446655440010",
    "categoryName": "Smartphones",
    "imageUrls": [
        "https://example.com/iphone15-1.jpg",
        "https://example.com/iphone15-2.jpg"
    ],
    "thumbnailUrl": "https://example.com/iphone15-thumb.jpg",
    "rating": 0.0,
    "reviewCount": 0,
    "status": "ACTIVE",
    "featured": true,
    "inStock": true,
    "createdAt": "2025-12-07T10:00:00",
    "updatedAt": "2025-12-07T10:00:00"
}
```

#### Postman Script (Tests tab):
```javascript
if (pm.response.code === 201) {
    var jsonData = pm.response.json();
    pm.environment.set("product_id", jsonData.id);
}
```

---

### 4.12 Update Product (Admin Only)

**PUT** `{{base_url}}/api/products/{id}`

**Required Role:** `ADMIN`

**Headers:**
```
Content-Type: application/json
Authorization: Bearer {{token}}
```

**Request Body:**
```json
{
    "name": "iPhone 15 Pro 256GB - Updated",
    "price": 949.99,
    "stockQuantity": 75,
    "featured": false
}
```

All fields are optional - only provided fields are updated.

**Additional Fields for Update:**

| Field | Description |
|-------|-------------|
| `status` | `ACTIVE`, `OUT_OF_STOCK`, or `DISCONTINUED` |

**Expected Response (200 OK):**
```json
{
    "id": "660e8400-e29b-41d4-a716-446655440000",
    "sku": "IPHONE-15-PRO-256",
    "name": "iPhone 15 Pro 256GB - Updated",
    "price": 949.99,
    "stockQuantity": 75,
    "status": "ACTIVE",
    "featured": false,
    "updatedAt": "2025-12-07T10:30:00"
}
```

---

### 4.13 Update Product Stock (Admin Only)

**PATCH** `{{base_url}}/api/products/{id}/stock?quantity={delta}`

**Required Role:** `ADMIN`

**Headers:**
```
Authorization: Bearer {{token}}
```

Adjusts stock by the given amount (positive or negative).

**Examples:**
- Add 10 to stock: `PATCH /api/products/{id}/stock?quantity=10`
- Remove 5 from stock: `PATCH /api/products/{id}/stock?quantity=-5`

**Expected Response (200 OK):** Empty body

**Error Response (400 Bad Request) - Insufficient Stock:**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Insufficient stock for iPhone 15 Pro 256GB. Available: 5",
    "errorCode": "INSUFFICIENT_STOCK"
}
```

---

## Step 5: Testing Flow

Follow this sequence for complete testing:

### Authentication First
| Step | Action | Endpoint |
|------|--------|----------|
| 1 | Login as admin | POST `/api/auth/login` |
| 2 | Save `accessToken` to `{{token}}` variable | - |

### Categories (Admin Required for Write)
| Step | Action | Endpoint | Auth |
|------|--------|----------|------|
| 3 | Create parent category | POST `/api/categories` | Admin |
| 4 | Save category ID | - | - |
| 5 | Create subcategory | POST `/api/categories` | Admin |
| 6 | Get category tree | GET `/api/categories/tree` | Public |
| 7 | Update category | PUT `/api/categories/{id}` | Admin |

### Products (Admin Required for Write)
| Step | Action | Endpoint | Auth |
|------|--------|----------|------|
| 8 | Create product | POST `/api/products` | Admin |
| 9 | Save product ID | - | - |
| 10 | Get all products | GET `/api/products` | Public |
| 11 | Search products | GET `/api/products/search?q=...` | Public |
| 12 | Filter by price | GET `/api/products/price-range?minPrice=...` | Public |
| 13 | Get by category | GET `/api/products/category/{id}` | Public |
| 14 | Update product | PUT `/api/products/{id}` | Admin |
| 15 | Update stock | PATCH `/api/products/{id}/stock?quantity=-5` | Admin |
| 16 | Get featured | GET `/api/products/featured` | Public |

---

## Step 6: Error Scenarios to Test

### 6.1 Unauthorized Access (No Token)
Try to create a product without authentication.

**Request:** `POST /api/products` without `Authorization` header

**Expected Response (401 Unauthorized):**
```json
{
    "error": "Unauthorized"
}
```

### 6.2 Forbidden Access (Non-Admin User)
Try to create a product as a regular user (not admin).

**Expected Response (403 Forbidden):**
```json
{
    "status": 403,
    "error": "Forbidden",
    "message": "You do not have permission to access this resource",
    "errorCode": "ACCESS_DENIED"
}
```

### 6.3 Duplicate SKU
Create product with existing SKU.

**Expected Response (409 Conflict):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Product with SKU already exists",
    "errorCode": "SKU_EXISTS"
}
```

### 6.2 Duplicate Category Name
Create category with existing name.

**Expected Response (409 Conflict):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Category with this name already exists",
    "errorCode": "CATEGORY_NAME_EXISTS"
}
```

### 6.3 Product Not Found
Get non-existent product.

**Expected Response (404 Not Found):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Product not found",
    "errorCode": "PRODUCT_NOT_FOUND"
}
```

### 6.4 Category Not Found
Get non-existent category.

**Expected Response (404 Not Found):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Category not found",
    "errorCode": "CATEGORY_NOT_FOUND"
}
```

### 6.5 Invalid Price Range
Filter with minPrice > maxPrice.

**Request:** `GET /api/products/price-range?minPrice=1000&maxPrice=100`

**Expected Response (400 Bad Request):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Minimum price cannot exceed maximum price",
    "errorCode": "INVALID_PRICE_RANGE"
}
```

### 6.6 Insufficient Stock
Try to reduce stock below zero.

**Request:** `PATCH /api/products/{id}/stock?quantity=-1000`

**Expected Response (400 Bad Request):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Insufficient stock for iPhone 15 Pro 256GB. Available: 50",
    "errorCode": "INSUFFICIENT_STOCK"
}
```

### 6.7 Invalid Product Data
Create product with invalid price.

**Request:**
```json
{
    "sku": "TEST-001",
    "name": "Test Product",
    "price": -10.00
}
```

**Expected Response (400 Bad Request):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Validation failed",
    "fieldErrors": [
        {
            "field": "price",
            "message": "Price must be greater than 0"
        }
    ]
}
```

### 6.8 Missing Required Fields
Create product without required fields.

**Request:**
```json
{
    "name": "Test Product"
}
```

**Expected Response (400 Bad Request):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Validation failed",
    "fieldErrors": [
        {
            "field": "sku",
            "message": "SKU is required"
        },
        {
            "field": "price",
            "message": "Price is required"
        }
    ]
}
```

---

## H2 Database Console (Development Only)

Access the H2 console to view database tables:

**URL:** http://localhost:8082/h2-console

**Connection Settings:**
- JDBC URL: `jdbc:h2:mem:cartiqdb`
- Username: `sa`
- Password: (leave empty)

**Useful Queries:**
```sql
-- View all categories
SELECT * FROM CATEGORIES;

-- View all products
SELECT * FROM PRODUCTS;

-- View product images
SELECT * FROM PRODUCT_IMAGES;

-- View products with category name
SELECT p.*, c.NAME as CATEGORY_NAME
FROM PRODUCTS p
LEFT JOIN CATEGORIES c ON p.CATEGORY_ID = c.ID;

-- View products by category
SELECT * FROM PRODUCTS WHERE CATEGORY_ID = 'your-category-uuid';

-- Search products
SELECT * FROM PRODUCTS
WHERE LOWER(NAME) LIKE '%iphone%'
   OR LOWER(DESCRIPTION) LIKE '%iphone%';
```

---

## cURL Examples

```bash
# ============================================
# AUTHENTICATION (Required for write operations)
# ============================================

# Login as admin to get token
curl -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@cartiq.com","password":"Admin123!"}'

# Save the accessToken from response as TOKEN variable
# export TOKEN="eyJhbGciOiJIUzI1NiJ9..."

# ============================================
# PUBLIC ENDPOINTS (No auth required)
# ============================================

# Get all categories
curl -X GET http://localhost:8082/api/categories

# Get category tree
curl -X GET http://localhost:8082/api/categories/tree

# Get all products (paginated)
curl -X GET "http://localhost:8082/api/products?page=0&size=10"

# Search products (text only)
curl -X GET "http://localhost:8082/api/products/search?q=iphone"

# Search products with price filter
curl -X GET "http://localhost:8082/api/products/search?q=iphone&maxPrice=100000"

# Search products with price range and rating filter
curl -X GET "http://localhost:8082/api/products/search?q=laptop&minPrice=50000&maxPrice=150000&minRating=4.0"

# Filter by price range
curl -X GET "http://localhost:8082/api/products/price-range?minPrice=100&maxPrice=500"

# Get featured products
curl -X GET "http://localhost:8082/api/products/featured?size=5"

# Get all brands
curl -X GET http://localhost:8082/api/products/brands

# Get products by IDs (batch) - public read operation
curl -X POST http://localhost:8082/api/products/batch \
  -H "Content-Type: application/json" \
  -d '["uuid1", "uuid2", "uuid3"]'

# ============================================
# ADMIN ENDPOINTS (Requires admin token)
# ============================================

# Create category (Admin only)
curl -X POST http://localhost:8082/api/categories \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"Electronics","description":"Electronic devices"}'

# Update category (Admin only)
curl -X PUT http://localhost:8082/api/categories/{category_id} \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"Consumer Electronics","description":"Updated description"}'

# Create product (Admin only)
curl -X POST http://localhost:8082/api/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sku": "IPHONE-15-256",
    "name": "iPhone 15 256GB",
    "description": "Latest Apple iPhone",
    "price": 999.99,
    "stockQuantity": 50,
    "brand": "Apple",
    "featured": true
  }'

# Update product (Admin only)
curl -X PUT http://localhost:8082/api/products/{product_id} \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"price": 899.99, "stockQuantity": 75}'

# Update stock - add 10 (Admin only)
curl -X PATCH "http://localhost:8082/api/products/{product_id}/stock?quantity=10" \
  -H "Authorization: Bearer $TOKEN"

# Update stock - remove 5 (Admin only)
curl -X PATCH "http://localhost:8082/api/products/{product_id}/stock?quantity=-5" \
  -H "Authorization: Bearer $TOKEN"
```

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Connection refused | Ensure app is running on port 8082 |
| 401 Unauthorized | Missing or invalid token - login again |
| 403 Forbidden | User doesn't have ADMIN role |
| 404 Not Found | Check the UUID is valid and entity exists |
| 409 Conflict | SKU or category name already exists |
| 400 Bad Request | Check validation rules for required fields |
| Empty search results | Try broader search terms |
| Products not showing | Check product status is `ACTIVE` |
| Category tree empty | Ensure categories have `active: true` |
| Token expired | Login again to get new token |

---

## Product Status Values

| Status | Description |
|--------|-------------|
| `ACTIVE` | Product is available for purchase |
| `OUT_OF_STOCK` | Stock is 0 (auto-set when stock depleted) |
| `DISCONTINUED` | Product is soft-deleted |

---

## Pagination Notes

All paginated endpoints support:
- `page`: Page number (0-indexed, default: 0)
- `size`: Items per page (default: 20)
- `sort`: Sort field and direction (e.g., `price,asc` or `createdAt,desc`)

**Example:** `GET /api/products?page=0&size=10&sort=price,asc`

---

## Next Steps

After testing Product APIs, you can test:
- **Cart APIs:** (in `cartiq-order` module)
- **Order APIs:** (in `cartiq-order` module)
- **AI Chat APIs:** `POST /api/chat/message`
- **Event Tracking APIs:** `POST /api/events/*`

## Related Documentation

- [User API Testing Guide](USER_API_TESTING.md)
- [Architecture Overview](../ARCHITECTURE.md)
- [Deployment Guide](../DEPLOYMENT.md)
