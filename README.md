# CartIQ Backend

AI-powered e-commerce backend built for the **AI Partner Catalyst Hackathon** (Confluent Challenge).

## Tech Stack

- Java 17 / Spring Boot 4.0
- PostgreSQL (Cloud SQL)
- Apache Kafka (Confluent Cloud)
- Google Cloud Vertex AI (Gemini)
- Redis (Cloud Memorystore)

## Quick Start

### Local Development

```bash
# Copy environment file
cp .env.example .env

# Edit .env with your values
nano .env

# Run the application
mvn spring-boot:run -pl cartiq-app
```

### Build

```bash
mvn clean package -pl cartiq-app -am -DskipTests
```

---

## Deployment Configuration

### GitHub Secrets

Go to: **Settings → Secrets and variables → Actions → Secrets**

| Secret Name | Required | Description | Example |
|-------------|----------|-------------|---------|
| `GCP_PROJECT_ID` | Yes | GCP project ID | `my-project-123456` |
| `WIF_PROVIDER` | Yes | Workload Identity Federation provider | `projects/123456789/locations/global/workloadIdentityPools/github-pool/providers/github-provider` |
| `WIF_SERVICE_ACCOUNT` | Yes | Service account email | `github-actions@my-project-123456.iam.gserviceaccount.com` |
| `CONFLUENT_BOOTSTRAP_SERVERS` | Yes | Kafka bootstrap servers | `pkc-xxxxx.region.provider.confluent.cloud:9092` |
| `VECTOR_SEARCH_INDEX_ENDPOINT` | No | Vector Search endpoint (for RAG) | `projects/123456789/locations/us-central1/indexEndpoints/987654321` |
| `REDIS_HOST` | No | Redis internal IP (for RAG) | `10.0.0.1` |

### GitHub Variables

Go to: **Settings → Secrets and variables → Actions → Variables**

| Variable Name | Required | Default | Description | Example |
|---------------|----------|---------|-------------|---------|
| `GCP_REGION` | No | `us-central1` | GCP region | `us-central1` |
| `CLOUD_SQL_INSTANCE_NAME` | No | `cartiq-db` | Cloud SQL instance name (not connection name) | `my-database-instance` |
| `DB_NAME` | No | `cartiq` | Database name inside the instance | `myapp` |
| `DB_USER` | No | `cartiq-user` | Database username | `db-user` |
| `VECTOR_SEARCH_DEPLOYED_INDEX_ID` | No | - | Deployed index ID | `1234567890123456789` |
| `VECTOR_SEARCH_API_ENDPOINT` | No | - | Vector Search API endpoint | `us-central1-aiplatform.googleapis.com` |
| `REDIS_PORT` | No | - | Redis port | `6379` |
| `RAG_ENABLED` | No | - | Enable RAG features | `true` |
| `ADMIN_EMAIL` | No | - | Initial admin email | `admin@example.com` |
| `CORS_ALLOWED_ORIGINS` | Yes | - | Frontend URLs (comma-separated) | `https://my-frontend.web.app` |

### GCP Secret Manager

Create these secrets in GCP (referenced by Cloud Run at runtime):

```bash
# JWT signing key (min 256 bits)
echo -n "your-secure-jwt-secret-at-least-256-bits-long" | \
  gcloud secrets create jwt-secret --data-file=-

# Database password
echo -n "your-db-password" | \
  gcloud secrets create db-password --data-file=-

# Confluent Cloud API key
echo -n "your-confluent-api-key" | \
  gcloud secrets create confluent-api-key --data-file=-

# Confluent Cloud API secret
echo -n "your-confluent-api-secret" | \
  gcloud secrets create confluent-api-secret --data-file=-

# Admin password (min 8 chars, uppercase, lowercase, digit, special char)
echo -n "AdminPass123!" | \
  gcloud secrets create admin-password --data-file=-
```

| GCP Secret Name | Description |
|-----------------|-------------|
| `jwt-secret` | JWT signing key |
| `db-password` | PostgreSQL password |
| `confluent-api-key` | Confluent Cloud API key |
| `confluent-api-secret` | Confluent Cloud API secret |
| `admin-password` | Initial admin user password |

---

## Workload Identity Federation Setup

