# Bringing up InterviewEngine on Google Cloud

Everything past the bootstrap is Terraform. This file covers only the parts
Terraform cannot create for itself — the project, the credentials, and the
bucket its own state lives in — plus the order to run things in.

Nothing here has been executed. It is written from what
`terraform/envs/*` and `.github/workflows/cd.yml` actually require, so the
variable names are real, but every step needs your account.

---

## Why GKE and not something else

Settled in Architecture v4.0 §6 and worth restating because it drives the
sizing below: **one zonal GKE control plane is free.** EKS charges ~$73/month
for the control plane alone, which is most of the target infrastructure budget
(~$44/month) before a single node runs. That is the entire cost argument.

The clusters below are therefore **zonal, not regional**. A regional control
plane is chargeable and buys availability this product does not yet need.

---

## 0. Before you touch GCP

**Buy the domain.** `interviewengine.ai` — the code now assumes it everywhere.
Then move DNS to Cloudflare (add the site in Cloudflare, then set the two
Cloudflare nameservers at your registrar). Cloudflare is the edge in this
architecture — DNS, TLS, CDN and WAF, all on the free tier — and its zone must
exist before Terraform's `edge` module can create records.

Nameserver propagation is the long pole. Start it first; it can run while you
do everything else.

**Tools:** `gcloud`, `terraform` (≥1.6), `kubectl`, `gke-gcloud-auth-plugin`.

```bash
brew install --cask google-cloud-sdk
brew install terraform kubernetes-cli
gcloud components install gke-gcloud-auth-plugin
```

---

## 1. Project and billing

Two projects, so a mistake in staging cannot bill or break production.

```bash
gcloud auth login

gcloud projects create interviewengine-staging --name="InterviewEngine Staging"
gcloud projects create interviewengine-prod    --name="InterviewEngine Production"

# Attach billing — free credits live on the billing account, not the project.
gcloud billing accounts list
gcloud billing projects link interviewengine-staging --billing-account=XXXXXX-XXXXXX-XXXXXX
gcloud billing projects link interviewengine-prod    --billing-account=XXXXXX-XXXXXX-XXXXXX
```

**Set a budget alert now, not later.** Free credits hide overspend until they
run out, and the failure mode is a surprise invoice rather than a warning.
Billing → Budgets & alerts → 50% / 90% / 100% of your monthly target.

> **Worth an afternoon before you commit:** Hello World Tech Consulting is a
> cloud consultancy, and Google runs *partner* credit programmes separately
> from the startup ones. Arch v4.0 §6 flags this as potentially the difference
> between $1,000 and $100,000 of credit. Check partner eligibility before
> burning the self-service allocation.

---

## 2. Enable the APIs

Terraform will fail with a permission error rather than a helpful one if any
of these is missing. Run for **both** projects:

```bash
for P in interviewengine-staging interviewengine-prod; do
  gcloud services enable \
    compute.googleapis.com \
    container.googleapis.com \
    sqladmin.googleapis.com \
    servicenetworking.googleapis.com \
    secretmanager.googleapis.com \
    iamcredentials.googleapis.com \
    sts.googleapis.com \
    cloudresourcemanager.googleapis.com \
    --project="$P"
done
```

`servicenetworking` is the one people miss — the platform module gives Cloud
SQL a **private IP** (`google_service_networking_connection`), so the database
has no public endpoint. Without that API the SQL instance fails to create.

---

## 3. Terraform state buckets

Chicken-and-egg: the backend bucket must exist before `terraform init` can
run, so it is created by hand. Names are already fixed in
`terraform/envs/*/main.tf`.

```bash
gcloud storage buckets create gs://interviewengine-tfstate-staging \
  --project=interviewengine-staging --location=asia-south1 --uniform-bucket-level-access
gcloud storage buckets update gs://interviewengine-tfstate-staging --versioning

gcloud storage buckets create gs://interviewengine-tfstate-production \
  --project=interviewengine-prod --location=asia-south1 --uniform-bucket-level-access
gcloud storage buckets update gs://interviewengine-tfstate-production --versioning
```

**Versioning is not optional.** Terraform state is the only record of what
exists; a corrupted or truncated write with no previous version means
reconstructing production by hand.

---

## 4. Cloudflare — R2 and the API token

Object storage is **Cloudflare R2 on every cloud**, deliberately. R2 charges
zero egress, and recording playback is the only meaningful egress this product
generates (Arch v4.0 §3). Keeping storage at Cloudflare also means it does not
move when the compute does.

1. R2 → create buckets `interviewengine-staging` and `interviewengine-prod`
2. R2 → Manage API Tokens → create a token with **Object Read & Write**, scoped
   to those buckets. Note the access key ID, secret, and the S3 endpoint
   (`https://<account-id>.r2.cloudflarestorage.com`).
3. My Profile → API Tokens → create a token with **Zone:DNS:Edit** and
   **Zone:Zone Settings:Edit** on `interviewengine.ai`, for the `edge` module.
4. Note the **Zone ID** from the zone's overview page.

**Set the R2 lifecycle rule to 7 days**, matching the retention the
architecture assumes. Interview recordings are video of identifiable people
answering questions about themselves; storage that outlives its purpose is a
liability, not an asset.

---

## 5. Deployer identity — Workload Identity Federation

`cd.yml` authenticates with **no long-lived key**. GitHub's OIDC token is
exchanged for short-lived credentials, so a leaked log cannot yield a reusable
cloud credential.

Run per project (shown for staging):

