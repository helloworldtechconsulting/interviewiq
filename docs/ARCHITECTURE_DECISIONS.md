# InterviewEngine — Revised Architecture Decisions

**Date:** 1 August 2026
**Supersedes** the single-box design in `BUILD_PLAN.md` §2. The phase sequencing in §3 of that document still stands; only the infrastructure target changes.

---

## 0. You were right, and here's what I got wrong

I optimised for cost when the actual constraint is **"cost-minimal *for a product I can sell*."** Those are different problems. A platform that degrades under simultaneous users isn't a cheap product — it's a proof of concept with a price tag, and the first customer who runs 20 interviews in a morning finds out.

So: **autoscaling is a requirement, not an upgrade.** Everything below is rebuilt on that basis. The cost goes from ~$26 to **~$97/mo all-in** — which is still **₹16.6 per interview at 500/month, inside your <₹20 target**, and break-even moves to ~83 interviews/month.

Minimum cost still applies. It just now means "cheapest way to get real autoscaling," not "cheapest thing that runs."

---

## 1. Compute: ECS Fargate, not EKS *(you asked what I'd suggest)*

**Recommendation: ECS Fargate on ARM64.**

### Why not EKS

| | Cost | Ongoing burden |
|---|---|---|
| **EKS** | **$73/mo control plane before a single pod runs**, plus compute on top | Cluster version upgrades ~3×/yr (AWS supports each release ~14 months), ingress controller, cert-manager, IRSA, node group management |
| **ECS Fargate** | **$0 control plane.** Pay only for running tasks | None. No cluster, no nodes, no OS |

The EKS control plane alone would cost more than the *entire* compute budget of the alternative — for one containerised monolith and a static SPA. Kubernetes earns its overhead when you have many services, many teams, or a genuine need for the operator ecosystem. You have one Spring Boot app.

Fargate also directly answers your OS-patching requirement (§6) — there is no OS. AWS patches the platform.

### The honest counter-argument

Hello World Tech Consulting lists **GKE/EKS/ArgoCD** as service offerings. If InterviewEngine doubles as a **reference implementation and sales asset** for the consulting business, running it on EKS with ArgoCD has value beyond the product itself — you'd be demoing your own stack.

That's a legitimate business reason to spend $73/mo, and it's your call, not a technical question. My advice: **not yet.** Ship on Fargate, get paying customers, and if you later want the EKS showcase, the app is already containerised — migration is writing manifests, not rewriting code. Don't pay the Kubernetes tax before you have a Kubernetes problem.

### Also considered: AWS App Runner

Simplest autoscaling container service, ~$50/mo floor. **Rejected: WebSocket support is limited**, and your entire interview flow depends on a long-lived WebSocket. Disqualifying.

### Sizing

- **ARM64 (Graviton) Fargate** — ~20% cheaper than x86, and Java 21 runs on it without changes. Free money.
- **0.5 vCPU / 1 GB per task**, min **2 tasks across 2 AZs** (2 is the minimum for real availability — one task means one deploy or one crash is an outage), max 6.
- Target-tracking autoscaling on **ALBRequestCountPerTarget** as the primary metric with CPU 65% as secondary. Request-count tracks your actual load shape better than CPU, because interview sessions are IO-bound, not CPU-bound.
- 2 tasks × ARM ≈ **$29/mo**; you only pay for scale-out during peaks.

### ⚠️ The one thing that will bite you: autoscaling + stateful WebSockets

This is the genuinely tricky part of running live interviews on autoscaling Fargate, and it's easy to get wrong. The failure mode is **a candidate's interview dying mid-session during a scale-in event** — which is about the worst bug this product could have.

Three things are needed together:

1. **ALB sticky sessions** so a candidate's WebSocket and REST calls land on the same task.
2. **Deregistration delay (connection draining) set to 3600s** — the maximum interview length — so a task being removed finishes its live interviews before terminating.
3. **ECS task scale-in protection.** The app calls the scale-in protection API when a session goes `IN_PROGRESS` and releases it on `COMPLETED`. Without this, ECS will happily kill a task holding four live interviews.

Also: your in-memory WebSocket session registry is **only correct at one instance**. With sticky sessions plus scale-in protection it works at N instances *for the WebSocket itself*, but any cross-task broadcast (e.g. an admin force-terminating a session) will silently no-op. Document the constraint in the code; add Redis pub/sub only if you actually need cross-task messaging.

Budget real time for this. It's a day of work and a week of subtle bugs if rushed.

---

## 2. Load balancing: ALB yes, API Gateway no *(you asked me to elaborate)*

