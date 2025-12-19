# CartIQ Order API Testing Guide

This guide explains how to test the `cartiq-order` module APIs using Postman.

## Prerequisites

1. **Java 17+** installed
2. **Postman** installed
3. **Maven** installed (or use the wrapper)
4. **User account** - All endpoints require authentication
5. **Products in catalog** - You need products to add to cart

---

## Authentication Requirements

All Cart and Order APIs require authentication:

| Operation | Authentication | Role Required |
|-----------|----------------|---------------|
| **Cart** (all operations) | Required | USER |
| **Orders** (user operations) | Required | USER |
| **Orders** (admin operations) | Required | ADMIN |

### Getting a Token

Before testing, you need to login:

```bash
# Login as user
curl -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"Password123!"}'
```

Save the `accessToken` from the response for authenticated requests.

See [User API Testing Guide](USER_API_TESTING.md) for registration and login details.

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

Create a new Postman Collection called **"CartIQ Order APIs"** and add the following requests.

### Environment Setup

Create a Postman environment with these variables:
- `base_url`: `http://localhost:8082`
- `token`: (set after login)
- `admin_token`: (set after admin login)
- `product_id`: (set to a valid product UUID)
- `cart_item_id`: (set after adding to cart)
- `order_id`: (set after creating order)

---

## Step 3: Cart API Endpoints

### 3.1 Get Cart

**GET** `{{base_url}}/api/cart`

**Headers:**
```
Authorization: Bearer {{token}}
```

**Expected Response (200 OK):**
```json
{
    "id": "770e8400-e29b-41d4-a716-446655440000",
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [
        {
            "id": "880e8400-e29b-41d4-a716-446655440000",
            "productId": "660e8400-e29b-41d4-a716-446655440000",
            "productName": "iPhone 15 Pro 256GB",
            "unitPrice": 999.99,
            "quantity": 2,
            "subtotal": 1999.98,
            "thumbnailUrl": "https://example.com/iphone15-thumb.jpg",
            "createdAt": "2025-12-07T10:00:00",
            "updatedAt": "2025-12-07T10:00:00"
        }
    ],
    "totalAmount": 1999.98,
    "totalItems": 2,
    "createdAt": "2025-12-07T10:00:00",
    "updatedAt": "2025-12-07T10:00:00"
}
```

**Note:** Returns an empty cart if user has no items:
```json
{
    "id": "770e8400-e29b-41d4-a716-446655440000",
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [],
    "totalAmount": 0.00,
    "totalItems": 0,
    "createdAt": "2025-12-07T10:00:00",
    "updatedAt": "2025-12-07T10:00:00"
}
```

---

### 3.2 Add to Cart

**POST** `{{base_url}}/api/cart/items`

**Headers:**
```
Content-Type: application/json
Authorization: Bearer {{token}}
```

**Request Body:**
```json
{
    "productId": "660e8400-e29b-41d4-a716-446655440000",
    "quantity": 2
}
```

#### Validation Rules

| Field | Validation |
|-------|------------|
| `productId` | **Required**, must be a valid UUID of existing product |
| `quantity` | **Required**, must be >= 1 |

**Expected Response (201 Created):**
```json
{
    "id": "770e8400-e29b-41d4-a716-446655440000",
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [
        {
            "id": "880e8400-e29b-41d4-a716-446655440000",
            "productId": "660e8400-e29b-41d4-a716-446655440000",
            "productName": "iPhone 15 Pro 256GB",
            "unitPrice": 999.99,
            "quantity": 2,
            "subtotal": 1999.98,
            "thumbnailUrl": "https://example.com/iphone15-thumb.jpg",
            "createdAt": "2025-12-07T10:00:00",
            "updatedAt": "2025-12-07T10:00:00"
        }
    ],
    "totalAmount": 1999.98,
    "totalItems": 2,
    "createdAt": "2025-12-07T10:00:00",
    "updatedAt": "2025-12-07T10:00:00"
}
```