```bash
PROJECT=interviewengine-staging
PROJECT_NUMBER=$(gcloud projects describe $PROJECT --format='value(projectNumber)')
REPO=helloworldtechconsulting/interviewiq     # the GitHub path, whatever it is now

gcloud iam service-accounts create github-deployer \
  --display-name="GitHub Actions deployer" --project=$PROJECT

for ROLE in roles/container.admin roles/cloudsql.admin roles/compute.networkAdmin \
            roles/iam.serviceAccountUser roles/storage.admin roles/secretmanager.admin; do
  gcloud projects add-iam-policy-binding $PROJECT \
    --member="serviceAccount:github-deployer@$PROJECT.iam.gserviceaccount.com" \
    --role="$ROLE" --condition=None
done

gcloud iam workload-identity-pools create github --location=global --project=$PROJECT

gcloud iam workload-identity-pools providers create-oidc github \
  --location=global --workload-identity-pool=github --project=$PROJECT \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository=='$REPO'"

gcloud iam service-accounts add-iam-policy-binding \
  github-deployer@$PROJECT.iam.gserviceaccount.com --project=$PROJECT \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository/$REPO"

echo "projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/providers/github"
```

The `--attribute-condition` pinning the repository is the security boundary.
Without it **any** GitHub repository on the internet can mint a token for your
service account.

---

## 6. GitHub configuration

Settings → Secrets and variables → Actions.

**Variables** (not secret):

| Name | Value |
|---|---|
| `GCP_PROJECT_ID` | `interviewengine-prod` |
| `GCP_REGION` | `asia-south1` |
| `GCP_ZONE` | `asia-south1-a` |
| `DNS_ZONE_NAME` | `interviewengine.ai` |
| `CLOUDFLARE_ZONE_ID` | from step 4 |
| `INGRESS_IP` | *leave until step 8* |
| `SPA_ORIGIN_HOSTNAME` | R2 public bucket hostname |
| `R2_ENDPOINT` | `https://<account>.r2.cloudflarestorage.com` |
| `R2_BUCKET` | `interviewengine-prod` |

**Secrets:** `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`,
`CLOUDFLARE_API_TOKEN`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`,
`GRAFANA_ADMIN_PASSWORD`.

**Environments** → create `staging` (no rules) and `production` (**required
reviewers — this is the approval gate**).

`asia-south1` (Mumbai) is not arbitrary: PRD §10 commits to candidate data at
rest in India.

---

## 7. First apply — staging

Run it locally the first time. Watching the plan matters more than automating
it on a cluster that does not exist yet.

```bash
cd terraform/envs/staging
gcloud auth application-default login
terraform init
terraform plan -out=tfplan \
  -var="project_id=interviewengine-staging" \
  -var="image=ghcr.io/<owner>/<repo>/interviewengine-backend:latest" \
  -var="zone_name=interviewengine.ai" \
  ...
terraform apply tfplan
```

Expect **15–25 minutes**. The GKE cluster and the Cloud SQL private-IP peering
are the slow parts; nothing is wrong if it sits on either for ten minutes.

The connection budget is already checked at plan time — staging is sized for
`db-f1-micro` (pools of 5 and 3 against a 50-connection ceiling), so if you
change replica counts and the budget check reports, believe it.

---

## 8. The ingress IP, then DNS

Ordering trap: the ingress controller allocates its IP during apply, so DNS
cannot be pointed until after the first apply.

```bash
gcloud container clusters get-credentials interviewengine-staging \
  --zone asia-south1-a --project interviewengine-staging
kubectl get svc -n ingress-nginx   # take EXTERNAL-IP
```

Put that in `INGRESS_IP` and re-apply so the `edge` module creates the
records. In Cloudflare, `api-staging` and `app-staging` should be
**proxied** (orange cloud) — that is what puts the WAF and DDoS protection in
front of the origin.

---

## 9. Verify before trusting it

```bash
kubectl get pods -n interviewengine
kubectl rollout status deployment/interviewengine-web -n interviewengine
curl -sS https://api-staging.interviewengine.ai/actuator/health/readiness
```

Then check the two things that have actually broken here before:

- **Probes.** `/actuator/health/readiness` must return **200**, not 401 or 404.
  Both failure modes have happened, and both stall a rollout indefinitely.
- **The drain.** `kubectl delete pod` on a web pod should show readiness go
  503 while liveness stays 200. That mechanism is what stops a deploy cutting
  off live interviews (Arch v4.0 §5.2), and it is the single easiest thing
  here to get wrong.

---

## 10. Production

Identical, with `terraform/envs/production` and the prod project. Do it only
after staging has run something real — the point of staging is to be wrong
first.

---

## Still outstanding before real candidates

- **SMTP provider.** Nothing sends mail until one is chosen and
  `MAIL_WEBHOOK_PROVIDER` / `MAIL_WEBHOOK_SECRET` are set (INTIQ-103). Domain
  verification with DKIM, SPF and DMARC on `interviewengine.ai` is
  launch-blocking.
- **`app.security.google.client-id`** — a new OAuth client for the new domain.
  The old one, if any, is bound to the old origin.
- **Alertmanager has no destination.** Alerts fire into its UI and nowhere
  else (INTIQ-101).
- **The load test has never run** (INTIQ-99). `interviews-per-pod` is still
  the unmeasured guess the autoscaling is built on.
- **Data residency is not yet proven.** `asia-south1` covers compute and
  storage; it says nothing about where the LLM provider runs inference. That
  gap is real and is the one most likely to matter legally.
