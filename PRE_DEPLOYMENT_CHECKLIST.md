# InterviewIQ Pre-Deployment Checklist

Complete checklist before deploying InterviewIQ to GCP.

## GCP Account Setup

- [ ] GCP project created
- [ ] Billing enabled on project
- [ ] Project ID noted: `_______________`
- [ ] Default region set to asia-south1

## Required APIs Enabled

- [ ] compute.googleapis.com
- [ ] run.googleapis.com
- [ ] sqladmin.googleapis.com
- [ ] storage.googleapis.com
- [ ] secretmanager.googleapis.com
- [ ] servicenetworking.googleapis.com
- [ ] cloudresourcemanager.googleapis.com
- [ ] monitoring.googleapis.com
- [ ] logging.googleapis.com
- [ ] artifactregistry.googleapis.com
- [ ] containerregistry.googleapis.com

### Enable APIs Command

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

## Service Accounts

### Terraform Service Account

- [ ] Service account `terraform-sa` created
- [ ] Roles assigned:
  - [ ] roles/compute.admin
  - [ ] roles/cloudsql.admin
  - [ ] roles/storage.admin
  - [ ] roles/servicemanagement.admin
  - [ ] roles/iam.securityAdmin
  - [ ] roles/run.admin
  - [ ] roles/secretmanager.admin
  - [ ] roles/monitoring.admin
  - [ ] roles/logging.admin
  - [ ] roles/artifactregistry.admin
- [ ] Key created and saved: `~/terraform-sa-key.json`
- [ ] Environment variable set: `export GOOGLE_APPLICATION_CREDENTIALS=~/terraform-sa-key.json`

### GitHub Actions Service Account

- [ ] Service account `github-actions-sa` created
- [ ] Roles assigned:
  - [ ] roles/run.admin
  - [ ] roles/artifactregistry.writer
  - [ ] roles/storage.admin
  - [ ] roles/editor (or equivalent)
- [ ] Workload Identity Federation configured
- [ ] Provider resource name: `_______________`
- [ ] Service account email: `_______________`

## Terraform State Backend

- [ ] State bucket `interviewiq-terraform-state` created
- [ ] Versioning enabled on state bucket
- [ ] Lifecycle policy configured (30-day retention)
- [ ] Appropriate permissions set on bucket

### Create State Bucket Command

```bash
export GCP_PROJECT_ID="your-project"
export GCP_REGION="asia-south1"

gsutil mb -p ${GCP_PROJECT_ID} -l ${GCP_REGION} gs://interviewiq-terraform-state
gsutil versioning set on gs://interviewiq-terraform-state

# Optional: Set lifecycle policy
cat > /tmp/lifecycle.json << 'EOF'
{
  "lifecycle": {
    "rule": [{
      "action": {"type": "Delete"},
      "condition": {"numNewerVersions": 10}
    }]
  }
}
EOF
gsutil lifecycle set /tmp/lifecycle.json gs://interviewiq-terraform-state
```

## Secrets and API Keys

### External Services

- [ ] OpenAI API key obtained: `sk-...`
- [ ] Razorpay account created
  - [ ] Test Key ID: `rzp_test_...`
  - [ ] Test Key Secret: `...`
  - [ ] Production Key ID: `rzp_live_...` (for production)
  - [ ] Production Key Secret: `...` (for production)
- [ ] Razorpay webhook secret generated

### Generated Secrets

- [ ] JWT secret generated (256-bit, base64 encoded)
  ```bash
  openssl rand -base64 32
  ```
- [ ] Strong database password generated
  ```bash
  openssl rand -base64 32
  ```

## Configuration Files

### Terraform Configuration

- [ ] `terraform/envs/staging/terraform.tfvars` created
  - [ ] gcp_project_id set
  - [ ] All passwords and API keys filled
  - [ ] Bucket names unique and consistent
  - [ ] Notification email valid
- [ ] `terraform/envs/production/terraform.tfvars` created (if deploying to production)
  - [ ] All values reviewed and hardened
  - [ ] Different bucket names from staging
  - [ ] Production API keys used

### Environment Variables

- [ ] `.env` created from `.env.example`
  - [ ] OPENAI_API_KEY set
  - [ ] RAZORPAY_KEY_ID set
  - [ ] RAZORPAY_KEY_SECRET set
  - [ ] RAZORPAY_WEBHOOK_SECRET set
  - [ ] JWT_SECRET set
  - [ ] GCP_PROJECT_ID set

- [ ] `frontend/.env` created (if separate)
  - [ ] REACT_APP_API_URL set correctly

## GitHub Setup

### Repository Configuration

- [ ] Repository cloned or code pushed
- [ ] Repository secrets added:
  - [ ] WIF_PROVIDER
  - [ ] WIF_SERVICE_ACCOUNT
  - [ ] GCP_PROJECT_ID

### Branch Protection

- [ ] Main branch protected
- [ ] PR reviews required (optional but recommended)
- [ ] CI must pass before merge
- [ ] Dismiss stale reviews on push (optional)

### GitHub Actions

- [ ] Workflows visible in Actions tab
- [ ] ci.yml triggers on push and PR
- [ ] deploy.yml ready for main branch pushes

## Local Development

### Tools Installed

- [ ] Docker and Docker Compose
- [ ] Java 21 (OpenJDK or Eclipse Temurin)
- [ ] Maven 3.9+
- [ ] Node.js 20+
- [ ] Google Cloud SDK
- [ ] Terraform 1.5+ (optional for validation)

