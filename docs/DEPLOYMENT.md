# CartIQ Backend Deployment Guide

This guide covers deploying CartIQ Backend to GCP Cloud Run, including admin user setup and environment configuration.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Environment Variables](#environment-variables)
3. [Admin User Setup](#admin-user-setup)
4. [Local Development](#local-development)
5. [GCP Cloud Run Deployment](#gcp-cloud-run-deployment)
6. [Troubleshooting](#troubleshooting)

---

## Prerequisites

- **Java 17+**
- **Maven 3.8+**
- **Docker** (for containerized deployment)
- **GCP Account** with Cloud Run and Secret Manager enabled
- **gcloud CLI** installed and authenticated

---

## Environment Variables

### Required for Production

| Variable | Description | Example |
|----------|-------------|---------|
| `JWT_SECRET` | Secret key for JWT signing (min 256 bits) | `your-256-bit-secret-key` |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `prod` |

### Optional - Admin Initialization

| Variable | Description | Default |
|----------|-------------|---------|
| `ADMIN_EMAIL` | Email for initial admin user | *(none)* |
| `ADMIN_PASSWORD` | Password for admin (see requirements below) | *(none)* |
| `ADMIN_FIRST_NAME` | Admin's first name | `Admin` |
| `ADMIN_LAST_NAME` | Admin's last name | `User` |

**Admin Password Requirements:**
- Minimum 8 characters
- At least one uppercase letter (A-Z)
- At least one lowercase letter (a-z)
- At least one digit (0-9)
- At least one special character (`@$!%*?&`)

### Optional - CORS & External Services

| Variable | Description | Default |
|----------|-------------|---------|
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed origins | `http://localhost:3000,http://localhost:5173` |
| `CONFLUENT_BOOTSTRAP_SERVERS` | Kafka bootstrap servers | `localhost:9092` |
| `CONFLUENT_API_KEY` | Confluent Cloud API key | *(none)* |
| `CONFLUENT_API_SECRET` | Confluent Cloud API secret | *(none)* |
| `GCP_PROJECT_ID` | Google Cloud project ID | *(none)* |
| `GCP_LOCATION` | Vertex AI location | `us-central1` |

---

## Admin User Setup

CartIQ uses environment variables to create the initial admin user on startup. This approach is:

- **Secure**: Credentials stored in GCP Secret Manager
- **Idempotent**: Safe to run multiple times (won't create duplicates)
- **CI/CD Friendly**: No manual steps required

### How It Works

```
┌─────────────────────────────────────────────────────────┐
│                    Application Start                     │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
              ┌─────────────────────────────┐
              │  ADMIN_EMAIL env var set?   │
              └─────────────────────────────┘
                     │              │
                    Yes             No
                     │              │
                     ▼              ▼
         ┌──────────────────┐   (Skip - no admin created)
         │ ADMIN_PASSWORD   │
         │ set & valid?     │
         └──────────────────┘
              │         │
             Yes        No
              │         │
              ▼         ▼
    ┌──────────────────┐   (Skip with warning)
    │ Admin with this  │
    │ email exists?    │
    └──────────────────┘
         │         │
        Yes        No
         │         │
         ▼         ▼
   (Skip - already   Create admin with
    exists)          hashed password
```

### Validation Rules

The admin initializer validates:

| Check | Requirement |
|-------|-------------|
| Email | Must be non-empty |
| Password | Min 8 chars + uppercase + lowercase + digit + special char (`@$!%*?&`) |
| Uniqueness | No existing user with same email |

---

## Local Development

### Quick Start (No Admin)

```bash
# Start with H2 in-memory database
mvn spring-boot:run -pl cartiq-app
```

### With Admin User

```bash
# Set environment variables and start
ADMIN_EMAIL=admin@cartiq.com \
ADMIN_PASSWORD=Admin123! \
mvn spring-boot:run -pl cartiq-app
```

### Using .env File (Recommended)

Create a `.env` file in the project root:

```bash
# .env (DO NOT COMMIT THIS FILE)
ADMIN_EMAIL=admin@cartiq.com
ADMIN_PASSWORD=Admin123!
JWT_SECRET=dev-only-secret-key-change-in-production-must-be-at-least-256-bits
```

Then run with:

```bash
# Using env-file (requires a tool like dotenv)
source .env && mvn spring-boot:run -pl cartiq-app
```

### Verify Admin Creation

Check the logs for:

```
==============================================
Admin user created successfully!
Email: admin@cartiq.com
Role: ADMIN
==============================================
```

### Test Admin Login

```bash
curl -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@cartiq.com","password":"Admin123!"}'
```

---

## GCP Cloud Run Deployment

### Step 1: Create Secrets in Secret Manager

```bash
# Set your project
gcloud config set project YOUR_PROJECT_ID

# Create JWT secret
echo -n "your-production-jwt-secret-at-least-256-bits-long-for-security" | \
  gcloud secrets create jwt-secret --data-file=-

# Create admin password secret
echo -n "YourSecureAdminPassword123!" | \
  gcloud secrets create admin-password --data-file=-

# (Optional) Create Confluent API secret
echo -n "your-confluent-api-secret" | \
  gcloud secrets create confluent-api-secret --data-file=-
```

### Step 2: Grant Secret Access to Cloud Run

```bash
# Get your project number
PROJECT_NUMBER=$(gcloud projects describe YOUR_PROJECT_ID --format='value(projectNumber)')

# Grant access to secrets
for SECRET in jwt-secret admin-password confluent-api-secret; do
  gcloud secrets add-iam-policy-binding $SECRET \
    --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
done
```

### Step 3: Build and Push Docker Image

```bash
# Build the application
mvn clean package -DskipTests

# Build Docker image
docker build -t gcr.io/YOUR_PROJECT_ID/cartiq-backend .

# Push to Container Registry
docker push gcr.io/YOUR_PROJECT_ID/cartiq-backend
```

### Step 4: Deploy to Cloud Run

```bash
gcloud run deploy cartiq-backend \
  --image gcr.io/YOUR_PROJECT_ID/cartiq-backend \
  --platform managed \
  --region us-central1 \
  --allow-unauthenticated \
  --set-env-vars="\
SPRING_PROFILES_ACTIVE=prod,\
ADMIN_EMAIL=admin@yourcompany.com,\
CORS_ALLOWED_ORIGINS=https://yourfrontend.com,\
CONFLUENT_BOOTSTRAP_SERVERS=pkc-xxxxx.us-central1.gcp.confluent.cloud:9092,\
CONFLUENT_API_KEY=your-api-key,\
GCP_PROJECT_ID=YOUR_PROJECT_ID" \
  --set-secrets="\
JWT_SECRET=jwt-secret:latest,\
ADMIN_PASSWORD=admin-password:latest,\
CONFLUENT_API_SECRET=confluent-api-secret:latest"
```

### Step 5: Verify Deployment

```bash
# Get the service URL
SERVICE_URL=$(gcloud run services describe cartiq-backend --region us-central1 --format='value(status.url)')

# Check health
curl $SERVICE_URL/actuator/health

# Test admin login
curl -X POST $SERVICE_URL/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@yourcompany.com","password":"YourSecureAdminPassword123!"}'
```

---

## Post-Deployment

### After First Deployment

Once the admin is created, you can optionally remove the `ADMIN_EMAIL` and `ADMIN_PASSWORD` environment variables:

```bash
gcloud run services update cartiq-backend \
  --region us-central1 \
  --remove-env-vars=ADMIN_EMAIL \
  --remove-secrets=ADMIN_PASSWORD
```

This is optional since the initializer is idempotent (won't create duplicates).

### Creating Additional Admins

Once you have the first admin user, additional admins can be created via the register endpoint:

```bash
# Login as existing admin to get token
ADMIN_TOKEN=$(curl -s -X POST $SERVICE_URL/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@yourcompany.com","password":"YourSecureAdminPassword123!"}' \
  | jq -r '.accessToken')

# Create new admin user
curl -X POST $SERVICE_URL/api/auth/register \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{
    "email": "newadmin@yourcompany.com",
    "password": "AnotherSecurePass123!",
    "firstName": "New",
    "lastName": "Admin",
    "role": "ADMIN"
  }'
```

**How it works:**
- The `role` field in the register request is optional
- If `role` is not specified or set to `USER`, registration is public (no auth required)
- If `role` is set to `ADMIN`, the caller must be an authenticated admin
- Non-admin users attempting to create an admin will receive `403 Forbidden`

---

## Troubleshooting

### Admin Not Created

| Symptom | Cause | Solution |
|---------|-------|----------|
| No logs about admin | `ADMIN_EMAIL` not set | Set the environment variable |
| "ADMIN_PASSWORD is empty" warning | Password not provided | Set `ADMIN_PASSWORD` |
| "must be at least 8 characters" warning | Password too short | Use longer password |
| "Admin user already exists" | Admin was created before | This is expected on restarts |

### Check Cloud Run Logs

```bash
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=cartiq-backend" \
  --limit 50 \
  --format "table(timestamp, textPayload)"
```

### Verify Secrets Are Mounted

```bash
# List secrets attached to service
gcloud run services describe cartiq-backend \
  --region us-central1 \
  --format='yaml(spec.template.spec.containers[0].env)'
```

### Common Issues

| Issue | Solution |
|-------|----------|
| 401 on login | Check password matches what's in Secret Manager |
| 500 on startup | Check logs for database connection issues |
| Admin role not working | Verify user has `ROLE_ADMIN` in database |

---

## Security Checklist

Before going to production, ensure:

- [ ] `JWT_SECRET` is set to a secure, unique value (not the default)
- [ ] `ADMIN_PASSWORD` meets complexity requirements (8+ chars, uppercase, lowercase, digit, special char)
- [ ] `CORS_ALLOWED_ORIGINS` only includes your frontend domain(s)
- [ ] `SPRING_PROFILES_ACTIVE=prod` (disables H2 console)
- [ ] All secrets are stored in GCP Secret Manager (not plain env vars)
- [ ] Cloud Run service account has minimal required permissions

---

## Quick Reference

### Environment Variable Summary

```bash
# Required
JWT_SECRET=<from-secret-manager>
SPRING_PROFILES_ACTIVE=prod

# Admin Setup (first deployment)
ADMIN_EMAIL=admin@yourcompany.com
ADMIN_PASSWORD=<from-secret-manager>

# CORS
CORS_ALLOWED_ORIGINS=https://yourfrontend.com

# Kafka (Confluent Cloud)
CONFLUENT_BOOTSTRAP_SERVERS=pkc-xxxxx.region.gcp.confluent.cloud:9092
CONFLUENT_API_KEY=your-key
CONFLUENT_API_SECRET=<from-secret-manager>

# GCP
GCP_PROJECT_ID=your-project-id
GCP_LOCATION=us-central1
```

### Useful Commands

```bash
# View logs
gcloud logging read "resource.type=cloud_run_revision" --limit 50

# Update environment variable
gcloud run services update cartiq-backend --set-env-vars=KEY=value

# Update secret
echo -n "new-value" | gcloud secrets versions add SECRET_NAME --data-file=-

# Rollback deployment
gcloud run services update-traffic cartiq-backend --to-revisions=REVISION=100
```
