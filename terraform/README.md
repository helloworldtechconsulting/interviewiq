# InterviewIQ Terraform Configuration

Complete infrastructure-as-code for deploying InterviewIQ on Google Cloud Platform.

## Architecture

- **Compute**: Cloud Run for scalable containerized backend
- **Database**: Cloud SQL PostgreSQL 15 with private IP
- **Storage**: Cloud Storage for data and static frontend files
- **Networking**: VPC with private service access and Cloud NAT
- **Secrets**: Secret Manager for sensitive configuration
- **Monitoring**: Cloud Monitoring, Logging, and Uptime Checks
- **Registry**: Artifact Registry for Docker images

## Structure

```
terraform/
├── main.tf                 # Root configuration and module composition
├── variables.tf            # Input variables
├── outputs.tf              # Output values
├── terraform.tfvars.example # Example values
├── modules/                # Reusable modules
│   ├── networking/        # VPC, subnets, connectors, NAT
│   ├── cloud-run/         # Cloud Run service and IAM
│   ├── cloud-sql/         # Cloud SQL database
│   ├── gcs/               # Cloud Storage buckets
│   ├── secrets/           # Secret Manager
│   └── monitoring/        # Monitoring and alerts
└── envs/                  # Environment-specific configurations
    ├── staging/           # Staging environment
    └── production/        # Production environment
```

## Prerequisites

1. **GCP Setup**:
   - GCP project with billing enabled
   - Service account with appropriate roles
   - Terraform state bucket already created (manual step)

2. **Local Tools**:
   - Terraform >= 1.5.0
   - Google Cloud SDK
   - Authentication: `gcloud auth login`

3. **GitHub Actions Setup** (for CI/CD):
   - Workload Identity Federation configured
   - GitHub secrets configured (WIF_PROVIDER, WIF_SERVICE_ACCOUNT, GCP_PROJECT_ID)

## Initial Setup

### 1. Create Terraform State Bucket

```bash
gsutil mb -p <PROJECT_ID> -l asia-south1 gs://interviewiq-terraform-state
gsutil versioning set on gs://interviewiq-terraform-state
```

### 2. Create terraform.tfvars

Copy `terraform.tfvars.example` to the appropriate environment:

```bash
# For staging
cp terraform/envs/staging/terraform.tfvars.example terraform/envs/staging/terraform.tfvars

# For production
cp terraform/envs/production/terraform.tfvars.example terraform/envs/production/terraform.tfvars
```

Update all values in the `.tfvars` files with your configuration.

### 3. Initialize and Validate

```bash
# Initialize Terraform
cd terraform/envs/staging
terraform init

# Validate configuration
terraform validate

# Format check
terraform fmt -check -recursive ../../
```

## Deployment

### Staging Environment

```bash
cd terraform/envs/staging

# Plan changes
terraform plan -out=tfplan

# Apply changes
terraform apply tfplan
```

### Production Environment

```bash
cd terraform/envs/production

# Plan changes
terraform plan -out=tfplan

# Apply changes (requires careful review)
terraform apply tfplan
```

## Key Variables

### VPC Configuration
- `vpc_cidr`: VPC CIDR block (default: 10.0.0.0/16)
- `subnet_cidr`: Subnet CIDR block (default: 10.0.1.0/24)

### Cloud SQL
- `instance_tier`: db-f1-micro (staging) or db-custom-1-3840 (production)
- `availability_type`: ZONAL (staging) or REGIONAL (production)
- `backup_retention_days`: Number of days to retain backups (default: 7)

### Cloud Run
- Staging: 1 CPU, 1Gi memory, 1-5 instances
- Production: 2 CPU, 2Gi memory, 2-20 instances

### Storage
- Data bucket: Private, versioning enabled, lifecycle rules for cost optimization
- Frontend bucket: Public website hosting, CORS enabled

### Monitoring
- Uptime checks for Cloud Run health endpoint
- Alerts for: 5xx errors, CPU usage, database connections
- Custom dashboard and log-based metrics

## Secrets Management

Secrets are stored in Secret Manager and injected into Cloud Run via environment variables:

- `db-password`: Cloud SQL password
- `openai-api-key`: OpenAI API key
- `razorpay-key-id`: Razorpay merchant key
- `razorpay-key-secret`: Razorpay merchant secret
- `jwt-secret`: JWT signing secret
- `razorpay-webhook-secret`: Razorpay webhook secret

Update secrets in `terraform.tfvars`, Terraform will manage them.

## Outputs

After successful deployment, check outputs:

```bash
terraform output
```

Key outputs:
- `cloud_run_service_url`: Backend service URL
- `cloud_sql_instance_connection`: Cloud SQL connection string
- `gcs_data_bucket_name`: Data storage bucket
- `gcs_frontend_bucket_name`: Frontend hosting bucket
- `artifact_registry_repository_url`: Docker registry URL

## Maintenance

### Database Backups

Automated backups are configured:
- Staging: 7-day retention
- Production: 30-day retention

Manual backup:
```bash
gcloud sql backups create --instance=interviewiq-postgres-staging
```

### Scaling Cloud Run

Update `cloud_run_min_instances` and `cloud_run_max_instances` in terraform.tfvars.

### Monitoring

Access Cloud Monitoring dashboard:
```bash
gcloud monitoring dashboards list
```

## Troubleshooting

### Terraform State Issues

```bash
# Refresh state
terraform refresh

# View state
terraform state list
terraform state show <resource>
```

### Cloud Run Deployment Issues

```bash
# Check logs
gcloud run services describe interviewiq-backend-staging --region=asia-south1
gcloud logging read "resource.type=cloud_run_revision" --limit=50
```

### Cloud SQL Connection Issues

```bash
# Test connection via Cloud SQL Proxy
cloud_sql_proxy -instances=<PROJECT>:asia-south1:interviewiq-postgres-staging=tcp:5432
```

## Cleanup

### Destroy Resources

```bash
# CAUTION: This will delete all resources
cd terraform/envs/staging
terraform destroy

# Manually delete state bucket if needed
gsutil -m rm -r gs://interviewiq-terraform-state
```

## Cost Optimization

- Use db-f1-micro for staging (shared-core database)
- Cloud NAT uses pay-per-use pricing
- Cloud Storage lifecycle policies move old data to cheaper tiers
- Cloud Run scales down to 0 during low traffic (if min_instances=0)

## Security Best Practices

1. **Secrets**: Never commit `.tfvars` with real secrets
2. **Network**: Private IP for Cloud SQL, VPC connector for Cloud Run
3. **IAM**: Least privilege service accounts with minimal roles
4. **Monitoring**: Alerts configured for security events
5. **State**: Enable versioning on state bucket, restrict access

## References

- [Terraform Google Provider Documentation](https://registry.terraform.io/providers/hashicorp/google/latest/docs)
- [GCP Cloud Run Documentation](https://cloud.google.com/run/docs)
- [Cloud SQL Documentation](https://cloud.google.com/sql/docs)
- [Terraform Best Practices](https://cloud.google.com/docs/terraform/best-practices)
