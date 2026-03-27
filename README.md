# InterviewIQ

AI-powered interview preparation platform on Google Cloud Platform.

## Project Structure

```
interviewiq/
├── terraform/              # Infrastructure as Code (GCP)
│   ├── modules/           # Reusable Terraform modules
│   ├── envs/              # Environment configurations (staging/production)
│   └── README.md          # Terraform documentation
├── backend/               # Spring Boot backend service
│   ├── src/
│   ├── pom.xml
│   └── Dockerfile
├── frontend/              # React/TypeScript frontend
│   ├── src/
│   ├── package.json
│   ├── Dockerfile
│   ├── nginx.conf         # Nginx configuration
│   └── default.conf       # Nginx server config
├── .github/workflows/     # CI/CD pipelines
│   ├── ci.yml            # Build and test pipeline
│   └── deploy.yml        # Deployment pipeline
├── docker-compose.yml    # Local development environment
├── DEPLOYMENT.md         # Detailed deployment guide
├── .env.example          # Example environment variables
└── .gitignore            # Git ignore rules
```

## Quick Start

### Local Development

**Prerequisites**:
- Docker and Docker Compose
- Java 21
- Node.js 20
- Maven 3.9+

**Start environment**:

```bash
# Copy environment file
cp .env.example .env

# Start all services (postgres, backend, frontend)
docker-compose up -d

# Monitor logs
docker-compose logs -f

# Access services
# Frontend: http://localhost:3000
# Backend: http://localhost:8080
# Database: localhost:5432
```

**Stop environment**:

```bash
docker-compose down
```

### GCP Deployment

See [DEPLOYMENT.md](./DEPLOYMENT.md) for complete deployment instructions.

**Quick summary**:

```bash
# Staging
cd terraform/envs/staging
terraform init
terraform plan
terraform apply

# Production
cd terraform/envs/production
terraform init
terraform plan
terraform apply
```

## Architecture

### Local Development
```
Docker Compose
├── PostgreSQL 15
├── Spring Boot Backend (8080)
└── React Frontend (3000)
    └── Nginx reverse proxy
```

### GCP Production
```
Load Balancer + Cloud CDN
├── Cloud Run (Backend)
│   ├── Service Account
│   ├── VPC Connector
│   └── Secret Manager integration
├── Cloud SQL (PostgreSQL 15, Private IP)
├── Cloud Storage
│   ├── Data bucket (versioning, lifecycle rules)
│   └── Frontend bucket (website hosting)
├── Cloud NAT (for secure egress)
└── Cloud Monitoring + Logging
```

## Technology Stack

### Backend
- Java 21
- Spring Boot 3.x
- Spring Data JPA
- PostgreSQL 15
- Google Cloud Client Libraries
- OpenAI API integration
- Razorpay payment integration

### Frontend
- React 18
- TypeScript
- Vite
- Tailwind CSS
- Axios for API calls

### Infrastructure
- Google Cloud Platform (asia-south1, Mumbai)
- Terraform 1.5+
- Docker and Docker Compose
- GitHub Actions for CI/CD

## Development

### Backend Development

```bash
# Build
cd backend
mvn clean package

# Run
java -jar target/interviewiq-*.jar

# Tests
mvn test

# Docker build
docker build -t interviewiq-backend:latest .
docker run -p 8080:8080 interviewiq-backend:latest
```

### Frontend Development

```bash
# Install dependencies
cd frontend
npm install

# Development server
npm run dev

# Build
npm run build

# Lint
npm run lint

# Docker build
docker build -t interviewiq-frontend:latest .
docker run -p 3000:80 interviewiq-frontend:latest
```

### Terraform

```bash
# Validate
cd terraform
terraform validate
terraform fmt -check -recursive

# Plan
terraform plan -out=tfplan

# Apply
terraform apply tfplan

# Destroy
terraform destroy
```

## CI/CD Pipeline

### GitHub Actions Workflows

#### CI Pipeline (ci.yml)
- Triggered on: push to any branch, PR to main
- Jobs:
  - Backend: Maven build + tests
  - Frontend: npm build + lint
  - Terraform: validate + format check
  - Docker: build images

#### Deploy Pipeline (deploy.yml)
- Triggered on: push to main, manual workflow_dispatch
- Jobs:
  - Build backend Docker image → Artifact Registry
  - Build frontend → Cloud Storage
  - Deploy backend → Cloud Run
  - Deploy infrastructure → Terraform apply
  - Smoke tests → health checks

### Secrets Required

In GitHub repository settings:

```
WIF_PROVIDER=<provider-resource-name>
WIF_SERVICE_ACCOUNT=github-actions-sa@<project>.iam.gserviceaccount.com
GCP_PROJECT_ID=<your-gcp-project>
```

## Environment Variables

### Local Development (.env)

```bash
GCP_PROJECT_ID=your-project
OPENAI_API_KEY=sk-...
RAZORPAY_KEY_ID=rzp_test_...
RAZORPAY_KEY_SECRET=...
JWT_SECRET=your-secret-key
```

