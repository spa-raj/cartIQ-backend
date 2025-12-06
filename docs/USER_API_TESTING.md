# CartIQ User API Testing Guide

This guide explains how to test the `cartiq-user` module APIs using Postman.

## Prerequisites

1. **Java 17+** installed
2. **Postman** installed
3. **Maven** installed (or use the wrapper)

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

### Environment Variables (Production)

For production deployments, set these environment variables:

| Variable | Description | Required |
|----------|-------------|----------|
| `JWT_SECRET` | Secret key for JWT signing (min 256 bits) | **Yes** |
| `CORS_ALLOWED_ORIGINS` | Comma-separated list of allowed origins | Recommended |
| `SPRING_PROFILES_ACTIVE` | Set to `prod` for production | Recommended |

```bash
# Example production setup
export JWT_SECRET="your-secure-256-bit-secret-key-here-must-be-long-enough"
export CORS_ALLOWED_ORIGINS="https://yourdomain.com,https://app.yourdomain.com"
export SPRING_PROFILES_ACTIVE="prod"
```

### Verify the Application is Running

Open in browser: http://localhost:8082/actuator/health

Expected response:
```json
{"status":"UP"}
```

---

## Step 2: Import Postman Collection

Create a new Postman Collection called **"CartIQ User APIs"** and add the following requests.

### Environment Setup (Optional)

Create a Postman environment with these variables:
- `base_url`: `http://localhost:8082`
- `token`: (will be set after login)

---

## Rate Limiting

The API implements rate limiting to prevent abuse:

| Endpoint Pattern | Rate Limit |
|------------------|------------|
| `/api/auth/**` | 10 requests per minute per IP |
| `/api/chat/**` | 30 requests per minute per IP |

When rate limited, you'll receive:
```json
{
    "error": "Rate limit exceeded. Please try again later."
}
```
**HTTP Status:** `429 Too Many Requests`

---

## Step 3: API Endpoints

### 3.1 Register a New User

**POST** `{{base_url}}/api/auth/register`

**Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
    "email": "john.doe@example.com",
    "password": "Password123!",
    "firstName": "John",
    "lastName": "Doe",
    "phone": "+919876543210"
}
```

#### Password Requirements

Passwords must meet the following criteria:
- **Length:** 8-128 characters
- **Uppercase:** At least one uppercase letter (A-Z)
- **Lowercase:** At least one lowercase letter (a-z)
- **Digit:** At least one number (0-9)
- **Special character:** At least one of: `@$!%*?&`

**Valid examples:** `Password123!`, `MySecure@Pass1`, `Test$1234`
**Invalid examples:** `password123`, `PASSWORD123!`, `Pass1!` (too short)

#### Phone Number Format

Phone numbers must be in **E.164 format** (international format):
- Start with `+` followed by country code
- Only digits after the `+`
- 1-15 digits total (excluding `+`)

**Valid examples:** `+919876543210`, `+14155552671`, `+442071234567`
**Invalid examples:** `9876543210`, `(415) 555-2671`, `+1-415-555-2671`

**Expected Response (200 OK):**
```json
{
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400000,
    "user": {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "email": "john.doe@example.com",
        "firstName": "John",
        "lastName": "Doe",
        "phone": "+919876543210",
        "role": "USER",
        "enabled": true,
        "createdAt": "2025-12-04T15:30:00",
        "updatedAt": "2025-12-04T15:30:00",
        "preference": {
            "id": "660e8400-e29b-41d4-a716-446655440001",
            "minPricePreference": null,
            "maxPricePreference": null,
            "preferredCategories": [],
            "preferredBrands": [],
            "emailNotifications": true,
            "pushNotifications": true,
            "currency": "USD",
            "language": "en"
        }
    }
}
```

**Save the `accessToken`** - you'll need it for authenticated requests.

#### Postman Script (Tests tab):
```javascript
if (pm.response.code === 200) {
    var jsonData = pm.response.json();
    pm.environment.set("token", jsonData.accessToken);
}
```

---

### 3.2 Login

**POST** `{{base_url}}/api/auth/login`

**Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
    "email": "john.doe@example.com",
    "password": "Password123!"
}
```