**Retract Caddy entirely** — it only made sense in the single-box design where we needed free TLS on an EC2 instance. With Fargate, ALB replaces it.

**ALB (~$19/mo) is non-negotiable.** It provides TLS via ACM (free certificates), native WebSocket support with connection draining, health-check-driven task replacement, the `ALBRequestCountPerTarget` metric that drives autoscaling, and an attachment point for WAF.

### Why not put API Gateway in front of it

You asked what the benefit actually is. Honest answer: **for your architecture, close to zero — and it makes one thing materially worse.**

What API Gateway HTTP API would add:

| Feature | Do you need it? |
|---|---|
| API keys + usage plans | No — you authenticate with JWT |
| Per-route throttling | Already have Bucket4j; and your real need is *per-tenant* throttling, which API GW can't do without custom authorisers |
| Request/response transformation, schema validation | Spring already does this |
| WAF integration | ALB has this too |

What it costs:

- $1.00 per million requests, plus an extra network hop of latency.
- **The disqualifier: API Gateway's WebSocket API is a completely separate product from its HTTP API.** You cannot serve REST and WebSocket from one HTTP API. The WebSocket API uses a *callback* model — your backend posts messages to connection IDs through the `@connections` management API; it does **not** hold a socket to your application. Adopting it would mean:
  - rewriting Spring's `TextWebSocketHandler` into a stateless request/response model,
  - storing connection state externally (DynamoDB),
  - paying $1.00/million messages **plus $0.25/million connection-minutes** — and a 60-minute interview is 60 connection-minutes per candidate.

**ALB handles WebSocket transparently.** Your Spring handler just works.

**Verdict: ALB only.** The $1.50/mo saving is incidental — the real reason is that API Gateway would actively degrade the WebSocket architecture that the whole product depends on.

---

## 3. Database: RDS *(your instinct was correct)*

**Recommendation: RDS PostgreSQL 16, db.t4g.micro, Single-AZ, ~$16/mo including 20GB gp3.**

Containerised Postgres on EBS was only defensible in a single-box world. With an autoscaling app tier it becomes the bottleneck and the single point of failure the moment Fargate scales — and Fargate has no persistent local storage, so you'd need a stateful task on EFS, which is slower and more fragile than RDS while saving ~$16/mo. Not worth it.

RDS also gives you **storage autoscaling** (enable it), automated backups with point-in-time recovery, and a one-parameter path to Multi-AZ later. Per your own architecture doc: **move to Multi-AZ at >20 paying customers** (~2× the cost). That's the right trigger.

### ⚠️ Connection-pool exhaustion — a real gotcha with autoscaling

`db.t4g.micro` supports roughly **85 max connections**. Your production config sets HikariCP to **20 connections per instance**. At 6 tasks that's 120 connections requested against an 85 limit — the database starts refusing connections *precisely when you're under peak load*, which is exactly when you can least afford it.

Fix, in order of preference:

1. **Cap HikariCP at 8 per task** (6 tasks × 8 = 48, comfortably under the limit). Free. Do this first.
2. Add **RDS Proxy** (~$11/mo) only if you observe genuine connection pressure. It's the right answer at scale, but don't buy it pre-emptively.

---

## 4. Container registry: ECR, not GHCR *(reversing my earlier call)*

I recommended GHCR when we were deploying by SSH-ing into a box and pulling manually. On Fargate that reverses:

| | ECR | GHCR |
|---|---|---|
| Auth from Fargate | **Native IAM task role** | Requires `repositoryCredentials` → a Secrets Manager secret ($0.40/mo) holding a GitHub PAT |
| Pull path | **In-region, fast** | Across the public internet on **every task start** — including every autoscale-out event, not just deploys |
| Failure mode | AWS-internal | GitHub outage or an expired PAT blocks task launches |
| Cost | ~$0.30/mo with a lifecycle policy keeping 10 images | $0.40/mo for the secret, plus pull egress |
| Extras | Free basic image scanning, lifecycle policies | Separate tooling |

**ECR is cheaper in practice, faster, and removes an external dependency from your scaling path.** Switch to it.

---

## 5. Security: fix the app first, then buy proportionately

You want "decent security, not overcompliant." Here's the honest prioritisation.

### The realistic attack on you is not AWS infrastructure — it's your application

The three highest-risk findings from the audit are all application-level, and all free to fix:

