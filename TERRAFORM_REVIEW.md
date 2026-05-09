# InterviewIQ — Terraform / Infra Launch Readiness Review

_Date: 2026-05-09_

---

## 1. Critical finding (TL;DR)

**There is no Terraform module in this repository. There are zero `.tf` files. Infrastructure cannot be provisioned by `terraform apply` today.** The directory `terraform/` contains only two files:

```
terraform/
└── envs/
    ├── staging/terraform.tfvars
    └── production/terraform.tfvars
```

Both are variable files for a module that does not exist, and **both target Google Cloud Platform** while the PRD §12.1 specifies AWS in `ap-south-1`. This is a P0 launch blocker.

---

## 2. Current state of `/Users/sps/code/interviewiq/terraform/`

| File | Lines | Provider implied | Status |
|---|---|---|---|
| `envs/staging/terraform.tfvars` | 26 | **GCP** (`gcp_project_id`, `cloud_run_service_name`, `artifact_registry_repository`, region `asia-south1`) | placeholder values only |
| `envs/production/terraform.tfvars` | 26 | **GCP** (same as staging, region `asia-south1`, image tag `:latest`) | placeholder values only |
| `*.tf` | 0 | — | **does not exist** |
| `modules/` | 0 | — | **does not exist** |
| `backend.tf` (state) | 0 | — | **does not exist** |
| `versions.tf` (provider pins) | 0 | — | **does not exist** |

### What the tfvars actually declare

Variables referenced in both files:

```
gcp_project_id, gcp_region, vpc_cidr, subnet_cidr,
database_instance_name, database_name, database_user, db_password,
data_bucket_name, frontend_bucket_name,
openai_api_key, razorpay_key_id, razorpay_key_secret, razorpay_webhook_secret,
jwt_secret, cloud_run_service_name, backend_image_url,
artifact_registry_repository, notification_email_address
```

**All secrets are committed in plaintext placeholder form** (`db_password = "CHANGE_ME_TO_STRONG_PASSWORD"`, `openai_api_key = "sk-YOUR_OPENAI_KEY_HERE"`, etc.). The intent is clearly that operators replace them — but the file is checked into git, which is itself a footgun pattern.

---

## 3. Cloud provider mismatch — P0

**The PRD §12.1 specifies AWS resources in `ap-south-1`. The tfvars target GCP in `asia-south1`.**

| Layer | PRD says (AWS) | tfvars say (GCP) |
|---|---|---|
| Compute | ECS Fargate (2–10 tasks, autoscale) | Cloud Run (`cloud_run_service_name`) |
| Container registry | ECR | Artifact Registry (`artifact_registry_repository`) |
| Database | RDS Postgres 15 db.t3.medium | Cloud SQL Postgres (`database_instance_name`) |
| Object storage | S3 (data + frontend buckets) | GCS (`data_bucket_name`, `frontend_bucket_name`) |
| Edge/CDN | CloudFront with OAC | implied: GCS + Load Balancer |
| API gateway | API Gateway HTTP API | implied: Cloud Run direct |
| Email | SES (verified domain) | not addressed |
| Secrets | Secrets Manager | implied: Secret Manager |
| Logs/metrics | CloudWatch + X-Ray | implied: Cloud Logging |
| DNS | Route 53 | implied: Cloud DNS |
| Region | ap-south-1 | asia-south1 |

The Java code is **AWS-locked**: `software.amazon.awssdk.services.s3.S3Client` (StorageService line 7), `software.amazon.awssdk.services.ses.SesClient` (EmailService line 12), `AwsConfig.java` configures `S3Client` / `S3Presigner` / `SesClient` from `AwsProperties` with `region: ap-south-1` (`application.yml` line 137). Production env vars referenced in `application-prod.yml`: `AWS_REGION`, `AWS_S3_BUCKET`. `application-prod.yml` line 106 hardcodes `${AWS_REGION:ap-south-1}`.

So the choice is forced: **the application code only runs on AWS**, but the (incomplete) Terraform was started for GCP. Someone began a GCP migration and stopped. There are three paths:

- **Path A — finish AWS Terraform** (matches code, matches PRD). Recommended.
- **Path B — pivot to GCP** (rewrite `StorageService` to GCS, `EmailService` to SendGrid or SES-via-AWS, drop AWS SDK). 1–2 weeks of unrelated work.
- **Path C — keep code AWS, host on AWS, leave the GCP tfvars for a future migration**. Confusing to operators; not recommended.

