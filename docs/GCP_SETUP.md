# GCP Infrastructure Setup Guide

This guide walks through setting up the GCP infrastructure for CartIQ's production-grade RAG system.

**Estimated Time:** 45-60 minutes (including index deployment wait time)

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Enable Required APIs](#1-enable-required-apis)
3. [Create Service Account](#2-create-service-account)
4. [Create Secrets in Secret Manager](#3-create-secrets-in-secret-manager)
5. [Set Up VPC Network](#4-set-up-vpc-network-required-for-vector-search--redis)
6. [Create Cloud Memorystore Redis Instance](#5-create-cloud-memorystore-redis-instance)
7. [Create Cloud SQL PostgreSQL Instance](#6-create-cloud-sql-postgresql-instance)
8. [Create Vertex AI Vector Search Index](#7-create-vertex-ai-vector-search-index)
9. [Create GCS Bucket for Batch Indexing](#8-create-gcs-bucket-for-batch-indexing)
10. [Verify Setup](#9-verify-setup)
11. [Environment Variables Summary](#10-environment-variables-summary)
12. [Cost Estimates](#11-cost-estimates)
13. [Cleanup (After Hackathon)](#12-cleanup-after-hackathon)
14. [Troubleshooting](#troubleshooting)
15. [Quick Reference](#quick-reference)

---

## Prerequisites

- GCP Account with billing enabled
- `gcloud` CLI installed and authenticated
- Project created (or create one below)

```bash
# Verify gcloud is authenticated
gcloud auth list

# Set your project (create one if needed)
export PROJECT_ID="your-project-id"
gcloud config set project $PROJECT_ID

# Set default region
export REGION="us-central1"
gcloud config set compute/region $REGION
```

---

## 1. Enable Required APIs

```bash
# Enable all required APIs
gcloud services enable \
  aiplatform.googleapis.com \
  redis.googleapis.com \
  sqladmin.googleapis.com \
  compute.googleapis.com \
  servicenetworking.googleapis.com \
  cloudresourcemanager.googleapis.com \
  secretmanager.googleapis.com \
  vpcaccess.googleapis.com \
  run.googleapis.com
```

**Verify APIs are enabled:**
```bash
gcloud services list --enabled | grep -E "(aiplatform|redis|compute|secretmanager|vpcaccess|run)"
```

---

## 2. Create Service Account

```bash
# Create service account for CartIQ backend
gcloud iam service-accounts create cartiq-backend \
  --display-name="CartIQ Backend Service Account"

# Grant necessary roles
export SA_EMAIL="cartiq-backend@${PROJECT_ID}.iam.gserviceaccount.com"

# Vertex AI permissions (embeddings, vector search, ranking)
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${SA_EMAIL}" \
  --role="roles/aiplatform.user"

# Storage permissions (for vector search index)
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${SA_EMAIL}" \
  --role="roles/storage.objectViewer"

# Generate and download key
gcloud iam service-accounts keys create ./cartiq-sa-key.json \
  --iam-account=$SA_EMAIL

echo "Service account key saved to: ./cartiq-sa-key.json"
echo "Set GOOGLE_APPLICATION_CREDENTIALS to this file path"
```

---

## 3. Create Secrets in Secret Manager

Store sensitive credentials in GCP Secret Manager instead of environment variables.

### Secrets to Create

| Secret Name | Variable | Description |
|-------------|----------|-------------|
| `jwt-secret` | JWT_SECRET | JWT signing key (min 256 bits) |
| `db-password` | SPRING_DATASOURCE_PASSWORD | PostgreSQL database password |
| `admin-password` | ADMIN_PASSWORD | Initial admin user password |
| `internal-api-key` | INTERNAL_API_KEY | API key for internal/batch endpoints |
| `confluent-api-key` | CONFLUENT_API_KEY | Kafka API key |
| `confluent-api-secret` | CONFLUENT_API_SECRET | Kafka API secret |
| `confluent-sr-api-key` | CONFLUENT_SR_API_KEY | Schema Registry API key |
| `confluent-sr-api-secret` | CONFLUENT_SR_API_SECRET | Schema Registry API secret |

### Create All Secrets

```bash
# 1. JWT Secret (generate new secure key - 256 bits)
JWT_SECRET=$(openssl rand -base64 32)
echo -n "$JWT_SECRET" | gcloud secrets create jwt-secret \
  --data-file=- \
  --replication-policy="automatic"
echo "Created jwt-secret"

# 2. Database Password (will be created in Cloud SQL section, or create now)
DB_PASSWORD=$(openssl rand -base64 16)
echo -n "$DB_PASSWORD" | gcloud secrets create db-password \
  --data-file=- \
  --replication-policy="automatic"
echo "Created db-password: $DB_PASSWORD"

# 3. Admin Password (set a secure password - min 8 chars, uppercase, lowercase, digit, special char)
read -sp "Enter Admin Password: " ADMIN_PASS && echo
echo -n "$ADMIN_PASS" | gcloud secrets create admin-password \
  --data-file=- \
  --replication-policy="automatic"

# 4. Internal API Key (for batch indexing endpoints)
INTERNAL_KEY=$(openssl rand -base64 32)
echo -n "$INTERNAL_KEY" | gcloud secrets create internal-api-key \
  --data-file=- \
  --replication-policy="automatic"
echo "Created internal-api-key"

# 5. Confluent API Key (from your Confluent Cloud console)
read -p "Enter Confluent API Key: " CONFLUENT_KEY
echo -n "$CONFLUENT_KEY" | gcloud secrets create confluent-api-key \
  --data-file=- \
  --replication-policy="automatic"

# 6. Confluent API Secret (from your Confluent Cloud console)
read -sp "Enter Confluent API Secret: " CONFLUENT_SECRET && echo
echo -n "$CONFLUENT_SECRET" | gcloud secrets create confluent-api-secret \
  --data-file=- \
  --replication-policy="automatic"

# 7. Schema Registry API Key (from Confluent Cloud > Schema Registry)
read -p "Enter Schema Registry API Key: " SR_KEY
echo -n "$SR_KEY" | gcloud secrets create confluent-sr-api-key \
  --data-file=- \
  --replication-policy="automatic"

# 8. Schema Registry API Secret
read -sp "Enter Schema Registry API Secret: " SR_SECRET && echo
echo -n "$SR_SECRET" | gcloud secrets create confluent-sr-api-secret \
  --data-file=- \
  --replication-policy="automatic"

# Verify all secrets were created
gcloud secrets list
```

### Grant Service Account Access

```bash
# Grant access to all secrets
for SECRET in jwt-secret db-password admin-password internal-api-key \
              confluent-api-key confluent-api-secret \
              confluent-sr-api-key confluent-sr-api-secret; do
  gcloud secrets add-iam-policy-binding $SECRET \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="roles/secretmanager.secretAccessor"
done

echo "Service account granted access to all secrets"
```

### Cloud Run Deployment

```bash
# Deploy with secrets injected as environment variables
gcloud run deploy cartiq-backend \
  --image=gcr.io/${PROJECT_ID}/cartiq-backend \
  --set-secrets=JWT_SECRET=jwt-secret:latest \
  --set-secrets=CONFLUENT_API_KEY=confluent-api-key:latest \
  --set-secrets=CONFLUENT_API_SECRET=confluent-api-secret:latest \
  --set-secrets=ADMIN_PASSWORD=admin-password:latest \
  --region=$REGION
```

### Access Secrets for Local Development

```bash
# Copy secrets to local .env file
echo "JWT_SECRET=$(gcloud secrets versions access latest --secret=jwt-secret)"
echo "CONFLUENT_API_KEY=$(gcloud secrets versions access latest --secret=confluent-api-key)"
echo "CONFLUENT_API_SECRET=$(gcloud secrets versions access latest --secret=confluent-api-secret)"
echo "ADMIN_PASSWORD=$(gcloud secrets versions access latest --secret=admin-password)"
```

### Update Existing Secrets

To update a secret, add a new version (the latest version is automatically used):

```bash
# Update JWT Secret (generate new random key)
openssl rand -base64 32 | tr -d '\n' | gcloud secrets versions add jwt-secret --data-file=-

# Update Confluent API Key
echo -n "new-api-key" | gcloud secrets versions add confluent-api-key --data-file=-

# Update Confluent API Secret
echo -n "new-api-secret" | gcloud secrets versions add confluent-api-secret --data-file=-

# Update Admin Password
echo -n "new-password" | gcloud secrets versions add admin-password --data-file=-
```

**Interactive update (hides input):**
```bash
read -sp "Enter new value: " NEW_VALUE && echo
echo -n "$NEW_VALUE" | gcloud secrets versions add SECRET_NAME --data-file=-
```

**Verify update:**
```bash
# List all versions
gcloud secrets versions list jwt-secret

# View latest value
gcloud secrets versions access latest --secret=jwt-secret
```

**Clean up old versions (optional):**
```bash
# Disable old version (can be re-enabled later)
gcloud secrets versions disable jwt-secret --version=1

# Permanently destroy old version (cannot be recovered!)
gcloud secrets versions destroy jwt-secret --version=1
```

> **Note:** Cloud Run automatically uses the latest version on next deployment or container restart.

---

## 4. Set Up VPC Network (Required for Vector Search & Redis)

### Option A: Use Default Network (Simpler)

```bash
# Check if default network exists
gcloud compute networks list

# If default exists, skip to configuring private services access
```

### Option B: Create Custom VPC (Recommended for Production)

```bash
# Create VPC network
gcloud compute networks create cartiq-vpc \
  --subnet-mode=auto \
  --bgp-routing-mode=regional

# Create firewall rule for internal communication
gcloud compute firewall-rules create cartiq-allow-internal \
  --network=cartiq-vpc \
  --allow=tcp,udp,icmp \
  --source-ranges=10.128.0.0/9
```

### Configure Private Services Access (Required for Redis)

```bash
# Allocate IP range for private services
gcloud compute addresses create google-managed-services-default \
  --global \
  --purpose=VPC_PEERING \
  --prefix-length=16 \
  --network=default

# Create private connection
gcloud services vpc-peerings connect \
  --service=servicenetworking.googleapis.com \
  --ranges=google-managed-services-default \
  --network=default
```

---

## 5. Create Cloud Memorystore Redis Instance

### Step 5.1: Create Redis Instance

```bash
# Create Redis instance (Basic tier, 1GB, Redis 7.2)
gcloud redis instances create cartiq-cache \
  --size=1 \
  --region=$REGION \
  --redis-version=redis_7_2 \
  --network=default \
  --tier=basic

# This takes 3-5 minutes...
echo "Creating Redis instance... (wait 3-5 minutes)"
```

### Step 5.2: Configure Eviction Policy

Set the eviction policy to `volatile-lru` for optimal cache behavior with TTL-based keys:

```bash
# Update Redis with recommended eviction policy
gcloud redis instances update cartiq-cache \
  --region=$REGION \
  --update-redis-config=maxmemory-policy=volatile-lru

echo "Eviction policy set to volatile-lru"
```

**Eviction Policy Options:**

| Policy | Description | Use Case |
|--------|-------------|----------|
| `volatile-lru` | Evict LRU keys among those with TTL | ✅ **Recommended** - Best for user profile caching |
| `allkeys-lru` | Evict LRU keys from all keys | Good general purpose |
| `volatile-ttl` | Evict keys with shortest TTL first | When TTL reflects priority |
| `noeviction` | Return error when memory full | When data loss is unacceptable |

### Step 5.3: Get Redis Connection Details

```bash
# Get Redis host IP
gcloud redis instances describe cartiq-cache --region=$REGION --format="value(host)"

# Get Redis port (default: 6379)
gcloud redis instances describe cartiq-cache --region=$REGION --format="value(port)"

# Full connection info (verify config)
gcloud redis instances describe cartiq-cache --region=$REGION
```

**Save these values:**
```bash
export REDIS_HOST=$(gcloud redis instances describe cartiq-cache --region=$REGION --format="value(host)")
export REDIS_PORT=$(gcloud redis instances describe cartiq-cache --region=$REGION --format="value(port)")

echo "REDIS_HOST=$REDIS_HOST"
echo "REDIS_PORT=$REDIS_PORT"
```

### Step 5.4: Verify Redis Configuration

```bash
# Check Redis instance details
gcloud redis instances describe cartiq-cache \
  --region=$REGION \
  --format="table(name,host,port,memorySizeGb,redisVersion,redisConfigs)"
```

Expected output:
```
NAME          HOST           PORT  MEMORY_SIZE_GB  REDIS_VERSION  REDIS_CONFIGS
cartiq-cache  10.143.43.75   6379  1               REDIS_7_2      {'maxmemory-policy': 'volatile-lru'}
```

> **Note:** Redis is deployed with a private IP. Cloud Run needs a VPC connector to access it (see Section 5.5).

### Step 5.5: Create VPC Connector for Cloud Run

Cloud Run is serverless and runs outside your VPC by default. To connect to the private Redis instance, you need a VPC Access Connector.

```bash
# Enable the VPC Access API (if not already enabled)
gcloud services enable vpcaccess.googleapis.com

# Create VPC connector
gcloud compute networks vpc-access connectors create cartiq-connector \
  --region=$REGION \
  --network=default \
  --range=10.8.0.0/28 \
  --min-instances=2 \
  --max-instances=3 \
  --machine-type=e2-micro

# This takes 2-3 minutes...
echo "Creating VPC connector... (wait 2-3 minutes)"
```

**Verify VPC Connector:**
```bash
# Check connector status (should be READY)
gcloud compute networks vpc-access connectors describe cartiq-connector \
  --region=$REGION \
  --format="table(name,state,network,ipCidrRange)"
```

Expected output:
```
NAME              STATE  NETWORK  IP_CIDR_RANGE
cartiq-connector  READY  default  10.8.0.0/28
```

### Step 5.6: Deploy Cloud Run with VPC Connector

When deploying to Cloud Run, include the VPC connector flags:

```bash
gcloud run deploy cartiq-backend \
  --image=gcr.io/${PROJECT_ID}/cartiq-backend \
  --region=$REGION \
  --vpc-connector=cartiq-connector \
  --vpc-egress=private-ranges-only \
  --set-env-vars="REDIS_HOST=${REDIS_HOST}" \
  --set-env-vars="REDIS_PORT=6379" \
  # ... other flags
```

**VPC Egress Options:**

| Option | Description |
|--------|-------------|
| `private-ranges-only` | ✅ **Recommended** - Only private IP traffic uses VPC |
| `all-traffic` | All outbound traffic routes through VPC |

### Step 5.7: Store Redis Host in GitHub Secrets

For CI/CD deployment, add the Redis host to GitHub secrets:

```bash
# Get the Redis host IP
REDIS_HOST=$(gcloud redis instances describe cartiq-cache --region=$REGION --format="value(host)")
echo "Add this to GitHub Secrets as REDIS_HOST: $REDIS_HOST"
```

In GitHub → Repository Settings → Secrets and variables → Actions:
- Add secret: `REDIS_HOST` = `10.x.x.x` (your Redis private IP)
- Add variable: `REDIS_PORT` = `6379`

---

## 6. Create Cloud SQL PostgreSQL Instance

Cloud SQL provides persistent storage for products, users, carts, and orders.

### Step 6.1: Create PostgreSQL Instance

```bash
# Create Cloud SQL PostgreSQL instance (smallest tier for hackathon)
gcloud sql instances create cartiq-db \
  --database-version=POSTGRES_15 \
  --tier=db-f1-micro \
  --region=$REGION \
  --storage-size=10GB \
  --storage-type=SSD \
  --availability-type=zonal \
  --authorized-networks=0.0.0.0/0

# This takes 5-10 minutes...
echo "Creating Cloud SQL instance... (wait 5-10 minutes)"
```

> **Note:** `--authorized-networks=0.0.0.0/0` allows connections from anywhere (for development). For production, restrict to specific IPs or use Cloud SQL Proxy.

### Step 6.2: Create Database and User

```bash
# Create the database
gcloud sql databases create cartiq --instance=cartiq-db

# Generate a secure password
DB_PASSWORD=$(openssl rand -base64 16)
echo "Generated DB_PASSWORD: $DB_PASSWORD"

# Create database user
gcloud sql users create cartiq-user \
  --instance=cartiq-db \
  --password=$DB_PASSWORD

echo "Database user 'cartiq-user' created"
```

### Step 6.3: Store Database Password in Secret Manager

```bash
# Store password in Secret Manager
echo -n "$DB_PASSWORD" | gcloud secrets create db-password \
  --data-file=- \
  --replication-policy="automatic"

# Grant service account access
gcloud secrets add-iam-policy-binding db-password \
  --member="serviceAccount:${SA_EMAIL}" \
  --role="roles/secretmanager.secretAccessor"

echo "Database password stored in Secret Manager"
```

### Step 6.4: Get Connection Details

```bash
# Get the public IP address
export DB_HOST=$(gcloud sql instances describe cartiq-db --format="value(ipAddresses[0].ipAddress)")
echo "DB_HOST=$DB_HOST"

# Connection details summary
echo ""
echo "=== Cloud SQL Connection Details ==="
echo "Host: $DB_HOST"
echo "Port: 5432"
echo "Database: cartiq"
echo "Username: cartiq-user"
echo "Password: (stored in Secret Manager as 'db-password')"
echo ""
echo "JDBC URL: jdbc:postgresql://${DB_HOST}:5432/cartiq"
```

### Step 6.5: Update Application Configuration

**For Local Development** - Add these to your `.env` file:

```bash
# Database Configuration
SPRING_DATASOURCE_URL=jdbc:postgresql://${DB_HOST}:5432/cartiq
SPRING_DATASOURCE_USERNAME=cartiq-user
SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.PostgreSQLDialect
```

### Step 6.6: Secure Database Access

**Option A: Restrict to Your IP (for development)**
```bash
# Get your current public IP
MY_IP=$(curl -s ifconfig.me)
echo "Your IP: $MY_IP"

# Update Cloud SQL to only allow your IP (removes 0.0.0.0/0)
gcloud sql instances patch cartiq-db \
  --authorized-networks="${MY_IP}/32"

echo "Database now only accepts connections from: $MY_IP"
```

> ⚠️ If your IP changes, run this again with the new IP.

**Option B: Keep open for hackathon (acceptable for short-term)**
- Password protection is your security layer
- Delete the instance after the hackathon demo

### Step 6.7: Cloud Run Deployment (Secure - No Public IP Needed)

Cloud Run has **built-in Cloud SQL connection** using Google's internal network - more secure than public IP:

```bash
# Store the Cloud SQL connection name
export SQL_CONNECTION="${PROJECT_ID}:${REGION}:cartiq-db"
echo "SQL_CONNECTION=$SQL_CONNECTION"
```

**Add PostgreSQL Socket Factory to your pom.xml:**
```xml
<dependency>
    <groupId>com.google.cloud.sql</groupId>
    <artifactId>postgres-socket-factory</artifactId>
    <version>1.15.0</version>
</dependency>
```

**Deploy to Cloud Run with Cloud SQL connection:**
```bash
gcloud run deploy cartiq-backend \
  --image=gcr.io/${PROJECT_ID}/cartiq-backend \
  --region=$REGION \
  --add-cloudsql-instances=${SQL_CONNECTION} \
  --set-env-vars="SPRING_DATASOURCE_URL=jdbc:postgresql:///cartiq?cloudSqlInstance=${SQL_CONNECTION}&socketFactory=com.google.cloud.sql.postgres.SocketFactory" \
  --set-env-vars="SPRING_DATASOURCE_USERNAME=cartiq-user" \
  --set-secrets="SPRING_DATASOURCE_PASSWORD=db-password:latest" \
  --set-secrets="JWT_SECRET=jwt-secret:latest" \
  --set-secrets="CONFLUENT_API_KEY=confluent-api-key:latest" \
  --set-secrets="CONFLUENT_API_SECRET=confluent-api-secret:latest" \
  --allow-unauthenticated
```

> **Why this is secure:** Cloud Run connects to Cloud SQL through Google's private network using Unix sockets - no public IP exposure, no firewall rules needed.

---

## 7. Create Vertex AI Vector Search Index

### Step 7.1: Create GCS Bucket for Index Data

```bash
# Create bucket for vector search data
export BUCKET_NAME="${PROJECT_ID}-vectorsearch"
gcloud storage buckets create gs://${BUCKET_NAME} \
  --location=$REGION \
  --uniform-bucket-level-access
```

### Step 7.2: Create Initial Embeddings Data

Vector Search requires initial data to create an index. Create a minimal embeddings file with 768 dimensions:

```bash
# Generate a 768-dimension dummy embedding using Python
python3 << 'PYEOF'
import json
embedding = [round(i * 0.001, 4) for i in range(768)]
data = {"id": "init_1", "embedding": embedding}
with open("/tmp/initial_embeddings.json", "w") as f:
    f.write(json.dumps(data))
print(f"Created /tmp/initial_embeddings.json with {len(embedding)} dimensions")
PYEOF

# Upload to GCS
gcloud storage cp /tmp/initial_embeddings.json gs://${BUCKET_NAME}/initial/embeddings.json
echo "Uploaded initial embeddings to gs://${BUCKET_NAME}/initial/"
```

### Step 7.3: Create Index Metadata File

The `gcloud ai indexes create` command requires a metadata JSON file (not CLI flags):

```bash
# Create the metadata configuration file
# NOTE: indexUpdateMethod goes in CLI flag, NOT in this file
cat > /tmp/index_metadata.json << EOF
{
  "contentsDeltaUri": "gs://${BUCKET_NAME}/initial/",
  "config": {
    "dimensions": 768,
    "approximateNeighborsCount": 50,
    "distanceMeasureType": "COSINE_DISTANCE",
    "shardSize": "SHARD_SIZE_SMALL",
    "algorithmConfig": {
      "treeAhConfig": {
        "leafNodeEmbeddingCount": 1000,
        "leafNodesToSearchPercent": 10
      }
    }
  }
}
EOF

echo "Created index metadata file:"
cat /tmp/index_metadata.json
```

### Step 7.4: Create Vector Search Index

```bash
# Create the index using metadata file (this takes 20-45 minutes)
# NOTE: --index-update-method is a CLI flag, not in metadata file
gcloud ai indexes create \
  --display-name="cartiq-products-index" \
  --description="Product embeddings for CartIQ RAG recommendations" \
  --metadata-file=/tmp/index_metadata.json \
  --index-update-method=stream-update \
  --region=$REGION \
  --project=$PROJECT_ID

echo "Index creation started. This takes 20-45 minutes."
echo "You can continue with other setup steps while waiting."
```

**Check index creation status:**

When you run the create command, it will output an operation ID. Use it to track progress:

```bash
# Check operation status (replace OPERATION_ID and INDEX_ID from the create output)
gcloud ai operations describe OPERATION_ID \
  --index=INDEX_ID \
  --region=$REGION \
  --project=$PROJECT_ID

# Example:
# gcloud ai operations describe 8073834468260970496 \
#   --index=3388617870991687680 \
#   --region=us-central1 \
#   --project=cartiq-480815

# List all indexes to verify creation
gcloud ai indexes list --region=$REGION

# Get index details after creation completes
export INDEX_ID=$(gcloud ai indexes list --region=$REGION --format="value(name)" | head -1 | xargs basename)
echo "INDEX_ID=$INDEX_ID"
gcloud ai indexes describe $INDEX_ID --region=$REGION
```

> **Note:** Index creation takes 20-45 minutes. The operation shows `done: true` when complete.

### Step 7.5: Create Index Endpoint

**Option A: Public Endpoint (Simpler - recommended for hackathon)**
```bash
# Create public endpoint (no VPC setup required)
gcloud ai index-endpoints create \
  --display-name="cartiq-products-endpoint" \
  --description="Endpoint for CartIQ product vector search" \
  --public-endpoint-enabled \
  --region=$REGION

echo "Index endpoint creation started..."
```

**Option B: VPC Network Endpoint (More secure - for production)**
```bash
# Get project NUMBER (required - project ID won't work)
export PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format="value(projectNumber)")
echo "PROJECT_NUMBER=$PROJECT_NUMBER"

# Create VPC endpoint
gcloud ai index-endpoints create \
  --display-name="cartiq-products-endpoint" \
  --description="Endpoint for CartIQ product vector search" \
  --network="projects/${PROJECT_NUMBER}/global/networks/default" \
  --region=$REGION

echo "Index endpoint creation started..."
```

> **Note:** The `--network` flag requires project NUMBER (e.g., `12345`), not project ID (e.g., `cartiq-480815`).

**Get endpoint ID:**
```bash
# List endpoints
gcloud ai index-endpoints list --region=$REGION

# Save endpoint ID
export INDEX_ENDPOINT_ID=$(gcloud ai index-endpoints list --region=$REGION --format="value(name)" | head -1 | xargs basename)
echo "INDEX_ENDPOINT_ID=$INDEX_ENDPOINT_ID"
```

### Step 7.6: Deploy Index to Endpoint

**Wait for index creation to complete first!**

```bash
# Get your index ID
export INDEX_ID=$(gcloud ai indexes list --region=$REGION --format="value(name)" | head -1 | xargs basename)
echo "INDEX_ID=$INDEX_ID"

# Deploy index to endpoint
gcloud ai index-endpoints deploy-index $INDEX_ENDPOINT_ID \
  --deployed-index-id="cartiq_products_deployed" \
  --display-name="CartIQ Products" \
  --index=$INDEX_ID \
  --min-replica-count=1 \
  --max-replica-count=2 \
  --region=$REGION

echo "Index deployment started. This takes 10-20 minutes."
```

**Check deployment status:**
```bash
gcloud ai index-endpoints describe $INDEX_ENDPOINT_ID --region=$REGION
```

---

## 8. Create GCS Bucket for Batch Indexing

The batch indexing pipeline requires a GCS bucket to store intermediate files during the indexing process.

### Step 8.1: Create the Bucket

```bash
# Set bucket name
export BATCH_INDEXING_BUCKET="cartiq-indexing-data"

# Create the bucket with uniform bucket-level access
gcloud storage buckets create gs://${BATCH_INDEXING_BUCKET} \
  --project=$PROJECT_ID \
  --location=$REGION \
  --uniform-bucket-level-access

# Verify bucket was created
gcloud storage buckets describe gs://${BATCH_INDEXING_BUCKET}
```

### Step 8.2: Grant Service Account Permissions

The Cloud Run service account needs read/write access to the bucket:

```bash
# Get Cloud Run service account
# Option A: If using a custom service account
export SA_EMAIL="cartiq-backend@${PROJECT_ID}.iam.gserviceaccount.com"

# Option B: If using default compute service account
# export SA_EMAIL="$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')-compute@developer.gserviceaccount.com"

# Grant Storage Object Admin role on the bucket
gcloud storage buckets add-iam-policy-binding gs://${BATCH_INDEXING_BUCKET} \
  --member="serviceAccount:${SA_EMAIL}" \
  --role="roles/storage.objectAdmin"

echo "Granted storage.objectAdmin to ${SA_EMAIL} on gs://${BATCH_INDEXING_BUCKET}"
```

### Step 8.3: Bucket Structure

The batch indexing pipeline automatically creates this folder structure:

```
gs://cartiq-indexing-data/
├── input/                              # Step 1: Product export
│   └── {timestamp}/
│       └── products.jsonl              # Products in batch prediction format
├── embeddings/                         # Step 2: Batch embedding output
│   └── {timestamp}/
│       └── predictions-*.jsonl         # Embeddings from Vertex AI
└── vectors/                            # Step 3: Vector Search format
    └── {timestamp}/
        └── products.jsonl              # Ready for index update
```

### Step 8.4: Configure Application

Add these properties to your `application.properties` or environment variables:

```properties
# Batch Indexing Configuration
cartiq.rag.batch-indexing.gcs-bucket=cartiq-indexing-data
cartiq.rag.batch-indexing.input-prefix=input
cartiq.rag.batch-indexing.embeddings-prefix=embeddings
cartiq.rag.batch-indexing.vectors-prefix=vectors
cartiq.rag.batch-indexing.embedding-model=text-embedding-004
cartiq.rag.batch-indexing.complete-overwrite=true
```

### Step 8.5: Verify Bucket Access

```bash
# Test write access
echo "test" | gcloud storage cp - gs://${BATCH_INDEXING_BUCKET}/test.txt

# Verify file exists
gcloud storage ls gs://${BATCH_INDEXING_BUCKET}/

# Clean up test file
gcloud storage rm gs://${BATCH_INDEXING_BUCKET}/test.txt

echo "Bucket access verified!"
```

---

## 9. Verify Setup

### Check All Resources

```bash
echo "=== Checking GCP Resources ==="

echo -e "\n1. Cloud SQL Instance:"
gcloud sql instances describe cartiq-db --format="table(name,state,ipAddresses[0].ipAddress)"

echo -e "\n2. Redis Instance:"
gcloud redis instances describe cartiq-cache --region=$REGION --format="table(name,host,port,state)"

echo -e "\n3. Vector Search Index:"
gcloud ai indexes list --region=$REGION --format="table(displayName,name,updateTime)"

echo -e "\n4. Index Endpoint:"
gcloud ai index-endpoints list --region=$REGION --format="table(displayName,name)"

echo -e "\n5. Service Account:"
gcloud iam service-accounts list --filter="email:cartiq-backend"

echo -e "\n6. Secrets:"
gcloud secrets list --format="table(name,createTime)"

echo -e "\n7. Batch Indexing Bucket:"
gcloud storage buckets describe gs://cartiq-indexing-data --format="table(name,location,storageClass)"
```

### Test Vertex AI Embeddings

```bash
# Quick test to verify embeddings API works
curl -X POST \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H "Content-Type: application/json" \
  "https://${REGION}-aiplatform.googleapis.com/v1/projects/${PROJECT_ID}/locations/${REGION}/publishers/google/models/text-embedding-004:predict" \
  -d '{
    "instances": [
      {"content": "test embedding"}
    ]
  }'
```

---

## 10. Environment Variables Summary

After setup, you'll need these environment variables for the application:

```bash
# GCP Configuration
export GCP_PROJECT_ID="your-project-id"
export GCP_LOCATION="us-central1"
export GOOGLE_APPLICATION_CREDENTIALS="/path/to/cartiq-sa-key.json"

# Database Configuration
export DB_HOST="x.x.x.x"  # From sql instances describe
export SPRING_DATASOURCE_URL="jdbc:postgresql://${DB_HOST}:5432/cartiq"
export SPRING_DATASOURCE_USERNAME="cartiq-user"
# SPRING_DATASOURCE_PASSWORD from Secret Manager

# Redis Configuration
export REDIS_HOST="10.x.x.x"  # From redis instance describe
export REDIS_PORT="6379"

# Vector Search Configuration
export VECTOR_SEARCH_INDEX_ENDPOINT="projects/${GCP_PROJECT_ID}/locations/${GCP_LOCATION}/indexEndpoints/${INDEX_ENDPOINT_ID}"
export VECTOR_SEARCH_DEPLOYED_INDEX_ID="cartiq_products_deployed"
export VECTOR_SEARCH_API_ENDPOINT="${GCP_LOCATION}-aiplatform.googleapis.com"
```

**Create .env file:**
```bash
cat > .env << EOF
# GCP
GCP_PROJECT_ID=${PROJECT_ID}
GCP_LOCATION=${REGION}
GOOGLE_APPLICATION_CREDENTIALS=./cartiq-sa-key.json

# Database (Cloud SQL)
SPRING_DATASOURCE_URL=jdbc:postgresql://${DB_HOST}:5432/cartiq
SPRING_DATASOURCE_USERNAME=cartiq-user
SPRING_DATASOURCE_PASSWORD=$(gcloud secrets versions access latest --secret=db-password)

# Redis
REDIS_HOST=${REDIS_HOST}
REDIS_PORT=${REDIS_PORT}

# Vector Search
VECTOR_SEARCH_INDEX_ENDPOINT=projects/${PROJECT_ID}/locations/${REGION}/indexEndpoints/${INDEX_ENDPOINT_ID}
VECTOR_SEARCH_DEPLOYED_INDEX_ID=cartiq_products_deployed

# Vertex AI
VERTEX_AI_ENDPOINT=${REGION}-aiplatform.googleapis.com
EOF

echo ".env file created!"
```

---


## 11. Cost Estimates

| Resource | Configuration | Monthly Cost |
|----------|---------------|--------------|
| Cloud SQL PostgreSQL | db-f1-micro, 10GB SSD | ~$10 |
| Memorystore Redis | Basic 1GB | ~$35 |
| Vector Search Index | Small shard, 1 replica | ~$150 |
| Vector Search Queries | ~100K queries | ~$10 |
| Vertex AI Embeddings | ~100K embeddings | ~$25 |
| Secret Manager | 5 secrets, <1K accesses | ~$0 |
| **Total** | | **~$230/month** |

**Cost Optimization Tips:**
- Delete resources after hackathon demo
- Use minimum replica count (1)
- Choose smallest shard size

---

## 12. Cleanup (After Hackathon)

```bash
# Delete Cloud Run service
gcloud run services delete cartiq-backend --region=$REGION --quiet

# Delete VPC Connector
gcloud compute networks vpc-access connectors delete cartiq-connector \
  --region=$REGION --quiet

# Delete Vector Search resources
gcloud ai index-endpoints undeploy-index $INDEX_ENDPOINT_ID \
  --deployed-index-id="cartiq_products_deployed" \
  --region=$REGION

gcloud ai index-endpoints delete $INDEX_ENDPOINT_ID --region=$REGION
gcloud ai indexes delete $INDEX_ID --region=$REGION

# Delete Cloud SQL
gcloud sql instances delete cartiq-db --quiet

# Delete Redis
gcloud redis instances delete cartiq-cache --region=$REGION --quiet

# Delete GCS buckets
gcloud storage rm -r gs://${BUCKET_NAME}
gcloud storage rm -r gs://cartiq-indexing-data

# Delete secrets
for SECRET in jwt-secret db-password admin-password internal-api-key \
              confluent-api-key confluent-api-secret \
              confluent-sr-api-key confluent-sr-api-secret; do
  gcloud secrets delete $SECRET --quiet 2>/dev/null || true
done

# Delete service account
gcloud iam service-accounts delete $SA_EMAIL

echo "Cleanup complete!"
```

---

## Troubleshooting

### Redis Connection Issues

**Problem: Cloud Run cannot connect to Redis**

1. **Verify VPC Connector exists and is READY:**
```bash
gcloud compute networks vpc-access connectors describe cartiq-connector \
  --region=$REGION \
  --format="table(name,state,network)"
```

2. **If connector is in ERROR state, recreate it:**
```bash
# Delete broken connector
gcloud compute networks vpc-access connectors delete cartiq-connector \
  --region=$REGION --quiet

# Create new connector with different IP range
gcloud compute networks vpc-access connectors create cartiq-connector \
  --region=$REGION \
  --network=default \
  --range=10.8.0.0/28 \
  --min-instances=2 \
  --max-instances=3 \
  --machine-type=e2-micro
```

3. **Verify Cloud Run is using the VPC connector:**
```bash
gcloud run services describe cartiq-backend \
  --region=$REGION \
  --format="value(spec.template.metadata.annotations['run.googleapis.com/vpc-access-connector'])"
```

4. **Check Redis is on the same network:**
```bash
# Redis network
gcloud redis instances describe cartiq-cache --region=$REGION --format="value(authorizedNetwork)"

# VPC connector network (should match)
gcloud compute networks vpc-access connectors describe cartiq-connector \
  --region=$REGION --format="value(network)"
```

**Problem: Redis connection timeout**

```bash
# Verify Redis is READY
gcloud redis instances describe cartiq-cache --region=$REGION --format="value(state)"

# Check Redis host is correct in environment
gcloud redis instances describe cartiq-cache --region=$REGION --format="value(host)"
```

### VPC Connector Issues

**Problem: VPC Connector stuck in ERROR state**

Common causes:
- IP range conflict with existing subnets
- Insufficient quota for e2-micro instances

```bash
# Check for IP range conflicts
gcloud compute networks subnets list --network=default --format="table(name,ipCidrRange)"

# Try a different IP range (avoid 10.128.x.x which is used by default subnets)
gcloud compute networks vpc-access connectors create cartiq-connector \
  --region=$REGION \
  --network=default \
  --range=10.9.0.0/28  # Different range
```

**Problem: VPC Connector quota exceeded**

```bash
# Check VPC access connector quota
gcloud compute regions describe $REGION \
  --format="table(quotas.filter(metric='VPC_ACCESS_CONNECTORS'))"
```

### Vector Search "Index Not Ready"
```bash
# Check index state
gcloud ai indexes describe $INDEX_ID --region=$REGION --format="value(indexStats)"

# Index must be in ACTIVE state before deployment
```

### Permission Denied Errors
```bash
# Verify service account has correct roles
gcloud projects get-iam-policy $PROJECT_ID \
  --flatten="bindings[].members" \
  --filter="bindings.members:${SA_EMAIL}"
```

---

## Quick Reference

| Resource | Command to Get Details |
|----------|------------------------|
| Redis Host | `gcloud redis instances describe cartiq-cache --region=$REGION --format="value(host)"` |
| Redis Config | `gcloud redis instances describe cartiq-cache --region=$REGION --format="value(redisConfigs)"` |
| VPC Connector | `gcloud compute networks vpc-access connectors describe cartiq-connector --region=$REGION` |
| Index ID | `gcloud ai indexes list --region=$REGION --format="value(name)"` |
| Endpoint ID | `gcloud ai index-endpoints list --region=$REGION --format="value(name)"` |
| Project ID | `gcloud config get-value project` |
| Batch Indexing Bucket | `gcloud storage buckets describe gs://cartiq-indexing-data` |

### Current Infrastructure (CartIQ)

| Resource | Value |
|----------|-------|
| Redis Instance | `cartiq-cache` |
| Redis Version | 7.2 |
| Redis Host | `10.143.43.75` |
| Redis Eviction Policy | `volatile-lru` |
| VPC Connector | `cartiq-connector` |
| VPC Connector IP Range | `10.8.0.0/28` |

---

*Last updated: December 28, 2025*