### Local Environment

- [ ] `.env.example` copied to `.env`
- [ ] Local environment variables set
- [ ] Docker Compose tested:
  ```bash
  docker-compose up -d
  docker-compose ps
  curl http://localhost:8080/actuator/health
  # Verify all services running
  docker-compose down
  ```

## Pre-Deployment Tests

### Terraform Validation

```bash
cd terraform

# Validate syntax
terraform init -backend=false
terraform validate

# Validate modules
cd modules/networking && terraform validate && cd ../..
cd modules/cloud-run && terraform validate && cd ../..
cd modules/cloud-sql && terraform validate && cd ../..
cd modules/gcs && terraform validate && cd ../..
cd modules/secrets && terraform validate && cd ../..
cd modules/monitoring && terraform validate && cd ../..

# Validate environments
cd envs/staging && terraform validate && cd ../..
cd envs/production && terraform validate && cd ../..

# Format check
terraform fmt -check -recursive
```

### Docker Build Test

```bash
# Test backend build
docker build -t interviewiq-backend:test backend/

# Test frontend build
docker build -t interviewiq-frontend:test frontend/

# Cleanup
docker rmi interviewiq-backend:test interviewiq-frontend:test
```

### Code Quality

- [ ] Backend tests pass: `mvn test -DskipIntegrationTests`
- [ ] Frontend tests pass: `npm test` (if configured)
- [ ] Linting passes: `npm run lint` (if configured)
- [ ] No sensitive data in code or configs

## Documentation Review

- [ ] README.md reviewed
- [ ] DEPLOYMENT.md reviewed and understood
- [ ] INFRASTRUCTURE_SUMMARY.md reviewed
- [ ] terraform/README.md reviewed
- [ ] All paths and commands noted

## Staging Deployment Plan

- [ ] Day/time scheduled for deployment
- [ ] Stakeholders notified
- [ ] Runbook prepared
- [ ] Rollback plan ready

### Deployment Steps

1. [ ] Initialize Terraform: `cd terraform/envs/staging && terraform init`
2. [ ] Create plan: `terraform plan -out=tfplan`
3. [ ] Review plan carefully
4. [ ] Apply: `terraform apply tfplan`
5. [ ] Wait 10-15 minutes for resources
6. [ ] Verify outputs: `terraform output`
7. [ ] Build and push images
8. [ ] Deploy to Cloud Run
9. [ ] Run smoke tests
10. [ ] Verify frontend deployment
11. [ ] Update DNS (if applicable)
12. [ ] Monitor for 24 hours

## Post-Deployment Verification

### Health Checks

- [ ] Backend health check: `curl $SERVICE_URL/actuator/health`
- [ ] Frontend accessible: `open $FRONTEND_URL` or `curl $FRONTEND_URL`
- [ ] Database connection successful
- [ ] Cloud Storage buckets created and accessible
- [ ] Secrets accessible to Cloud Run

### Monitoring Setup

- [ ] Dashboard accessible in Cloud Monitoring
- [ ] Alerts configured and tested
- [ ] Logs visible in Cloud Logging
- [ ] Uptime checks working

### Data Verification

- [ ] Database migrations executed
- [ ] Tables created successfully
- [ ] Seed data inserted (if needed)
- [ ] Backups configured and running

## Production Deployment (When Ready)

- [ ] Staging fully tested and stable for 7+ days
- [ ] All issues from staging resolved
- [ ] Production configuration reviewed
- [ ] Production database backup plan confirmed
- [ ] Production monitoring configured
- [ ] Disaster recovery tested
- [ ] Team trained on operations
- [ ] Maintenance windows scheduled

## Ongoing Maintenance

### Weekly

- [ ] Check logs for errors
- [ ] Monitor performance metrics
- [ ] Review alerts
- [ ] Test backups

### Monthly

- [ ] Update dependencies
- [ ] Review security logs
- [ ] Check cost trends
- [ ] Verify backup retention

### Quarterly

- [ ] Test disaster recovery
- [ ] Review and update documentation
- [ ] Performance optimization review
- [ ] Security assessment

## Support Contacts

- [ ] DevOps lead: `_______________`
- [ ] Database admin: `_______________`
- [ ] Security officer: `_______________`
- [ ] On-call rotation established

## Sign-Off

- [ ] Infrastructure team review: _____ Date: _____
- [ ] Security review: _____ Date: _____
- [ ] Deployment approved by: _____ Date: _____

## Useful Commands Quick Reference

```bash
# GCP Project setup
gcloud config set project <PROJECT_ID>
gcloud services list --enabled

# Terraform
cd terraform/envs/staging
terraform init
terraform plan -out=tfplan
terraform apply tfplan
terraform output -json

# Docker
docker-compose up -d
docker-compose logs -f
docker-compose down

# Cloud Run
gcloud run services list
gcloud run services describe interviewiq-backend-staging --region=asia-south1

# Cloud SQL
gcloud sql instances list
gcloud sql instances describe interviewiq-postgres-staging

# Logging
gcloud logging read "resource.type=cloud_run_revision" --limit=50

# Storage
gsutil ls -r gs://interviewiq-staging-frontend/
gsutil -m cp gs://interviewiq-staging-frontend/* .
```

## Notes

Use this section for additional notes and observations:

```
_________________________________________________________
_________________________________________________________
_________________________________________________________
_________________________________________________________
```

---

**Document Version**: 1.0
**Last Updated**: 2026-03-27
**Valid For**: InterviewIQ v1.0 Deployment