```bash
# Set variables - replace with your values
export PROJECT_ID="your-gcp-project-id"
export GITHUB_ORG="your-github-username"
export REPO_NAME="your-repo-name"

# Enable APIs
gcloud services enable iamcredentials.googleapis.com sts.googleapis.com

# Create Workload Identity Pool
gcloud iam workload-identity-pools create "github-pool" \
    --project="$PROJECT_ID" \
    --location="global" \
    --display-name="GitHub Actions Pool"

# Create Provider
gcloud iam workload-identity-pools providers create-oidc "github-provider" \
    --project="$PROJECT_ID" \
    --location="global" \
    --workload-identity-pool="github-pool" \
    --display-name="GitHub Provider" \
    --attribute-mapping="google.subject=assertion.sub,attribute.actor=assertion.actor,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner" \
    --attribute-condition="assertion.repository_owner == '${GITHUB_ORG}'" \
    --issuer-uri="https://token.actions.githubusercontent.com"

# Create Service Account
gcloud iam service-accounts create "github-actions" \
    --project="$PROJECT_ID" \
    --display-name="GitHub Actions Service Account"

# Grant roles
SA_EMAIL="github-actions@${PROJECT_ID}.iam.gserviceaccount.com"
for ROLE in "roles/run.admin" "roles/storage.admin" "roles/iam.serviceAccountUser" "roles/secretmanager.secretAccessor" "roles/cloudsql.client"; do
    gcloud projects add-iam-policy-binding $PROJECT_ID \
        --member="serviceAccount:${SA_EMAIL}" \
        --role="$ROLE"
done

# Allow GitHub to impersonate service account
PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format="value(projectNumber)")
gcloud iam service-accounts add-iam-policy-binding $SA_EMAIL \
    --project="$PROJECT_ID" \
    --role="roles/iam.workloadIdentityUser" \
    --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github-pool/attribute.repository/${GITHUB_ORG}/${REPO_NAME}"

# Get values for GitHub secrets
echo "WIF_PROVIDER: projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github-pool/providers/github-provider"
echo "WIF_SERVICE_ACCOUNT: ${SA_EMAIL}"
```

---

## Project Structure

```
cartiq-backend/
├── cartiq-common/     # Shared DTOs, exceptions, utilities
├── cartiq-user/       # User auth, profiles, JWT
├── cartiq-product/    # Product catalog, categories
├── cartiq-order/      # Shopping cart, orders
├── cartiq-kafka/      # Kafka producers/consumers
├── cartiq-ai/         # Gemini integration, chat API
├── cartiq-rag/        # RAG pipeline (future)
├── cartiq-seeder/     # Database seeder utility
└── cartiq-app/        # Main application assembly
```

---

## API Endpoints

| Module | Endpoints |
|--------|-----------|
| User | `/api/auth/**`, `/api/users/**` |
| Product | `/api/products/**`, `/api/categories/**` |
| Order | `/api/cart/**`, `/api/orders/**` |
| Kafka | `/api/events/**` |
| AI | `/api/chat/**` |

---

## Documentation

- [Architecture](./docs/ARCHITECTURE.md)
- [Deployment Guide](./docs/DEPLOYMENT.md)
- [GCP Setup](./docs/GCP_SETUP.md)
- [User API Testing](./docs/USER_API_TESTING.md)
- [Product API Testing](./docs/PRODUCT_API_TESTING.md)
- [Order API Testing](./docs/ORDER_API_TESTING.md)

---

## Troubleshooting Deployment

### Container fails to start

If you see `The user-provided container failed to start and listen on the port`:

1. **Check GCP Secrets exist:**
   ```bash
   gcloud secrets list --project=YOUR_PROJECT_ID
   ```
   Required secrets: `jwt-secret`, `db-password`, `confluent-api-key`, `confluent-api-secret`, `admin-password`

2. **Grant Secret Manager access to Cloud Run:**
   ```bash
   PROJECT_NUMBER=$(gcloud projects describe YOUR_PROJECT_ID --format='value(projectNumber)')
   gcloud secrets add-iam-policy-binding jwt-secret \
     --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
     --role="roles/secretmanager.secretAccessor"
   # Repeat for other secrets...
   ```

3. **Verify Cloud SQL instance exists:**
   ```bash
   gcloud sql instances list --project=YOUR_PROJECT_ID
   ```

4. **Check Cloud Run logs:**
   ```bash
   gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=cartiq-backend" \
     --limit=50 --project=YOUR_PROJECT_ID
   ```

### Permission denied on logs

Grant yourself logging access:
```bash
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="user:your-email@example.com" \
  --role="roles/logging.viewer"
```

---

## License

MIT