**Note:** If the product is already in cart, the quantity is added to the existing quantity.

#### Postman Script (Tests tab):
```javascript
if (pm.response.code === 201) {
    var jsonData = pm.response.json();
    if (jsonData.items.length > 0) {
        pm.environment.set("cart_item_id", jsonData.items[0].id);
    }
}
```

---

### 3.3 Update Cart Item Quantity

**PUT** `{{base_url}}/api/cart/items/{itemId}`

**Headers:**
```
Content-Type: application/json
Authorization: Bearer {{token}}
```

**Request Body:**
```json
{
    "quantity": 3
}
```

#### Validation Rules

| Field | Validation |
|-------|------------|
| `quantity` | **Required**, must be >= 1 |

**Expected Response (200 OK):**
```json
{
    "id": "770e8400-e29b-41d4-a716-446655440000",
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [
        {
            "id": "880e8400-e29b-41d4-a716-446655440000",
            "productId": "660e8400-e29b-41d4-a716-446655440000",
            "productName": "iPhone 15 Pro 256GB",
            "unitPrice": 999.99,
            "quantity": 3,
            "subtotal": 2999.97,
            "thumbnailUrl": "https://example.com/iphone15-thumb.jpg",
            "createdAt": "2025-12-07T10:00:00",
            "updatedAt": "2025-12-07T10:05:00"
        }
    ],
    "totalAmount": 2999.97,
    "totalItems": 3,
    "createdAt": "2025-12-07T10:00:00",
    "updatedAt": "2025-12-07T10:05:00"
}
```

---

### 3.4 Remove Item from Cart

**DELETE** `{{base_url}}/api/cart/items/{itemId}`

**Headers:**
```
Authorization: Bearer {{token}}
```

**Expected Response (200 OK):**
```json
{
    "id": "770e8400-e29b-41d4-a716-446655440000",
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [],
    "totalAmount": 0.00,
    "totalItems": 0,
    "createdAt": "2025-12-07T10:00:00",
    "updatedAt": "2025-12-07T10:10:00"
}
```

---

### 3.5 Clear Cart

**DELETE** `{{base_url}}/api/cart`

**Headers:**
```
Authorization: Bearer {{token}}
```

Removes all items from the cart.

**Expected Response:** `204 No Content`

---

### 3.6 Get Cart Item Count

**GET** `{{base_url}}/api/cart/count`

**Headers:**
```
Authorization: Bearer {{token}}
```

Returns the total number of items in the cart.

**Expected Response (200 OK):**
```json
{
    "count": 5
}
```

---

## Step 4: Order API Endpoints (User)

### 4.1 Create Order

**POST** `{{base_url}}/api/orders`

Creates an order from the current cart contents.

**Headers:**
```
Content-Type: application/json
Authorization: Bearer {{token}}
```

**Request Body:**
```json
{
    "shippingAddress": "123 Main Street, Apt 4B",
    "shippingCity": "San Francisco",
    "shippingState": "California",
    "shippingZipCode": "94102",
    "shippingCountry": "United States",
    "contactPhone": "+14155551234",
    "notes": "Please leave at the door"
}
```

#### Validation Rules

| Field | Validation |
|-------|------------|
| `shippingAddress` | **Required**, max 500 characters |
| `shippingCity` | **Required**, max 100 characters |
| `shippingState` | Optional, max 100 characters |
| `shippingZipCode` | **Required**, max 20 characters |
| `shippingCountry` | **Required**, max 100 characters |
| `contactPhone` | **Required**, max 20 characters |
| `notes` | Optional, max 1000 characters |