**Expected Response (200 OK):**
```json
{
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400000,
    "user": {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "email": "john.doe@example.com",
        "firstName": "John",
        "lastName": "Doe",
        "phone": "+919876543210",
        "role": "USER",
        "enabled": true,
        "createdAt": "2025-12-04T15:30:00",
        "updatedAt": "2025-12-04T15:30:00",
        "preference": {
            "id": "660e8400-e29b-41d4-a716-446655440001",
            "minPricePreference": null,
            "maxPricePreference": null,
            "preferredCategories": [],
            "preferredBrands": [],
            "emailNotifications": true,
            "pushNotifications": true,
            "currency": "USD",
            "language": "en"
        }
    }
}
```

#### Postman Script (Tests tab):
```javascript
if (pm.response.code === 200) {
    var jsonData = pm.response.json();
    pm.environment.set("token", jsonData.accessToken);
}
```

---

### 3.3 Logout

**POST** `{{base_url}}/api/auth/logout`

**Headers:**
```
Authorization: Bearer {{token}}
```

**Expected Response (200 OK):**
```json
{
    "message": "Logged out successfully"
}
```

After logout:
- The token is **blacklisted** and cannot be used again
- You must login again to get a new token
- Using the old token will return `401 Unauthorized` with error code `TOKEN_REVOKED`

---

### 3.4 Get Current User Profile

**GET** `{{base_url}}/api/users/me`

**Headers:**
```
Authorization: Bearer {{token}}
```

**Expected Response (200 OK):**
```json
{
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "john.doe@example.com",
    "firstName": "John",
    "lastName": "Doe",
    "phone": "+919876543210",
    "role": "USER",
    "enabled": true,
    "createdAt": "2025-12-04T15:30:00",
    "updatedAt": "2025-12-04T15:30:00",
    "preference": {
        "id": "660e8400-e29b-41d4-a716-446655440001",
        "minPricePreference": null,
        "maxPricePreference": null,
        "preferredCategories": [],
        "preferredBrands": [],
        "emailNotifications": true,
        "pushNotifications": true,
        "currency": "USD",
        "language": "en"
    }
}
```

---

### 3.5 Update User Profile

**PUT** `{{base_url}}/api/users/me`

**Headers:**
```
Content-Type: application/json
Authorization: Bearer {{token}}
```

**Request Body:**
```json
{
    "firstName": "Johnny",
    "lastName": "Doe",
    "phone": "+919876543211"
}
```

#### Validation Rules

| Field | Validation |
|-------|------------|
| `firstName` | 1-100 characters |
| `lastName` | 1-100 characters |
| `phone` | E.164 format (see above) or empty to clear |

**Expected Response (200 OK):**
```json
{
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "john.doe@example.com",
    "firstName": "Johnny",
    "lastName": "Doe",
    "phone": "+919876543211",
    "role": "USER",
    "enabled": true,
    "createdAt": "2025-12-04T15:30:00",
    "updatedAt": "2025-12-04T15:35:00",
    "preference": null
}
```

---

### 3.6 Get User Preferences

**GET** `{{base_url}}/api/users/me/preferences`

**Headers:**
```
Authorization: Bearer {{token}}
```

**Expected Response (200 OK):**
```json
{
    "id": "660e8400-e29b-41d4-a716-446655440001",
    "minPricePreference": null,
    "maxPricePreference": null,
    "preferredCategories": [],
    "preferredBrands": [],
    "emailNotifications": true,
    "pushNotifications": true,
    "currency": "USD",
    "language": "en"
}
```

---

### 3.7 Update User Preferences

**PUT** `{{base_url}}/api/users/me/preferences`