1. **Webhook HMAC verification fails open.** When the secret is blank, `WebhookService` skips verification entirely on a `permitAll` endpoint. Anyone who finds `/api/v1/webhooks/razorpay` can forge `payment.captured` and **mint unlimited wallet credit**. This is precisely the "someone makes a joke of my efforts" scenario, and it costs nothing to close.
2. **`upload-confirm` accepts an arbitrary client-supplied S3 key** with no ownership check — cross-tenant object access.
3. **No CORS + refresh token in `localStorage`** — any XSS becomes full account takeover.

**Fix Phase 1 before buying a single security service.** It's free and it removes more real risk than anything below.

### Then, a proportionate AWS baseline

**Free, do all of it:**
- RDS in private subnets, security group accepts only the Fargate SG.
- S3: Block Public Access on, SSE-S3, scoped bucket policies, access via pre-signed URLs only.
- Secrets in **SSM Parameter Store SecureString** (free tier is sufficient for your ~8 secrets).
- **GitHub Actions OIDC → IAM role.** No long-lived AWS keys anywhere.
- ECR basic image scanning; CloudTrail (first trail free); Dependabot; OWASP dependency-check in CI.

**Worth paying for (~$17/mo total):**
- **AWS WAF on the ALB** with the managed Core + Known-Bad-Inputs rule sets — ~$9/mo. This is your defence against the automated scanning and injection attempts that hit any public endpoint within days of DNS propagating. Directly addresses your concern.
- **GuardDuty** — ~$8/mo. The "am I already compromised?" detector: crypto-mining on your tasks, credential exfiltration, contact with known-bad IPs. Highest signal-per-rupee security service AWS sells at your scale.

### One deliberate trade-off, stated plainly

Textbook practice puts Fargate tasks in **private subnets with a NAT Gateway** for outbound calls to OpenAI/Razorpay. NAT Gateway is **~$32/mo** — a third of your entire infrastructure bill.

**My recommendation: public subnets with `assignPublicIp: ENABLED`, and a security group that accepts inbound traffic *only* from the ALB's security group.** Tasks can reach the internet outbound; nothing on the internet can reach them directly. RDS stays private regardless.

This is a well-trodden cost pattern and is secure when the security groups are right. It is *slightly* less defence-in-depth than private + NAT, and some enterprise security questionnaires ask about it specifically. At your stage the $32/mo is better spent elsewhere. Revisit if you land a customer whose procurement team asks.