**Expected Response (201 Created):**
```json
{
    "id": "990e8400-e29b-41d4-a716-446655440000",
    "orderNumber": "ORD-20251207-ABC123",
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [
        {
            "id": "aa0e8400-e29b-41d4-a716-446655440000",
            "productId": "660e8400-e29b-41d4-a716-446655440000",
            "productSku": "IPHONE-15-PRO-256",
            "productName": "iPhone 15 Pro 256GB",
            "unitPrice": 999.99,
            "quantity": 2,
            "subtotal": 1999.98,
            "thumbnailUrl": "https://example.com/iphone15-thumb.jpg",
            "createdAt": "2025-12-07T10:15:00"
        }
    ],
    "status": "PENDING",
    "paymentStatus": "PENDING",
    "subtotal": 1999.98,
    "shippingCost": 0.00,
    "tax": 0.00,
    "totalAmount": 1999.98,
    "totalQuantity": 2,
    "shippingAddress": "123 Main Street, Apt 4B",
    "shippingCity": "San Francisco",
    "shippingState": "California",
    "shippingZipCode": "94102",
    "shippingCountry": "United States",
    "contactPhone": "+14155551234",
    "notes": "Please leave at the door",
    "cancellable": true,
    "createdAt": "2025-12-07T10:15:00",
    "updatedAt": "2025-12-07T10:15:00"
}
```

**Note:** After creating an order, the cart is automatically cleared.

#### Postman Script (Tests tab):
```javascript
if (pm.response.code === 201) {
    var jsonData = pm.response.json();
    pm.environment.set("order_id", jsonData.id);
    pm.environment.set("order_number", jsonData.orderNumber);
}
```

---

### 4.2 Get User Orders (Paginated)

**GET** `{{base_url}}/api/orders`

**Headers:**
```
Authorization: Bearer {{token}}
```

**Query Parameters:**
| Parameter | Default | Description |
|-----------|---------|-------------|
| `page` | 0 | Page number (0-indexed) |
| `size` | 10 | Items per page |
| `sort` | `createdAt,desc` | Sort field and direction |
| `status` | (none) | Filter by order status |

**Example:** `GET /api/orders?page=0&size=10&status=PENDING`

**Expected Response (200 OK):**
```json
{
    "content": [
        {
            "id": "990e8400-e29b-41d4-a716-446655440000",
            "orderNumber": "ORD-20251207-ABC123",
            "status": "PENDING",
            "paymentStatus": "PENDING",
            "totalAmount": 1999.98,
            "totalQuantity": 2,
            "createdAt": "2025-12-07T10:15:00"
        },
        {
            "id": "990e8400-e29b-41d4-a716-446655440001",
            "orderNumber": "ORD-20251206-DEF456",
            "status": "DELIVERED",
            "paymentStatus": "PAID",
            "totalAmount": 599.99,
            "totalQuantity": 1,
            "createdAt": "2025-12-06T14:30:00"
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
    "totalElements": 2,
    "totalPages": 1,
    "last": true,
    "first": true,
    "numberOfElements": 2
}
```

---

### 4.3 Get Order by ID

**GET** `{{base_url}}/api/orders/{orderId}`

**Headers:**
```
Authorization: Bearer {{token}}
```

**Example:** `GET /api/orders/990e8400-e29b-41d4-a716-446655440000`

**Expected Response (200 OK):**
```json
{
    "id": "990e8400-e29b-41d4-a716-446655440000",
    "orderNumber": "ORD-20251207-ABC123",
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [
        {
            "id": "aa0e8400-e29b-41d4-a716-446655440000",
            "productId": "660e8400-e29b-41d4-a716-446655440000",
            "productSku": "IPHONE-15-PRO-256",
            "productName": "iPhone 15 Pro 256GB",
            "unitPrice": 999.99,
            "quantity": 2,
            "subtotal": 1999.98,
            "thumbnailUrl": "https://example.com/iphone15-thumb.jpg",
            "createdAt": "2025-12-07T10:15:00"
        }
    ],
    "status": "PENDING",
    "paymentStatus": "PENDING",
    "subtotal": 1999.98,
    "shippingCost": 0.00,
    "tax": 0.00,
    "totalAmount": 1999.98,
    "totalQuantity": 2,
    "shippingAddress": "123 Main Street, Apt 4B",
    "shippingCity": "San Francisco",
    "shippingState": "California",
    "shippingZipCode": "94102",
    "shippingCountry": "United States",
    "contactPhone": "+14155551234",
    "notes": "Please leave at the door",
    "cancellable": true,
    "createdAt": "2025-12-07T10:15:00",
    "updatedAt": "2025-12-07T10:15:00"
}
```