**Recommendation: Path A.** The codebase, the PRD, the application properties, and the SDK choices are all AWS. The GCP tfvars are vestigial.

---

## 4. PRD §12.1 resource inventory — implemented vs not

| PRD AWS resource | Implemented in TF? | Notes |
|---|---|---|
| VPC `10.0.0.0/16` | **No** — vars exist in tfvars, no `aws_vpc` resource | |
| Public + private subnets, multi-AZ | **No** | |
| Internet Gateway, NAT Gateway | **No** | |
| Route tables / SG | **No** | |
| ECS Fargate cluster | **No** | |
| ECS service (2–10 tasks, autoscaling) | **No** | |
| ECS task definition with task role | **No** | |
| ECR repository | **No** — backend image URL points at GCP Artifact Registry | |
| ALB + target group + listener (HTTPS) | **No** | |
| ALB health check `/actuator/health` | **No** (endpoint exists in app, no LB to call it) | |
| ACM cert | **No** | |
| RDS Postgres 15 db.t3.medium Multi-AZ (prod) | **No** — Cloud SQL referenced instead | |
| RDS Single-AZ (staging) | **No** | |
| RDS subnet group + parameter group | **No** | |
| S3 data bucket + lifecycle | **No** — buckets named in tfvars only | |
| S3 frontend bucket | **No** | |
| CloudFront distribution + OAC | **No** | |
| API Gateway HTTP API | **No** | |
| SES (verified domain + DKIM) | **No** | |
| Secrets Manager (DB password, JWT keys, API keys) | **No** — secrets sit in plaintext tfvars | |
| CloudWatch log group | **No** | |
| CloudWatch alarms (CPU, mem, 5xx, RDS storage, wallet imbalance) | **No** | |
| X-Ray tracing | **No** in TF; no X-Ray dependency in `pom.xml` either | |
| IAM roles (task role, exec role, CI deploy role) | **No** | |
| Route 53 zones + records | **No** | |
| Backup plan / RDS automated backups | **No** | |

**Score: 0 of 27 PRD-specified AWS resources are provisioned by Terraform.**

---

## 5. Security review

Because no `.tf` exists, this is a forward-looking checklist for the AWS module that will be written.

### 5.1 Secrets management

- **Current state**: secrets are placeholders in committed tfvars (`db_password`, `openai_api_key`, `razorpay_key_secret`, `razorpay_webhook_secret`, `jwt_secret`). Even as placeholders, this normalises checking in real values later. **High risk.**
- **Recommendation**: remove all secret-bearing variables from tfvars. Create AWS Secrets Manager secrets via `aws_secretsmanager_secret` resources, then reference ARNs in ECS task definitions. The Java app already supports env-var injection (`application-prod.yml` lines 88, 94, 98, 102 — `${RAZORPAY_KEY_SECRET}`, etc.); just wire ECS to populate those env vars from Secrets Manager. Use `aws_secretsmanager_secret_version` with `lifecycle.ignore_changes = [secret_string]` so rotation outside TF doesn't drift state.
- **JWT RSA keys**: PRD says inject via Secrets Manager. `application-prod.yml` line 88 reads `APP_SECURITY_JWT_PRIVATE_KEY_PEM`. Make sure the secret value preserves PEM newlines (use the `secret_string` JSON wrapper, not raw).

### 5.2 Network isolation

- **Current state**: `vpc_cidr = 10.0.0.0/16` and `subnet_cidr = 10.0.1.0/24` declared in tfvars but no resources created.
- **Recommendation**:
  - Public subnets (ALB only) in `10.0.0.0/20` and `10.0.16.0/20` across two AZs (`ap-south-1a`, `ap-south-1b`).
  - Private subnets (ECS tasks, RDS) in `10.0.32.0/20` and `10.0.48.0/20`.
  - RDS in DB-only subnet group; SG allows 5432 only from ECS task SG.
  - ECS tasks in private subnets, no public IP; NAT Gateway for outbound (Razorpay, OpenAI, Recall.ai, SES).
  - Single NAT Gateway in staging (cost), HA NAT (one per AZ) in prod.

### 5.3 Encryption

