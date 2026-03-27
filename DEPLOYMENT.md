# InterviewIQ Deployment Guide

Complete guide to deploying InterviewIQ on Google Cloud Platform.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [GCP Setup](#gcp-setup)
3. [Local Development](#local-development)
4. [CI/CD Setup](#cicd-setup)
5. [Staging Deployment](#staging-deployment)
6. [Production Deployment](#production-deployment)
7. [Post-Deployment Verification](#post-deployment-verification)
8. [Troubleshooting](#troubleshooting)

## Prerequisites

### Required Tools

- Terraform 1.5.0+
- Google Cloud SDK
- Docker and Docker Compose
- Node.js 20+
- Java 21 (OpenJDK or Eclipse Temurin)
- Maven 3.9+
- git

### Installation

```bash
# macOS (using Homebrew)
brew install terraform google-cloud-sdk docker node openjdk maven

# Ubuntu/Debian
sudo apt-get install -y terraform google-cloud-cli docker.io nodejs openjdk-21-jdk maven

# Verify installations
terraform version
gcloud --version
docker --version
node --version
java -version
mvn --version
```

### GCP Account

- Active GCP project with billing enabled
- Appropriate IAM roles:
  - Compute Admin
  - Cloud SQL Admin
  - Storage Admin
  - Service Account Admin
  - Secret Manager Admin
  - Cloud Run Admin
  - Monitoring Admin

## GCP Setup

### 1. Set Default Project

```bash
gcloud config set project <PROJECT_ID>
export GCP_PROJECT_ID=<PROJECT_ID>
export GCP_REGION=asia-south1
```

### 2. Enable Required APIs

```bash
gcloud services enable \
  compute.googleapis.com \
  run.googleapis.com \
  sqladmin.googleapis.com \
  storage.googleapis.com \
  secretmanager.googleapis.com \
  servicenetworking.googleapis.com \
  cloudresourcemanager.googleapis.com \
  monitoring.googleapis.com \
  logging.googleapis.com \
  artifactregistry.googleapis.com \
  containerregistry.googleapis.com
```

### 3. Create Terraform State Bucket

```bash
gsutil mb -p ${GCP_PROJECT_ID} -l ${GCP_REGION} gs://interviewiq-terraform-state

# Enable versioning
gsutil versioning set on gs://interviewiq-terraform-state

# Set lifecycle policy (delete old versions after 30 days)
cat > /tmp/lifecycle.json << 'EOF'
{
  "lifecycle": {
    "rule": [
      {
        "action": {"type": "Delete"},
        "condition": {"numNewerVersions": 10}
      }
    ]
  }
}
EOF
gsutil lifecycle set /tmp/lifecycle.json gs://interviewiq-terraform-state
```

### 4. Create Service Account for Terraform

```bash
# Create service account
gcloud iam service-accounts create terraform-sa \
  --display-name="Terraform Service Account"

# Grant necessary roles
for role in \
  roles/compute.admin \
  roles/cloudsql.admin \
  roles/storage.admin \
  roles/servicemanagement.admin \
  roles/iam.securityAdmin \
  roles/run.admin \
  roles/secretmanager.admin \
  roles/monitoring.admin \
  roles/logging.admin \
  roles/artifactregistry.admin; do
  gcloud projects add-iam-policy-binding ${GCP_PROJECT_ID} \
    --member=serviceAccount:terraform-sa@${GCP_PROJECT_ID}.iam.gserviceaccount.com \
    --role=${role} \
    --condition=None
done

# Create and download key
gcloud iam service-accounts keys create ~/terraform-sa-key.json \
  --iam-account=terraform-sa@${GCP_PROJECT_ID}.iam.gserviceaccount.com

# Set environment variable
export GOOGLE_APPLICATION_CREDENTIALS=~/terraform-sa-key.json
```

## Local Development

### 1. Clone Repository

```bash
git clone <REPOSITORY_URL>
cd interviewiq
```

### 2. Set Up Environment

```bash
# Copy and update environment file
cp .env.example .env

# Edit .env with your configuration
# Required: OPENAI_API_KEY, RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, JWT_SECRET
nano .env
```

### 3. Start Local Environment

```bash
# Build and start services
docker-compose up -d

# Wait for services to be ready
docker-compose ps

# Check logs
docker-compose logs -f backend
docker-compose logs -f frontend
docker-compose logs -f postgres
```

### 4. Verify Local Deployment

```bash
# Backend health check
curl http://localhost:8080/actuator/health

# Frontend
open http://localhost:3000

# Database connection
psql -h localhost -U interviewiq -d interviewiq
```

### 5. Stop Local Environment

```bash
docker-compose down
docker-compose down -v  # Also remove volumes
```

## CI/CD Setup

### 1. Create Workload Identity Federation

```bash
# Create GCP identity provider
gcloud iam workload-identity-pools create github-pool \
  --project=${GCP_PROJECT_ID} \
  --location=global \
  --display-name="GitHub Pool"

# Get the pool resource name
POOL_NAME=$(gcloud iam workload-identity-pools describe github-pool \
  --project=${GCP_PROJECT_ID} \
  --location=global \
  --format='value(name)')

# Create identity provider
gcloud iam workload-identity-providers create-oidc gh-provider \
  --project=${GCP_PROJECT_ID} \
  --location=global \
  --display-name="GitHub Provider" \
  --attribute-mapping="google.subject=assertion.sub,attribute.actor=assertion.actor,attribute.aud=assertion.aud,attribute.repository=assertion.repository" \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --workload-identity-pool=${POOL_NAME}

# Get the provider resource name
PROVIDER_NAME=$(gcloud iam workload-identity-providers describe gh-provider \
  --project=${GCP_PROJECT_ID} \
  --location=global \
  --workload-identity-pool=github-pool \
  --format='value(name)')
```

### 2. Create Service Account for GitHub Actions

```bash
# Create service account
gcloud iam service-accounts create github-actions-sa \
  --display-name="GitHub Actions Service Account"

# Grant necessary roles
for role in \
  roles/run.admin \
  roles/artifactregistry.writer \
  roles/storage.admin \
  roles/editor; do
  gcloud projects add-iam-policy-binding ${GCP_PROJECT_ID} \
    --member=serviceAccount:github-actions-sa@${GCP_PROJECT_ID}.iam.gserviceaccount.com \
    --role=${role}
done

# Configure workload identity
gcloud iam service-accounts add-iam-policy-binding \
  github-actions-sa@${GCP_PROJECT_ID}.iam.gserviceaccount.com \
  --project=${GCP_PROJECT_ID} \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/${PROVIDER_NAME}/attribute.repository/YOUR_ORG/interviewiq"
```

### 3. Configure GitHub Secrets

Add these secrets to your GitHub repository (Settings > Secrets and variables > Actions):

```bash
WIF_PROVIDER=<PROVIDER_NAME>
WIF_SERVICE_ACCOUNT=github-actions-sa@${GCP_PROJECT_ID}.iam.gserviceaccount.com
GCP_PROJECT_ID=<PROJECT_ID>
```

## Staging Deployment

### 1. Prepare Terraform Variables

```bash
cd terraform/envs/staging

# Copy and update terraform.tfvars
cp terraform.tfvars.example terraform.tfvars

# Edit with your configuration
nano terraform.tfvars
```

### 2. Initialize Terraform

```bash
# Initialize with remote state
terraform init

# Validate configuration
terraform validate

# Format check
terraform fmt -check -recursive ../../
```

### 3. Plan Deployment

```bash
# Create plan
terraform plan -out=tfplan

# Review plan carefully
# Check for:
# - Correct region (asia-south1)
# - Correct environment (staging)
# - Correct instance sizes
# - Correct bucket names
```

### 4. Apply Configuration

```bash
# Apply plan
terraform apply tfplan

# Wait for completion (may take 10-15 minutes)

# Capture outputs
terraform output -json > outputs.json
```

### 5. Build and Push Docker Images

```bash
# Set variables
export GCP_REGION=asia-south1
export ARTIFACT_REGISTRY=interviewiq-docker

# Authenticate Docker
gcloud auth configure-docker ${GCP_REGION}-docker.pkg.dev

# Build and push backend
cd ../../..
docker build -t ${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${ARTIFACT_REGISTRY}/backend:staging-latest backend/
docker push ${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${ARTIFACT_REGISTRY}/backend:staging-latest

# Build and push frontend
cd frontend
npm install
npm run build
gsutil -m cp -r build/* gs://interviewiq-staging-frontend/
```

### 6. Update Cloud Run Service

```bash
# Update with new image
gcloud run deploy interviewiq-backend-staging \
  --image=${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${ARTIFACT_REGISTRY}/backend:staging-latest \
  --region=${GCP_REGION} \
  --platform=managed \
  --allow-unauthenticated \
  --project=${GCP_PROJECT_ID}
```

## Production Deployment

### Prerequisites

- Staging environment fully tested
- All variables reviewed and hardened
- Database backups configured
- Monitoring and alerts verified

### 1. Prepare Production Terraform Variables

```bash
cd terraform/envs/production

# Copy and update terraform.tfvars
cp terraform.tfvars.example terraform.tfvars

# Edit with production-specific values:
# - Stronger database passwords
# - Production API keys
# - Production secrets
nano terraform.tfvars
```

### 2. Plan Production Deployment

```bash
terraform init
terraform plan -out=tfplan

# Review plan VERY carefully
# Key differences from staging:
# - db-custom-1-3840 instance tier
# - REGIONAL availability type
# - 2 CPU, 2Gi memory for Cloud Run
# - 2-20 instance scaling
# - 30-day backup retention
```

### 3. Apply Production Configuration

```bash
# IMPORTANT: Get approval before applying
terraform apply tfplan

# Monitor logs
gcloud logging read "resource.type=cloud_run_revision" \
  --limit=50 \
  --format=json
```

### 4. Blue-Green Deployment

```bash
# Test new version before switching traffic
# 1. Deploy to new Cloud Run service or revision
# 2. Test thoroughly
# 3. Gradually shift traffic
# 4. Monitor metrics
# 5. Complete cutover

# Monitor traffic split
gcloud run services describe interviewiq-backend-production \
  --region=${GCP_REGION}
```

## Post-Deployment Verification

### 1. Health Checks

```bash
# Get Cloud Run service URL
SERVICE_URL=$(gcloud run services describe interviewiq-backend-staging \
  --region=${GCP_REGION} \
  --format='value(status.url)')

# Test endpoints
curl -X GET "${SERVICE_URL}/actuator/health"
curl -X GET "${SERVICE_URL}/actuator/health/live"
curl -X GET "${SERVICE_URL}/actuator/health/ready"

# Check frontend
gcloud storage ls gs://interviewiq-staging-frontend/
```

### 2. Database Verification

```bash
# Get Cloud SQL instance connection
INSTANCE_NAME=$(gcloud sql instances list --filter="name:interviewiq-postgres-staging" --format="value(name)")

# Start Cloud SQL Proxy
cloud_sql_proxy -instances=${GCP_PROJECT_ID}:${GCP_REGION}:${INSTANCE_NAME}=tcp:5432 &

# Connect to database
psql -h 127.0.0.1 -U interviewiq -d interviewiq

# Verify tables
\dt
```

### 3. Monitoring and Logging

```bash
# View recent logs
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=interviewiq-backend-staging" \
  --limit=100

# Check metrics
gcloud monitoring time-series list \
  --filter='resource.type="cloud_run_revision"'

# View uptime checks
gcloud monitoring uptime list
```

### 4. Performance Testing

```bash
# Install Apache Bench
brew install httpd  # or apt-get install apache2-utils

# Run load test
ab -n 1000 -c 10 "${SERVICE_URL}/actuator/health"

# Monitor Cloud Run metrics during test
gcloud run services describe interviewiq-backend-staging \
  --region=${GCP_REGION}
```

## Troubleshooting

### Terraform Issues

```bash
# Enable debug logging
export TF_LOG=DEBUG

# Refresh state
terraform refresh

# Validate state
terraform state list
terraform state show <resource_name>

# Check for locked state
gcloud storage ls gs://interviewiq-terraform-state/
```

### Cloud Run Issues

```bash
# View service details
gcloud run services describe interviewiq-backend-staging \
  --region=${GCP_REGION}

# View recent errors
gcloud logging read "resource.type=cloud_run_revision AND severity=ERROR" \
  --limit=50

# Check service account permissions
gcloud projects get-iam-policy ${GCP_PROJECT_ID} \
  --flatten="bindings[].members" \
  --filter="bindings.members:interviewiq-*-sa"

# Test Cloud SQL connection
cloud_sql_proxy -instances=${GCP_PROJECT_ID}:${GCP_REGION}:interviewiq-postgres-staging=tcp:5432
```

### Database Issues

```bash
# View database instance status
gcloud sql instances describe interviewiq-postgres-staging

# Check recent backups
gcloud sql backups list --instance=interviewiq-postgres-staging

# View database logs
gcloud sql operations list --instance=interviewiq-postgres-staging
```

### Network Issues

```bash
# Check VPC status
gcloud compute networks describe interviewiq-staging-vpc

# Check subnet
gcloud compute networks subnets describe interviewiq-staging-subnet \
  --region=${GCP_REGION}

# Check VPC connector
gcloud compute networks vpc-access connectors describe interviewiq-staging-connector \
  --region=${GCP_REGION}
```

## Cleanup

### Remove Staging

```bash
cd terraform/envs/staging
terraform destroy

# Manually delete resources created outside Terraform
gsutil -m rm -r gs://interviewiq-staging-data/
gsutil -m rm -r gs://interviewiq-staging-frontend/
```

### Remove Production

```bash
# CAUTION: This will delete production data
cd terraform/envs/production
terraform destroy

# Create final backup before destroying
gcloud sql backups create \
  --instance=interviewiq-postgres-production \
  --description="Final backup before destruction"
```

## Support

For issues or questions:

1. Check Terraform documentation: https://www.terraform.io/docs
2. Check GCP documentation: https://cloud.google.com/docs
3. Review logs: `gcloud logging read --limit=100`
4. Check Cloud Run documentation: https://cloud.google.com/run/docs