**Note:** Users can only access their own orders.

---

### 4.4 Get Order by Order Number

**GET** `{{base_url}}/api/orders/number/{orderNumber}`

**Headers:**
```
Authorization: Bearer {{token}}
```

**Example:** `GET /api/orders/number/ORD-20251207-ABC123`

Returns the same structure as Get Order by ID.

---

### 4.5 Cancel Order

**POST** `{{base_url}}/api/orders/{orderId}/cancel`

**Headers:**
```
Authorization: Bearer {{token}}
```

Cancels an order. Orders can only be cancelled when:
- Status is `PENDING` or `CONFIRMED`
- `cancellable` is `true`

**Expected Response (200 OK):**
```json
{
    "id": "990e8400-e29b-41d4-a716-446655440000",
    "orderNumber": "ORD-20251207-ABC123",
    "status": "CANCELLED",
    "paymentStatus": "PENDING",
    "cancellable": false,
    ...
}
```

---

## Step 5: Order API Endpoints (Admin Only)

### 5.1 Get All Orders (Admin)

**GET** `{{base_url}}/api/orders/admin/all`

**Required Role:** `ADMIN`

**Headers:**
```
Authorization: Bearer {{admin_token}}
```

**Query Parameters:**
| Parameter | Default | Description |
|-----------|---------|-------------|
| `page` | 0 | Page number (0-indexed) |
| `size` | 20 | Items per page |
| `sort` | `createdAt,desc` | Sort field and direction |
| `status` | (none) | Filter by order status |

**Example:** `GET /api/orders/admin/all?status=PROCESSING&page=0&size=20`

**Expected Response (200 OK):**
```json
{
    "content": [
        {
            "id": "990e8400-e29b-41d4-a716-446655440000",
            "orderNumber": "ORD-20251207-ABC123",
            "status": "PROCESSING",
            "paymentStatus": "PAID",
            "totalAmount": 1999.98,
            "totalQuantity": 2,
            "createdAt": "2025-12-07T10:15:00"
        }
    ],
    "totalElements": 150,
    "totalPages": 8
}
```

---

### 5.2 Get Order by ID (Admin)

**GET** `{{base_url}}/api/orders/admin/{orderId}`

**Required Role:** `ADMIN`

**Headers:**
```
Authorization: Bearer {{admin_token}}
```

Admins can view any order regardless of user.

---

### 5.3 Update Order Status (Admin)

**PATCH** `{{base_url}}/api/orders/admin/{orderId}/status?status={status}`

**Required Role:** `ADMIN`

**Headers:**
```
Authorization: Bearer {{admin_token}}
```

**Query Parameters:**
| Parameter | Required | Values |
|-----------|----------|--------|
| `status` | Yes | `PENDING`, `CONFIRMED`, `PROCESSING`, `SHIPPED`, `DELIVERED`, `CANCELLED`, `REFUNDED` |

**Example:** `PATCH /api/orders/admin/990e8400.../status?status=SHIPPED`

**Expected Response (200 OK):**
```json
{
    "id": "990e8400-e29b-41d4-a716-446655440000",
    "orderNumber": "ORD-20251207-ABC123",
    "status": "SHIPPED",
    "paymentStatus": "PAID",
    "cancellable": false,
    ...
}
```

---

### 5.4 Update Payment Status (Admin)

**PATCH** `{{base_url}}/api/orders/admin/{orderId}/payment?status={status}`

**Required Role:** `ADMIN`