**Headers:**
```
Content-Type: application/json
Authorization: Bearer {{token}}
```

**Request Body:**
```json
{
    "minPricePreference": 100.00,
    "maxPricePreference": 50000.00,
    "preferredCategories": ["Electronics", "Books", "Fashion"],
    "preferredBrands": ["Apple", "Samsung", "Nike"],
    "emailNotifications": true,
    "pushNotifications": false,
    "currency": "INR",
    "language": "en"
}
```

#### Validation Rules

| Field | Validation |
|-------|------------|
| `minPricePreference` | Must be ≥ 0, and ≤ `maxPricePreference` |
| `maxPricePreference` | Must be ≥ 0, and ≥ `minPricePreference` |
| `preferredCategories` | Max 20 items |
| `preferredBrands` | Max 20 items |
| `currency` | 3-letter ISO 4217 code (e.g., `USD`, `EUR`, `INR`, `GBP`) |
| `language` | ISO 639-1 code, optionally with region (e.g., `en`, `en-US`, `fr`, `de-DE`) |

**Valid currency examples:** `USD`, `EUR`, `INR`, `GBP`, `JPY`
**Valid language examples:** `en`, `en-US`, `fr`, `de-DE`, `es-MX`

**Expected Response (200 OK):**
```json
{
    "id": "660e8400-e29b-41d4-a716-446655440001",
    "minPricePreference": 100.00,
    "maxPricePreference": 50000.00,
    "preferredCategories": ["Electronics", "Books", "Fashion"],
    "preferredBrands": ["Apple", "Samsung", "Nike"],
    "emailNotifications": true,
    "pushNotifications": false,
    "currency": "INR",
    "language": "en"
}
```

---

### 3.8 Get User by ID (Admin Only)

**GET** `{{base_url}}/api/users/{userId}`

**Headers:**
```
Authorization: Bearer {{token}}
```

**Required Role:** `ADMIN`

Replace `{userId}` with the actual UUID from registration/login response.

**Example:** `GET http://localhost:8082/api/users/550e8400-e29b-41d4-a716-446655440000`

**For regular users:** Returns `403 Forbidden`
**For admin users:** Returns the user profile

---

### 3.9 Delete User Account

**DELETE** `{{base_url}}/api/users/me`

**Headers:**
```
Authorization: Bearer {{token}}
```

**Expected Response:** `204 No Content`

---

## Step 4: Testing Flow

Follow this sequence for complete testing:

| Step | Action | Endpoint |
|------|--------|----------|
| 1 | Register new user | POST `/api/auth/register` |
| 2 | Copy the `accessToken` from response | - |
| 3 | Get current user profile | GET `/api/users/me` |
| 4 | Update profile | PUT `/api/users/me` |
| 5 | Get preferences | GET `/api/users/me/preferences` |
| 6 | Update preferences | PUT `/api/users/me/preferences` |
| 7 | **Logout** | POST `/api/auth/logout` |
| 8 | Verify old token is rejected | GET `/api/users/me` (should fail) |
| 9 | Login again | POST `/api/auth/login` |
| 10 | Verify profile changes persisted | GET `/api/users/me` |

---

## Step 5: Error Scenarios to Test

### 5.1 Duplicate Email Registration
Register with the same email twice.

**Expected Response (409 Conflict):**
```json
{
    "error": "Registration failed. Please try again or use a different email.",
    "code": "REGISTRATION_FAILED"
}
```

### 5.2 Invalid Login Credentials
Login with wrong password.

**Expected Response (401 Unauthorized):**
```json
{
    "error": "Invalid credentials",
    "code": "INVALID_CREDENTIALS"
}
```

### 5.3 Missing/Invalid Token
Call protected endpoint without token.

**Expected Response (401 Unauthorized):**
```json
{
    "error": "Unauthorized"
}
```

### 5.4 Using Revoked Token (After Logout)
Use a token after calling logout.