- **In-transit**: ALB must be HTTPS-only, ACM cert. Force HTTP→HTTPS redirect. Backend ALB→ECS over HTTP within VPC is acceptable per PRD norms; consider mTLS for highly regulated paths (out of MVP scope).
- **At-rest**: RDS storage `storage_encrypted = true` with KMS CMK. S3 buckets: SSE-S3 minimum, SSE-KMS preferred. Secrets Manager secrets: KMS CMK. CloudWatch log group: `kms_key_id` set.
- **CMK rotation**: enable `aws_kms_key.enable_key_rotation = true`.

### 5.4 IAM / least-privilege

- Three role types needed:
  - **ECS task execution role**: `AmazonECSTaskExecutionRolePolicy` + `secretsmanager:GetSecretValue` scoped to the specific secret ARNs + `kms:Decrypt` on the secret CMK.
  - **ECS task role** (the app's runtime role): `s3:GetObject/PutObject/DeleteObject` on the data-bucket ARN only; `ses:SendEmail` for the configured `From` identity only; CloudWatch Logs `PutLogEvents`.
  - **CI deploy role** (GitHub OIDC): `ecr:Push`, `ecs:UpdateService`, `s3:Put` on frontend bucket only. Never `*` permissions.
- No long-lived AWS access keys. Use OIDC for GitHub Actions (`aws-actions/configure-aws-credentials@v4` with `role-to-assume`).

### 5.5 Public exposure

- Only the ALB (`internet-facing`) and CloudFront should have public IPs.
- ECS tasks: `assign_public_ip = false`.
- RDS: `publicly_accessible = false`, no public endpoint.
- S3: `block_public_acls`, `block_public_policy`, `ignore_public_acls`, `restrict_public_buckets` all `true`. Frontend bucket served only via CloudFront with OAC, not via public website hosting.
- Swagger UI is currently `permitAll` in `SecurityConfig` line 158 — fine for staging, restrict in prod via WAF rule or remove from prod profile.

### 5.6 WAF

- AWS WAF in front of CloudFront and/or ALB with the AWS managed rule set: `AWSManagedRulesCommonRuleSet`, `AWSManagedRulesKnownBadInputsRuleSet`, `AWSManagedRulesAmazonIpReputationList`. Rate-limit rule (1000 req / 5 min / IP) on `/api/v1/*/auth/*`. App also has its own `RateLimitFilter` so this is defence-in-depth.

---

## 6. Performance / scalability review

| Dimension | PRD ask | Recommendation in TF |
|---|---|---|
| Auto-scaling | ECS 2–10 tasks | `aws_appautoscaling_target` + `aws_appautoscaling_policy` on CPU 70% target tracking + ALB request-count target. Min 2 in prod, 1 in staging. |
| Multi-AZ | Multi-AZ RDS in prod | `multi_az = true` in prod, `false` in staging |
| RDS sizing | db.t3.medium (~2 vCPU, 4 GB) | OK for MVP. Burstable. Watch credit balance with a CloudWatch alarm. Migrate to `db.m6g.large` at ~20 concurrent sessions. |
| Connection pooling | Hikari size 20 (`application-prod.yml` line 29) | RDS Postgres 15 default `max_connections` is ~100 on db.t3.medium. With 10 ECS tasks × 20 connections = 200 → exceeds limit. **Either lower Hikari to 10 in prod, or use RDS Proxy.** Recommend RDS Proxy. |
| Read replicas | not in PRD | not needed at MVP scale |
| Caching | not in PRD; no Redis dependency in pom.xml | rate-limit bucket is in-process (`RateLimitFilter`) — does not survive task restarts and is not consistent across tasks. Add ElastiCache (Redis 7) **only** if you find this is biting; not P0. |
| CDN | CloudFront with OAC | mandatory for frontend bucket |
| Observability | CloudWatch logs + alarms + X-Ray | logs: yes. Alarms: see below. X-Ray: app does not have the X-Ray Java agent in `pom.xml`; defer to P1. Use CloudWatch RUM for frontend. |

### Recommended alarms

- ECS service: CPU > 80% for 5min; memory > 85% for 5min; running task count < desired for 2min.
- ALB: 5xx rate > 1% for 5min; target response time p95 > 2s for 5min; unhealthy host count > 0 for 1min.
- RDS: CPU > 80%; free storage < 20%; replica lag > 30s; database connections > 80% of max.
- App-level: wallet imbalance (`balance_paise < reserved_paise`) — query alarm via CloudWatch metric filter on log line; OpenAI failure rate > 10% over 15min.

---

## 7. Cost estimate (rough monthly USD, ap-south-1)

These are **list prices**, before any savings plans / reservations. Assumes ~500 interview sessions/month at MVP load.

### Staging (single-AZ, single small task)

| Resource | Spec | Approx cost |
|---|---|---|
| ECS Fargate | 1 task × 0.5 vCPU × 1 GB × 730h | $9 |
| ALB | 1 instance, light traffic | $20 |
| RDS Postgres 15 | db.t3.small Single-AZ, 20 GB gp3 | $30 |
| NAT Gateway | 1 × 730h + ~10 GB | $35 |
| S3 | ~5 GB | $1 |
| CloudFront | low traffic | $5 |
| SES | 5,000 emails | $0.50 |
| Secrets Manager | 8 secrets | $3 |
| CloudWatch logs + alarms | low | $5 |
| Route 53 | 1 zone | $0.50 |
| **Staging total** | | **~$110/month** |

### Production (multi-AZ, autoscaling 2–10 tasks)

| Resource | Spec | Approx cost |
|---|---|---|
| ECS Fargate | avg 3 tasks × 1 vCPU × 2 GB × 730h | $90 |
| ALB | 1 instance, ~5M reqs/month | $25 |
| RDS Postgres 15 | db.t3.medium Multi-AZ, 100 GB gp3, 7-day backup | $130 |
| RDS Proxy | always-on | $30 |
| NAT Gateway | 2 × 730h + ~50 GB | $80 |
| S3 | ~50 GB + lifecycle to IA | $3 |
| CloudFront | ~200 GB egress | $20 |
| SES | 50,000 emails | $5 |
| Secrets Manager | 12 secrets | $5 |
| CloudWatch logs (10 GB ingest) + alarms (~20) | | $20 |
| Route 53 | 1 zone | $0.50 |
| WAF | core rules + custom | $15 |
| **Production total** | | **~$425/month** |

### Variable / pass-through (excluded above, billed separately)

- OpenAI gpt-4o: ~$0.05–0.10 per interview at typical token usage → **$25–50/month for 500 sessions**.
- Recall.ai: ~$0.50 per bot-hour (varies by plan); 500 × 30min ≈ 250 bot-hours → **~$125/month**.
- Razorpay: 2% of GMV.

**Combined run-rate at MVP load: ~$550/month staging+prod cloud + ~$175/month variable AI/bot = $725/month**. Comfortably below ₹100k/month INR.

---

## 8. Prioritized remediation plan

Effort: **S** = ≤ 1 day, **M** = 2–4 days, **L** = > 1 week.

### P0 — must-fix before launch

| # | Item | Effort |
|---|---|---|
| 1 | **Decide AWS vs GCP**, write down rationale, remove the other path's vestiges. | S |
| 2 | Write `terraform/main.tf` + `versions.tf` + S3-backed `backend.tf` (state with DynamoDB lock). | S |
| 3 | Write VPC module: 2 public + 2 private subnets, IGW, NAT (single in staging, HA in prod), default routes, SGs. | M |
| 4 | Write RDS module: subnet group, parameter group with `rds.force_ssl=1`, db.t3.medium Multi-AZ in prod, encrypted, automated backups 7d. | M |
| 5 | Write ECS module: cluster, service, task definition (uses Secrets Manager), autoscaling 2–10, ALB + ACM + HTTPS. | M |
| 6 | Write ECR repo + GitHub OIDC role. Update `.github/workflows/ci.yml` to push to ECR (currently pushes to GHCR). | S |
| 7 | Write S3 buckets: data (versioning, lifecycle to IA at 90d, KMS), frontend (CloudFront OAC). | S |
| 8 | Write Secrets Manager resources for: DB password (random_password), JWT keypair, invite secret, OpenAI key, Razorpay key/secret/webhook secret, Recall API key/webhook secret. | M |
| 9 | Write SES module: domain identity + DKIM + DMARC record + verification. | S |
| 10 | Write CloudWatch log group + the 8 alarms above + SNS topic for `notification_email_address`. | S |
| 11 | Write Route 53 zone + records (api.interviewiq.in, app.interviewiq.in). | S |
| 12 | Replace tfvars secrets with non-secret-only variables. Move secrets into `terraform.tfvars.local` (gitignored) or pass via TF_VAR_ env vars from CI. | S |

**Sprint sum: ~10 dev-days, one engineer.**

### P1 — required within first month after launch

| # | Item | Effort |
|---|---|---|
| 13 | RDS Proxy in front of Postgres. | S |
| 14 | WAF in front of CloudFront + ALB with managed rule set. | S |
| 15 | CloudFront for backend ALB (cache `/actuator/health` short, no-cache `/api/*`). Optional. | S |
| 16 | API Gateway HTTP API (PRD calls for it; in practice ALB alone is fine). Decide whether to actually add. | M |
| 17 | CloudWatch RUM for frontend. | S |
| 18 | Backup vault + AWS Backup plan including RDS daily snapshots, retention 30d. | S |

### P2 — nice-to-haves

| # | Item | Effort |
|---|---|---|
| 19 | ElastiCache Redis for distributed rate-limit + future job queue. | M |
| 20 | X-Ray tracing (add `aws-xray-recorder-sdk-spring-boot` to pom + IAM permissions). | M |
| 21 | Secrets rotation Lambda for DB password (90-day). | M |
| 22 | Multi-region read replica + S3 cross-region replication (DR). | L |

---

## 9. Repo hygiene observations

- `terraform/` is the wrong layout for a future module. Recommend:

  ```
  terraform/
    modules/
      network/      # VPC, subnets, NAT, SGs
      data/         # RDS, S3
      compute/      # ECS, ALB, ACM
      observability/# CloudWatch alarms, log groups
      iam/          # task roles, CI role
      secrets/      # Secrets Manager
      dns/          # Route 53
    envs/
      staging/
        main.tf     # picks modules with staging vars
        backend.tf  # state in s3://interviewiq-tfstate-staging
        terraform.tfvars  # NON-SECRET vars only
      production/
        main.tf
        backend.tf
        terraform.tfvars
  ```

- Add a `terraform/.gitignore` containing `terraform.tfvars.local`, `*.tfstate*`, `.terraform/`.
- Add a `terraform/Makefile` with `init`, `plan`, `apply`, `destroy` targets keyed off `ENV=staging|production`.
- CI: a separate `.github/workflows/terraform-plan.yml` that runs `terraform plan` on PRs touching `terraform/**`, and `terraform-apply.yml` on push to main. Use the same OIDC role.

---

## 10. Recommended approach: AWS

**Stay on AWS.** Justification:

1. The Java code is bound to AWS SDK v2 (`S3Client`, `S3Presigner`, `SesClient` in `AwsConfig` and `AwsProperties`). Pivoting to GCP means rewriting `StorageService.java` (138 lines), `EmailService.java` (199 lines), and `AwsConfig.java`, plus replacing SES with SendGrid or Mailgun.
2. The PRD §12.1 explicitly specifies the AWS resource list. The company has presumably told customers / investors this.
3. `application-prod.yml` line 106 hard-defaults `AWS_REGION:ap-south-1`.
4. Razorpay (the only India-specific dependency) is region-agnostic; AWS ap-south-1 is the right home for an Indian SMB SaaS.
5. SES verified-domain flow is well-trodden; GCP requires a third-party transactional-email vendor. Less cognitive load to stay AWS.
6. The GCP tfvars are 26 lines × 2 env = 52 lines of throwaway. Throwing them away costs nothing.

**Action**: delete `terraform/envs/staging/terraform.tfvars` and `terraform/envs/production/terraform.tfvars`, replace with AWS-flavoured tfvars + the module structure above. Track in a single PR titled "infra: scaffold AWS Terraform — replaces vestigial GCP tfvars".

---

## 11. Bottom line

Two facts and one number:

- There is no Terraform module — only orphan tfvars for the wrong cloud.
- The Java code only runs on AWS; the PRD only describes AWS.
- ETA to a usable AWS staging environment: **~10 dev-days (P0 list above) for one focused engineer**.

Until P0 items 1–12 are done, the application cannot be deployed to any cloud and the question "how close are we to MVP" is bounded above by "infra readiness", which is currently **0%**.