**Headers:**
```
Authorization: Bearer {{admin_token}}
```

**Query Parameters:**
| Parameter | Required | Values |
|-----------|----------|--------|
| `status` | Yes | `PENDING`, `PAID`, `FAILED`, `REFUNDED` |

**Example:** `PATCH /api/orders/admin/990e8400.../payment?status=PAID`

**Expected Response (200 OK):**
```json
{
    "id": "990e8400-e29b-41d4-a716-446655440000",
    "orderNumber": "ORD-20251207-ABC123",
    "status": "CONFIRMED",
    "paymentStatus": "PAID",
    ...
}
```

---

## Step 6: Testing Flow

Follow this sequence for complete testing:

### Setup
| Step | Action | Endpoint |
|------|--------|----------|
| 1 | Login as user | POST `/api/auth/login` |
| 2 | Save `accessToken` to `{{token}}` | - |
| 3 | Get a product ID from catalog | GET `/api/products` |

### Cart Operations
| Step | Action | Endpoint |
|------|--------|----------|
| 4 | Get empty cart | GET `/api/cart` |
| 5 | Add product to cart | POST `/api/cart/items` |
| 6 | Save cart item ID | - |
| 7 | Add another product | POST `/api/cart/items` |
| 8 | Update item quantity | PUT `/api/cart/items/{itemId}` |
| 9 | Get cart count | GET `/api/cart/count` |
| 10 | Remove one item | DELETE `/api/cart/items/{itemId}` |

### Order Operations
| Step | Action | Endpoint |
|------|--------|----------|
| 11 | Create order from cart | POST `/api/orders` |
| 12 | Save order ID | - |
| 13 | Verify cart is empty | GET `/api/cart` |
| 14 | Get order details | GET `/api/orders/{orderId}` |
| 15 | Get order by number | GET `/api/orders/number/{orderNumber}` |
| 16 | List all user orders | GET `/api/orders` |
| 17 | Filter orders by status | GET `/api/orders?status=PENDING` |

### Cancel Order
| Step | Action | Endpoint |
|------|--------|----------|
| 18 | Create another order | POST `/api/orders` |
| 19 | Cancel the order | POST `/api/orders/{orderId}/cancel` |

### Admin Operations
| Step | Action | Endpoint |
|------|--------|----------|
| 20 | Login as admin | POST `/api/auth/login` |
| 21 | Get all orders | GET `/api/orders/admin/all` |
| 22 | Update order status | PATCH `/api/orders/admin/{orderId}/status?status=PROCESSING` |
| 23 | Update payment status | PATCH `/api/orders/admin/{orderId}/payment?status=PAID` |

---

## Step 7: Error Scenarios to Test

### 7.1 Unauthorized Access (No Token)

Try to access cart without authentication.

**Request:** `GET /api/cart` without `Authorization` header

**Expected Response (401 Unauthorized):**
```json
{
    "error": "Unauthorized"
}
```

### 7.2 Add Non-Existent Product to Cart

**Request:**
```json
{
    "productId": "00000000-0000-0000-0000-000000000000",
    "quantity": 1
}
```

**Expected Response (404 Not Found):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Product not found",
    "errorCode": "PRODUCT_NOT_FOUND"
}
```

### 7.3 Invalid Quantity

**Request:**
```json
{
    "productId": "660e8400-e29b-41d4-a716-446655440000",
    "quantity": 0
}
```

**Expected Response (400 Bad Request):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Validation failed",
    "fieldErrors": [
        {
            "field": "quantity",
            "message": "Quantity must be at least 1"
        }
    ]
}
```

### 7.4 Cart Item Not Found

**Request:** `DELETE /api/cart/items/00000000-0000-0000-0000-000000000000`

**Expected Response (404 Not Found):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Cart item not found",
    "errorCode": "CART_ITEM_NOT_FOUND"
}
```

### 7.5 Create Order with Empty Cart

**Request:** `POST /api/orders` with empty cart

**Expected Response (400 Bad Request):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Cannot place order with empty cart",
    "errorCode": "EMPTY_CART"
}
```