*(A NAT instance on t4g.nano would be ~$3/mo — but it reintroduces an OS you have to patch, which you've explicitly ruled out.)*

**Dropping VPC interface endpoints is fine.** They're a cost-optimisation for private-subnet architectures, not a security control. Keep the free S3 *gateway* endpoint.

---

## 6. OS patching: eliminated

Fargate has no OS you can access or are responsible for. AWS patches the underlying platform. Your only remaining patch surface is the base image in your Dockerfile — handled by rebuilding on a schedule, which CI already does.

This requirement essentially decides the compute question on its own: it rules out EC2, and it rules out EKS with EC2 node groups.

---

## 7. Chrome-only + face detection — one correction

You chose to target Chrome only to unlock more browser APIs. Two consequences, one of which isn't what you'd expect:

**Good news:** Chrome-only makes the **Web Speech API viable** for speech-to-text — free, no model download, no CPU cost on the candidate's machine. Given §4 of the AI analysis (nothing keeps data in India anyway once an LLM is involved), the incremental residency cost of using it is smaller than I framed earlier. **Recommendation: ship Web Speech API now**, keep on-device Whisper as a Phase 2 option if a customer demands it. This removes the biggest schedule risk from Phase 3.

**The correction:** the browser **`FaceDetector` API is behind the "Experimental Web Platform Features" flag** on desktop Chrome — it is *not* enabled by default. Candidates will not enable a Chrome flag to take an interview. So "Chrome-only" does **not** give you free face detection.

Realistic path to multi-face detection: **MediaPipe Face Detection or face-api.js** — a ~2–6 MB WASM + model download running on-device. Free in dollars, costs first-load time and some CPU. That's a real feature, just not a free one. My recommendation stands: **ship `tab_switch` + `camera_off` for MVP** (both are a few lines and genuinely free), add MediaPipe face detection in Phase 2 when you know whether customers actually care.

**One refinement:** gate on **Chromium**, not Chrome. Edge, Brave, Opera and Arc all support the same APIs. "Chromium-based browser required" captures meaningfully more users than "Chrome required" for zero extra work. Put the requirement in the invite email as well as the room's preflight screen — a candidate discovering it when they click the link is a support ticket.

---

## 8. Revised cost

| Item | $/mo |
|---|---|
| ALB | 19.00 |
| Fargate ARM64 — 2 × (0.5 vCPU / 1 GB) baseline, scale to 6 | 29.00 |
| RDS db.t4g.micro Single-AZ + 20 GB gp3 | 16.00 |
| S3 — recordings (7-day expiry) + assets | 2.50 |
| CloudFront + ACM | 2.00 |
| CloudWatch + alarms | 3.00 |
| Data transfer | 2.00 |
| Route 53 | 0.50 |
| SES | 0.50 |
| ECR | 0.30 |
| SSM Parameter Store | 0.00 |
| **Subtotal — infrastructure** | **~$75** |
| AWS WAF | 9.00 |
| GuardDuty | 8.00 |
| **Subtotal — with security** | **~$92** |
| LLM (nano generation + Haiku/mini evaluation) | ~5.00 |
| **All-in** | **~$97/mo ≈ ₹8,300** |

A 1-year Compute Savings Plan on Fargate takes this to **~$91**.

**Unit economics at 500 interviews/month: ₹16.6 per interview against ₹100 revenue — ~83% gross margin, inside the PRD's <₹20 target. Break-even ~83 interviews/month.**

That's ~3.7× the single-box design, and it buys you: no OS to patch, automatic scale-out under load, no single point of failure, managed database with point-in-time recovery, zero-downtime deploys, and a WAF. That is the difference between a demo and something you can put a contract behind.

---

## 9. What changes in the build plan

The 9-phase sequence in `BUILD_PLAN.md` §3 holds. Phase 4 (Infrastructure) is rewritten:

**Terraform modules:** `network` (VPC, 2 public + 2 private subnets across 2 AZs, SGs, S3 gateway endpoint) · `ecr` · `ecs` (cluster, ARM64 task definition, service, autoscaling policies, **scale-in protection IAM permissions**) · `alb` (target group, HTTPS listener, stickiness, 3600s deregistration delay, WAF association) · `rds` (Single-AZ, storage autoscaling, private subnet group) · `storage` (3 buckets, lifecycle, SSE-S3) · `edge` (CloudFront, ACM, Route 53) · `email` (SES, DKIM/SPF/DMARC) · `secrets` (SSM SecureString) · `security` (WAF web ACL, GuardDuty) · `monitoring` (alarms, SNS) · `iam` (GitHub OIDC provider + deploy role, ECS task + execution roles) · `envs/staging`, `envs/production`.

**CD pipeline:** GitHub Actions → OIDC assume role → build ARM64 image → push ECR → `aws ecs update-service --force-new-deployment` → wait for stable → automatic rollback on failed health checks. **Zero static credentials.**

**Two additions to Phase 3** (the interview room), both from §1 above:
- ECS task scale-in protection wired to session lifecycle.
- WebSocket reconnect-and-resume that survives a task replacement.

---

## 10. Decisions log

| # | Decision | Status |
|---|---|---|
| 1 | Per-workflow AI model config — extended to per-workflow **vendor** | ✅ Agreed, extended |
| 2 | Video at 480p / 600 kbps (~135 MB per 30-min interview) | ✅ Agreed |
| 3 | Proctoring: `tab_switch` + `camera_off` for MVP | ✅ Agreed — but see §7, `FaceDetector` is flag-gated; MediaPipe in Phase 2 |
| 4 | Browser support: Chromium-based only | ✅ Agreed — unlocks free Web Speech API STT |
| 5 | Compute: **ECS Fargate ARM64**, autoscaling 2→6 | 🆕 Revised from single-box EC2 |
| 6 | **EKS rejected** for MVP — $73/mo control plane, no benefit at one service | 🆕 Open to revisit for consulting-portfolio reasons |
| 7 | **ALB yes, API Gateway no** — API GW's WebSocket API would force an app rewrite | 🆕 Caddy retracted |
| 8 | **RDS Single-AZ**, Multi-AZ at >20 customers; cap HikariCP at 8/task | 🆕 Revised |
| 9 | **ECR**, not GHCR | 🆕 Reversed |
| 10 | Fargate in public subnets + strict SG; **no NAT Gateway** (saves $32/mo) | 🆕 Deliberate trade-off |
| 11 | **WAF + GuardDuty** at launch (~$17/mo) | 🆕 Added |
| 12 | OS patching eliminated by Fargate | ✅ Requirement met |
| 13 | STT: **Web Speech API** now, on-device Whisper deferred | 🆕 Changed — Chromium-only makes this viable |
| 14 | **PII redaction before every LLM call** | 🆕 Free, and the strongest residency control available |
| 15 | PRD's "all data processed in ap-south-1" claim must be **amended** | 🆕 No LLM vendor, including Bedrock, keeps inference in India |