### Cloud Deployment (Secret Manager)

Secrets are managed via Terraform and accessed from Secret Manager:

```
- db-password
- openai-api-key
- razorpay-key-id
- razorpay-key-secret
- jwt-secret
- razorpay-webhook-secret
```

## Database

### Schema

```sql
-- Users
CREATE TABLE users (
  id SERIAL PRIMARY KEY,
  email VARCHAR(255) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Interviews
CREATE TABLE interviews (
  id SERIAL PRIMARY KEY,
  user_id INTEGER REFERENCES users(id),
  title VARCHAR(255),
  description TEXT,
  status VARCHAR(50),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Interview Recordings
CREATE TABLE interview_recordings (
  id SERIAL PRIMARY KEY,
  interview_id INTEGER REFERENCES interviews(id),
  gcs_path VARCHAR(255),
  duration_seconds INTEGER,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### Migrations

Using Flyway/Liquibase (configured in Spring Boot):

```
src/main/resources/db/migration/
├── V1__initial_schema.sql
├── V2__add_columns.sql
└── ...
```

## Testing

### Backend Tests

```bash
cd backend
mvn test
mvn verify
```

### Frontend Tests

```bash
cd frontend
npm test
npm run test:coverage
```

### Integration Tests

```bash
# Run with test database
docker-compose -f docker-compose.test.yml up
```

## Monitoring

### Cloud Monitoring

- **Uptime Checks**: Health endpoints
- **Alert Policies**:
  - 5xx error rate > 1%
  - CPU usage > 80% (staging) / > 70% (production)
  - Database connections > 80% (staging) / > 70% (production)
- **Custom Metrics**: Interview completions

### Logging

View logs:

```bash
gcloud logging read "resource.type=cloud_run_revision" --limit=100
```

### Dashboards

Cloud Monitoring dashboard with metrics:
- Request rate
- Error rate
- Latency
- CPU/Memory usage
- Database connections
- Interview completions

## Performance

### Optimization

- Cloud CDN for static assets
- Cloud Storage lifecycle rules for cost optimization
- Cloud SQL index optimization
- Connection pooling
- API rate limiting
- Frontend code splitting and lazy loading

### Scaling

- Cloud Run auto-scaling (1-10 instances in staging, 2-20 in production)
- Database read replicas (production)
- Cloud Storage caching headers
- Gzip compression enabled

## Security

### Network

- VPC with private subnets
- Cloud SQL private IP only
- VPC Access Connector for Cloud Run to Cloud SQL
- Cloud NAT for secure egress
- Firewall rules with least privilege

### Secrets

- Secret Manager for sensitive data
- Automatic rotation policies
- IAM controls on secret access
- No secrets in code/config

### Authentication

- JWT token-based authentication
- HTTPS enforced
- CORS configured
- API key rotation

### Compliance

- Data encryption at rest and in transit
- Audit logging enabled
- GDPR-compliant data handling
- Regular backups (7 days staging, 30 days production)

## Troubleshooting

### Local Development

```bash
# Logs
docker-compose logs -f <service>

# Shell access
docker-compose exec backend sh
docker-compose exec postgres psql -U interviewiq

# Database reset
docker-compose down -v
docker-compose up -d postgres
```

### GCP Deployment

See [DEPLOYMENT.md](./DEPLOYMENT.md) for detailed troubleshooting.

```bash
# Cloud Run logs
gcloud logging read "resource.type=cloud_run_revision" --limit=50

# Cloud SQL issues
cloud_sql_proxy -instances=<PROJECT>:<REGION>:<INSTANCE>=tcp:5432

# Terraform state
terraform refresh
terraform state list
```

## Documentation

- [Terraform Documentation](./terraform/README.md)
- [Deployment Guide](./DEPLOYMENT.md)
- [Backend API Documentation](./backend/README.md) (if exists)
- [Frontend Documentation](./frontend/README.md) (if exists)

## Contributing

1. Create feature branch from `develop`
2. Make changes and commit with clear messages
3. Push to GitHub and open Pull Request
4. CI pipeline runs automatically
5. After approval and merge to main, CD pipeline deploys

### Commit Convention

```
feat: Add new feature
fix: Fix bug
docs: Update documentation
test: Add/update tests
perf: Performance improvements
refactor: Code refactoring
chore: Build/maintenance
```

## License

Proprietary - All rights reserved

## Support

For issues or questions:

1. Check documentation in DEPLOYMENT.md
2. Review logs: `gcloud logging read ...`
3. Check GitHub Issues
4. Contact DevOps team

## Roadmap

- [ ] Machine learning model for interview analysis
- [ ] Real-time feedback during interviews
- [ ] Video recording and playback
- [ ] Peer-reviewed interviews
- [ ] Advanced analytics dashboard
- [ ] Mobile application
- [ ] Multi-language support