### 7.6 Missing Required Shipping Fields

**Request:**
```json
{
    "shippingCity": "San Francisco"
}
```

**Expected Response (400 Bad Request):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Validation failed",
    "fieldErrors": [
        {
            "field": "shippingAddress",
            "message": "Shipping address is required"
        },
        {
            "field": "shippingZipCode",
            "message": "ZIP code is required"
        },
        {
            "field": "shippingCountry",
            "message": "Country is required"
        },
        {
            "field": "contactPhone",
            "message": "Contact phone is required"
        }
    ]
}
```

### 7.7 Order Not Found

**Request:** `GET /api/orders/00000000-0000-0000-0000-000000000000`

**Expected Response (404 Not Found):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Order not found",
    "errorCode": "ORDER_NOT_FOUND"
}
```

### 7.8 Access Another User's Order

**Request:** `GET /api/orders/{another_user_order_id}`

**Expected Response (403 Forbidden):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "You do not have permission to access this resource",
    "errorCode": "ACCESS_DENIED"
}
```

### 7.9 Cancel Non-Cancellable Order

Try to cancel an order that's already shipped.

**Request:** `POST /api/orders/{shipped_order_id}/cancel`

**Expected Response (400 Bad Request):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Order ORD-20251207-ABC123 cannot be cancelled in its current state",
    "errorCode": "ORDER_NOT_CANCELLABLE"
}
```

### 7.10 Insufficient Stock

Try to add more items than available stock.

**Expected Response (400 Bad Request):**
```json
{
    "timestamp": "2025-12-07T10:30:00",
    "error": "Insufficient stock for 'iPhone 15 Pro 256GB'. Available: 5, Requested: 10",
    "errorCode": "INSUFFICIENT_STOCK"
}
```

### 7.11 Admin Endpoint as Regular User

**Request:** `GET /api/orders/admin/all` with regular user token

**Expected Response (403 Forbidden):**
```json
{
    "status": 403,
    "error": "Forbidden",
    "message": "You do not have permission to access this resource",
    "errorCode": "ACCESS_DENIED"
}
```

---

## Status Values

### Order Status

| Status | Description | Cancellable |
|--------|-------------|-------------|
| `PENDING` | Order placed, awaiting confirmation | Yes |
| `CONFIRMED` | Order confirmed | Yes |
| `PROCESSING` | Order being prepared | No |
| `SHIPPED` | Order shipped | No |
| `DELIVERED` | Order delivered | No |
| `CANCELLED` | Order cancelled | No |
| `REFUNDED` | Order refunded | No |

### Payment Status

| Status | Description |
|--------|-------------|
| `PENDING` | Payment not yet received |
| `PAID` | Payment received |
| `FAILED` | Payment failed |
| `REFUNDED` | Payment refunded |

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
-- View all carts
SELECT * FROM CARTS;

-- View cart items
SELECT * FROM CART_ITEMS;

-- View all orders
SELECT * FROM ORDERS;

-- View order items
SELECT * FROM ORDER_ITEMS;

-- View orders by user
SELECT * FROM ORDERS WHERE USER_ID = 'your-user-uuid';

-- View orders by status
SELECT * FROM ORDERS WHERE STATUS = 'PENDING';

-- View order with items
SELECT o.*, oi.*
FROM ORDERS o
JOIN ORDER_ITEMS oi ON o.ID = oi.ORDER_ID
WHERE o.ORDER_NUMBER = 'ORD-20251207-ABC123';
```

---

## cURL Examples

```bash
# ============================================
# AUTHENTICATION
# ============================================

# Login to get token
curl -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"Password123!"}'

# Save the accessToken as TOKEN variable
# export TOKEN="eyJhbGciOiJIUzI1NiJ9..."

# ============================================
# CART ENDPOINTS
# ============================================