**Expected Response (401 Unauthorized):**
```json
{
    "error": "Token has been revoked. Please login again.",
    "code": "TOKEN_REVOKED"
}
```

### 5.5 Invalid Email Format
Register with invalid email.

**Request:**
```json
{
    "email": "invalid-email",
    "password": "Password123!",
    "firstName": "Test",
    "lastName": "User"
}
```

**Expected Response (400 Bad Request):**
```json
{
    "error": "Invalid email format"
}
```

### 5.6 Weak Password
Register with password that doesn't meet requirements.

**Request:**
```json
{
    "email": "test@example.com",
    "password": "password123",
    "firstName": "Test",
    "lastName": "User"
}
```

**Expected Response (400 Bad Request):**
```json
{
    "error": "Password must contain at least one uppercase letter, one lowercase letter, one digit, and one special character (@$!%*?&)"
}
```

### 5.7 Invalid Phone Format
Register with invalid phone number.

**Request:**
```json
{
    "email": "test@example.com",
    "password": "Password123!",
    "firstName": "Test",
    "lastName": "User",
    "phone": "9876543210"
}
```

**Expected Response (400 Bad Request):**
```json
{
    "error": "Invalid phone number format. Use E.164 format"
}
```

### 5.8 Invalid Currency/Language in Preferences
Update preferences with invalid format.

**Request:**
```json
{
    "currency": "INVALID",
    "language": "english"
}
```

**Expected Response (400 Bad Request):**
```json
{
    "status": 400,
    "error": "Validation Failed",
    "message": "One or more fields have invalid values",
    "errorCode": "VALIDATION_ERROR",
    "fieldErrors": [
        {"field": "currency", "message": "Currency must be a valid 3-letter ISO 4217 code"},
        {"field": "language", "message": "Language must be a valid ISO 639-1 code"}
    ]
}
```

### 5.9 Invalid Price Range in Preferences
Update preferences with min price greater than max price.

**Request:**
```json
{
    "minPricePreference": 1000.00,
    "maxPricePreference": 100.00
}
```

**Expected Response (400 Bad Request):**
```json
{
    "status": 400,
    "error": "Bad Request",
    "message": "Minimum price cannot be greater than maximum price",
    "errorCode": "INVALID_PRICE_RANGE"
}
```

### 5.10 Access Admin Endpoint as Regular User

**GET** `/api/users/{userId}` without ADMIN role.

**Expected Response (403 Forbidden):**
```json
{
    "status": 403,
    "error": "Forbidden",
    "message": "You do not have permission to access this resource",
    "errorCode": "ACCESS_DENIED"
}
```

### 5.11 Rate Limit Exceeded
Send more than 10 requests per minute to auth endpoints.

**Expected Response (429 Too Many Requests):**
```json
{
    "error": "Rate limit exceeded. Please try again later."
}
```

---

## H2 Database Console (Development Only)

The H2 console is **only available in development mode** (`dev` profile).

Access the H2 console to view database tables:

**URL:** http://localhost:8082/h2-console

**Connection Settings:**
- JDBC URL: `jdbc:h2:mem:cartiqdb`
- Username: `sa`
- Password: (leave empty)

**Useful Queries:**
```sql
-- View all users
SELECT * FROM USERS;

-- View user preferences
SELECT * FROM USER_PREFERENCES;

-- View preferred categories
SELECT * FROM USER_PREFERRED_CATEGORIES;

-- View preferred brands
SELECT * FROM USER_PREFERRED_BRANDS;
```

**Note:** In production mode (`prod` profile), the H2 console is disabled for security.

---

## Postman Collection Export

You can import this cURL collection directly:

```bash
# Register (note the strong password)
curl -X POST http://localhost:8082/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"Password123!","firstName":"Test","lastName":"User","phone":"+14155552671"}'

# Login
curl -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"Password123!"}'

# Get Profile (replace TOKEN)
curl -X GET http://localhost:8082/api/users/me \
  -H "Authorization: Bearer TOKEN"

# Update Profile (replace TOKEN)
curl -X PUT http://localhost:8082/api/users/me \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer TOKEN" \
  -d '{"firstName":"Updated","lastName":"Name","phone":"+14155552672"}'

# Get Preferences (replace TOKEN)
curl -X GET http://localhost:8082/api/users/me/preferences \
  -H "Authorization: Bearer TOKEN"

# Update Preferences (replace TOKEN)
curl -X PUT http://localhost:8082/api/users/me/preferences \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer TOKEN" \
  -d '{"currency":"INR","language":"en","preferredCategories":["Electronics"]}'

# Logout (replace TOKEN)
curl -X POST http://localhost:8082/api/auth/logout \
  -H "Authorization: Bearer TOKEN"
```

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Connection refused | Ensure app is running on port 8082 |
| 401 Unauthorized | Check token is valid and not expired/revoked |
| 403 Forbidden | Endpoint requires ADMIN role |
| 429 Too Many Requests | Rate limit exceeded, wait 1 minute |
| 500 Internal Server Error | Check application logs |
| Token expired | Login again to get new token |
| Token revoked | You logged out; login again for new token |
| Password validation failed | Ensure password has uppercase, lowercase, digit, and special char |
| Phone validation failed | Use E.164 format: +{country code}{number} |

---

## Security Notes

1. **JWT Secret:** Never commit the JWT secret. Always set `JWT_SECRET` environment variable in production.

2. **CORS:** Configure `CORS_ALLOWED_ORIGINS` to only allow your frontend domains in production.

3. **H2 Console:** Disabled in production. Only use for local development.

4. **Rate Limiting:** Auth endpoints are limited to 10 req/min, chat to 30 req/min per IP.

5. **Token Blacklisting:** Logging out invalidates the token server-side. Old tokens cannot be reused.

6. **Password Policy:** Strong passwords required (8+ chars with complexity requirements).

---

## Creating an Admin User

To test admin-only endpoints like `GET /api/users/{userId}`, you need an admin user.

### Option 1: Environment Variables (First Admin)

**Local Development:**
```bash
ADMIN_EMAIL=admin@cartiq.com ADMIN_PASSWORD=Admin123! mvn spring-boot:run -pl cartiq-app
```

**Production:** See [DEPLOYMENT.md](./DEPLOYMENT.md) for GCP Cloud Run setup with Secret Manager.

### Option 2: Register Endpoint (Additional Admins)

An existing admin can create new admin users via the register endpoint by including `"role": "ADMIN"` in the request body.

**POST** `{{base_url}}/api/auth/register`

**Headers:**
```
Content-Type: application/json
Authorization: Bearer {{admin_token}}
```

**Request Body:**
```json
{
    "email": "newadmin@example.com",
    "password": "SecurePass123!",
    "firstName": "New",
    "lastName": "Admin",
    "phone": "+14155551234",
    "role": "ADMIN"
}
```

**Expected Response (200 OK):**
```json
{
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400000,
    "user": {
        "id": "...",
        "email": "newadmin@example.com",
        "firstName": "New",
        "lastName": "Admin",
        "role": "ADMIN",
        ...
    }
}
```

**Without Admin Auth (403 Forbidden):**
```json
{
    "status": 403,
    "error": "Forbidden",
    "message": "Admin privileges required to perform this action",
    "errorCode": "ADMIN_REQUIRED"
}
```

**Note:** Regular user registration (without `role` field or `"role": "USER"`) remains public and doesn't require authentication.

---

## Next Steps

After testing User APIs, you can test:
- **Event Tracking APIs:** `POST /api/events/*`
- **AI Chat APIs:** `POST /api/chat/message` (requires authentication)

## Related Documentation

- [Deployment Guide](./DEPLOYMENT.md) - GCP Cloud Run deployment and admin setup