# Get cart
curl -X GET http://localhost:8082/api/cart \
  -H "Authorization: Bearer $TOKEN"

# Add to cart
curl -X POST http://localhost:8082/api/cart/items \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"productId":"660e8400-e29b-41d4-a716-446655440000","quantity":2}'

# Update cart item quantity
curl -X PUT http://localhost:8082/api/cart/items/{item_id} \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"quantity":3}'

# Remove item from cart
curl -X DELETE http://localhost:8082/api/cart/items/{item_id} \
  -H "Authorization: Bearer $TOKEN"

# Clear cart
curl -X DELETE http://localhost:8082/api/cart \
  -H "Authorization: Bearer $TOKEN"

# Get cart item count
curl -X GET http://localhost:8082/api/cart/count \
  -H "Authorization: Bearer $TOKEN"

# ============================================
# ORDER ENDPOINTS (User)
# ============================================

# Create order
curl -X POST http://localhost:8082/api/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "shippingAddress": "123 Main Street, Apt 4B",
    "shippingCity": "San Francisco",
    "shippingState": "California",
    "shippingZipCode": "94102",
    "shippingCountry": "United States",
    "contactPhone": "+14155551234",
    "notes": "Please leave at the door"
  }'

# Get user orders
curl -X GET "http://localhost:8082/api/orders?page=0&size=10" \
  -H "Authorization: Bearer $TOKEN"

# Get orders by status
curl -X GET "http://localhost:8082/api/orders?status=PENDING" \
  -H "Authorization: Bearer $TOKEN"

# Get order by ID
curl -X GET http://localhost:8082/api/orders/{order_id} \
  -H "Authorization: Bearer $TOKEN"

# Get order by order number
curl -X GET http://localhost:8082/api/orders/number/ORD-20251207-ABC123 \
  -H "Authorization: Bearer $TOKEN"

# Cancel order
curl -X POST http://localhost:8082/api/orders/{order_id}/cancel \
  -H "Authorization: Bearer $TOKEN"

# ============================================
# ORDER ENDPOINTS (Admin)
# ============================================

# Login as admin
curl -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@cartiq.com","password":"Admin123!"}'

# Save admin token
# export ADMIN_TOKEN="eyJhbGciOiJIUzI1NiJ9..."

# Get all orders (admin)
curl -X GET "http://localhost:8082/api/orders/admin/all?page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Get all orders by status (admin)
curl -X GET "http://localhost:8082/api/orders/admin/all?status=PROCESSING" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Get order by ID (admin)
curl -X GET http://localhost:8082/api/orders/admin/{order_id} \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Update order status (admin)
curl -X PATCH "http://localhost:8082/api/orders/admin/{order_id}/status?status=SHIPPED" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Update payment status (admin)
curl -X PATCH "http://localhost:8082/api/orders/admin/{order_id}/payment?status=PAID" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Connection refused | Ensure app is running on port 8082 |
| 401 Unauthorized | Missing or invalid token - login again |
| 403 Forbidden | User doesn't have required role or accessing another user's resource |
| 404 Not Found | Check the UUID is valid and entity exists |
| 400 Bad Request | Check validation rules for required fields |
| Empty cart error | Add items to cart before creating order |
| Order not cancellable | Order has progressed past cancellable states |
| Token expired | Login again to get new token |

---

## Pagination Notes

All paginated endpoints support:
- `page`: Page number (0-indexed, default: 0)
- `size`: Items per page (default: 10 for user orders, 20 for admin)
- `sort`: Sort field and direction (e.g., `createdAt,desc`)

**Example:** `GET /api/orders?page=0&size=10&sort=totalAmount,desc`

---

## Related Documentation

- [User API Testing Guide](USER_API_TESTING.md)
- [Product API Testing Guide](PRODUCT_API_TESTING.md)
- [Architecture Overview](../ARCHITECTURE.md)
- [Deployment Guide](../DEPLOYMENT.md)
