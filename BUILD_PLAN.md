# InterviewEngine — Cost-Minimised Build Plan

**Date:** 1 August 2026
**Confirmed architecture:** April 2026 (in-browser WebRTC). The March 2026 PRD is superseded wherever the two conflict; it remains authoritative for billing rules, evaluation scoring, NFRs and personas.
**Confirmed infra target:** single-box, cheapest that works.
**Standing constraint:** minimum cost, treated as a design input everywhere — not just an infra line item.

---

## 0. Correction to my earlier estimate, and one finding that changes the AI budget

**Infra.** I quoted "~$15/mo" for the single-box option. Priced properly it lands at **~$21/mo on-demand, ~$17/mo with a 1-year Compute Savings Plan** — still roughly a third of the documented architecture's ~$60/mo, but I was optimistic. Numbers in §2.

**AI — this one matters.** The specs pin **GPT-4o**, and `application.yml:73` sets `model: gpt-4o`. As of mid-2026 GPT-4o is **no longer listed on OpenAI's pricing page**; the current lineup is GPT-5.5, GPT-5.4, and the 5.4 mini/nano tiers. Two independent pricing trackers confirm this. So the model is at best legacy and at worst on a retirement path — you need to move regardless, and moving *down* the tier ladder is nearly free money:

| Model | Input /1M | Output /1M | Est. cost per interview | 500 interviews/mo |
|---|---|---|---|---|
| GPT-5.4 (≈ the old 4o tier) | $2.50 | $15.00 | ~₹5.3 | ~$30 |
| **GPT-5.4-mini** | $0.75 | $4.50 | **~₹1.6** | **~$9** |
| **GPT-5.4-nano** | $0.20 | $1.25 | **~₹0.4** | **~$2.5** |

*(Assumes ~6k input + ~3k output tokens per interview across question generation and evaluation, extrapolating the PRD's own token estimate.)*

**Recommendation: nano for question generation, mini for evaluation.** Question generation is a templated, structured-output task where nano is ample. Evaluation is the one place where judgement quality is a business KPI — the PRD commits to **Pearson r > 0.65 between AI score and hire decisions**, so don't cheap out there blindly. Run mini, and validate against your first 50 interviews before considering nano. Combined: **~$5/mo instead of $30** — and it drops your all-in cost per interview from the doc's ~₹15 to **~₹3.5**, comfortably inside the <₹20 target.

Make the model a per-workflow config property (`app.ai.question-model` / `app.ai.evaluation-model`), not one global `spring.ai.openai.chat.options.model`. It's a 20-minute change that lets you re-tier without a redeploy.

---

## 1. Three decisions I need from you before Phase 3

These are genuine forks with cost and compliance consequences. I've given a recommendation for each, but they're yours to call.

### 1.1 Speech-to-text: Web Speech API vs on-device Whisper — **there is a data-residency conflict**

The April spec says browser Web Speech API for ASR. Two problems:

- **Chrome's `SpeechRecognition` streams the candidate's audio to Google's servers for transcription.** Your PRD commits to "all data stored and processed in AWS ap-south-1 (Mumbai)". These two statements cannot both be true. This is a compliance issue, not a technical one, and it's on the candidate's *voice* — the most sensitive data in the product.
- **Firefox does not implement `SpeechRecognition` at all**, yet the PRD lists Firefox 100+ as supported. Safari's support is partial and inconsistent.

| Option | Cost | Residency | Notes |
|---|---|---|---|
| **Web Speech API** (as specced) | ₹0 | ✗ audio leaves region | Chrome/Edge only. Needs a privacy-policy rewrite and a browser gate. |
| **On-device Whisper** via `transformers.js` in a Web Worker | ₹0 | ✓ never leaves the device | ~40MB one-time model download; needs a mid-range machine. Works in all modern browsers. |
| Server-side Whisper API | ~₹15/interview | ✓ | **Blows the budget** — 15% of revenue on transcription alone. Rejected. |

**Recommendation: on-device Whisper (`whisper-base`, quantised) with Web Speech API as an opt-in fast path.** It is the only option that is both free *and* keeps you honest on data residency, and it fixes Firefox. Budget an extra 2–3 days for the Web Worker plumbing and a loading state for the first-visit model download. Ship a typed-answer fallback either way — microphones fail.

### 1.2 Record video, or audio only?

Spec says video, 500MB cap, 7-day S3 lifecycle. Video at 720p runs ~225MB per 30-minute interview; audio-only Opus at 64kbps runs ~15MB — **a 15× reduction** in storage and egress.

Nothing in the evaluation pipeline consumes the video. It exists for a human to spot-check. **Recommendation: audio-only by default, video as a per-employer toggle in Phase 2.** If you want video now, cap at 480p/600kbps (~135MB) rather than 720p.

### 1.3 Proctoring depth for MVP

`tab_switch` (via `visibilitychange`) and `camera_off` (via track `onmute`/`onended`) are a few lines each and free. **Multi-face detection is not** — the browser `FaceDetector` API is Chrome-only and behind a flag, so the real option is a bundled ML model, which is another 2–5MB download and real CPU cost on the candidate's machine.

**Recommendation: ship tab_switch + camera_off only.** Defer multi-face. The API contract already carries a `multi_face` event type, so adding it later is additive, not breaking.

---

## 2. Target infrastructure — ~$21/mo

### 2.1 Shape

```
                    Route 53  (interviewengine.ai)
                         │
        ┌────────────────┴────────────────┐
        │                                 │
  app.interviewengine.ai                api.interviewengine.ai
        │                                 │
  CloudFront + ACM                   Elastic IP
        │                                 │
   S3 (static SPA)              EC2 t4g.small — public subnet
                                          │
                                  ┌───────┴────────┐
                                  │  Caddy         │ auto-TLS via Let's Encrypt, free
                                  │  Spring Boot   │ REST + WebSocket, one JVM
                                  │  Postgres 16   │ container, data on EBS
                                  └───────┬────────┘
                                          │ instance profile — no static keys
                                  S3 · SES · SSM Parameter Store
```

### 2.2 What this deliberately does *not* use, and why

| Dropped | Saved/mo | Reasoning |
|---|---|---|
| **ALB** | ~$18 | Caddy terminates TLS on the box for free and proxies WebSocket natively. An ALB buys you health-check-driven replacement — worth it at 3+ instances, not at 1. |
| **ECS Fargate** | ~$28 | `docker compose up` on one box. Same containers, same image, no orchestration bill. |
| **RDS** | ~$14 | Postgres in a container on a dedicated EBS volume. You give up automated PITR — mitigated by nightly `pg_dump` to S3 (§2.4). |
| **API Gateway** | ~$1.50 | Buys nothing in front of a single origin. Its throttling is replaced by Caddy `rate_limit` + the existing Bucket4j filter. |
| **VPC interface endpoints** | ~$21 | *The April doc understates these.* It budgets $2/mo for "VPC endpoints, no NAT" — but interface endpoints are ~$7.20/mo **each**, and S3/ECR/SSM/Logs would be four of them. A public subnet with an Elastic IP needs neither NAT nor endpoints. (S3's *gateway* endpoint is free and worth keeping if you later move to a private subnet.) |
| **ECR** | ~$0.20 | You already push to GHCR, which is free for private repos at this volume. |

### 2.3 Line items

| Item | $/mo |
|---|---|
| EC2 t4g.small (2 vCPU / 2 GB, Graviton), on-demand | 12.10 |
| EBS gp3 — 8 GB root + 10 GB data | 1.70 |
| S3 — recordings (transient, 7-day expiry) + assets | 2.50 |
| CloudFront + ACM — SPA delivery, free TLS | 2.00 |
| Route 53 hosted zone | 0.50 |
| SES (~5k emails) | 0.50 |
| CloudWatch — 2 alarms + SNS email | 0.30 |
| SSM Parameter Store (Standard tier) | 0.00 |
| Data transfer | 1.50 |
| **AWS total** | **~$21** |
| OpenAI (nano + mini, 500 interviews) | ~5 |
| **All-in** | **~$26/mo ≈ ₹2,200** |

A 1-year Compute Savings Plan on the instance takes AWS to ~$17. At 500 interviews that's **~₹3.5 all-in per interview against ₹100 of revenue — roughly 96% gross margin**, versus the doc's 85%.

**Break-even: ~22 interviews/month**, down from the doc's 76.

### 2.4 What you're accepting in exchange

Say these out loud now so they don't surprise you later:

- **Single point of failure.** Instance dies → platform down until it's replaced. Mitigation: EC2 auto-recovery alarm (free) handles host-level failure in ~3 minutes. A running interview is lost — the candidate must be re-invited. At pilot volume this is a handful of incidents a year; at 20 paying customers it stops being acceptable.
- **You patch the OS.** Amazon Linux 2023 with `dnf-automatic` for security updates, monthly reboot window.
- **Backups are yours.** Nightly `pg_dump | gzip | aws s3 cp` on cron, 30-day lifecycle, **plus a restore drill before launch** — an untested backup is not a backup. RPO 24h vs RDS's ~5 min. Given the data (job postings, candidates, wallet ledger) this is defensible for a pilot, but the **wallet ledger is money** — consider a second `pg_dump` of `wallet_transactions` alone every 6 hours; it's tiny and nearly free.
- **Deploys have ~20s downtime.** `docker compose pull && up -d`. Fine off-peak; not fine mid-interview. Deploy gate: refuse if any session is IN_PROGRESS.
- **2 GB RAM is tight but sufficient** *because of the April architecture* — all media stays client-side, so the server only relays text over WebSocket. Tune `-XX:MaxRAMPercentage=45` (~900MB heap), Postgres `shared_buffers=256MB`, and add 2GB swap as a safety net. If you'd rather not think about it, t4g.medium (4GB) is +$12/mo.
- **In-memory WebSocket registry only works at one instance.** Document this loudly in the code. The moment you run two, you need Redis pub/sub or sticky sessions.

*Considered and rejected:* **Lightsail** ($12/mo bundles 2GB + 60GB SSD + 3TB transfer, and would come out slightly cheaper) — but Lightsail instances can't assume IAM roles, so S3/SES/SSM access would require static access keys on the box. That trades away the "no long-lived credentials" property for about $2/mo. Not worth it.

---

## 3. Sequenced plan

Nine phases. Phases 1–2 are prerequisites; 3–5 are the product; 6–9 harden it. Phase 4 (infra) can run in parallel with 3 if you have the bandwidth.

### Phase 1 — Stop the bleeding *(1–2 days)*

Existing-code defects. #2 is a live security hole and #6 means the frontend doesn't currently build.

1. **Wallet funds leak.** `SessionExpiryJob` must call `walletService.releaseFunds` for each expired session. The bulk `UPDATE` can't do this — switch to a paged fetch-then-release loop, or add a compensating sweep. Also fix the Javadoc, which currently asserts the opposite of what the code does. Write a migration to release funds already stranded by this bug.
2. **Webhook HMAC fails open.** `WebhookService:296-300` skips verification when the secret is blank, on a `permitAll` endpoint. Fail closed: reject the request and log an error. Add a startup `@PostConstruct` assertion that the secret is non-blank outside the `local` profile.
3. **Staging JWT keys.** Populate `app.security.jwt.private-key-pem` / `public-key-pem` / invite secret from SSM. Fix the orphaned `app.jwt.*` prefix in `application-staging.yml` (should be `app.security.jwt.*`). Make `TokenConfig` **fail startup** rather than generate ephemeral keys when the profile isn't `local`.
4. **`app.frontend.base-url`** in `application-prod.yml` — currently falls back to `http://localhost:3000`, so production invite emails would be dead links.
5. **CORS.** Add a `CorsConfigurationSource` bound to an `app.security.cors.allowed-origins` list. Nothing exists today.
6. **Frontend build.** Add `status` and `jobId` to `candidatesApi.list`'s param type and wire them through — the two call sites already pass them, the filters are silently dead, and `tsc --strict` rejects it.
7. **Upload validation.** Whitelist content types, add `ContentLengthRange` conditions to pre-signed PUTs, and validate that the `objectKey` in `upload-confirm` matches the expected `{companyId}/{entityId}/...` prefix — today a caller can point their job at any object in the bucket.
8. Commit `frontend/package-lock.json` (both `npm ci` call sites fail without it) and add `.dockerignore` at root and in `frontend/`.

### Phase 2 — Align to spec and cut the stale code *(2–3 days)*

9. **Correct the wrong values** — every row in §3 of the audit report. The ₹50→₹100 change touches `application.yml`, `.env.example`, `docker-compose.yml`, `BillingPage.tsx` and `DashboardPage.tsx`. Also: JWT TTLs (60min/7d), invite TTL 72h, evaluation weights 40/20/25/15, recommendation bands.
10. **Migrate the session state machine.** `V027`: add `IN_PROGRESS` and `EVALUATING` to the status CHECK; add `recording_s3_key`, `consent_accepted_at`, `started_at` semantics. Then write the `SessionStateMachine` class that `SessionStatus`'s Javadoc already claims exists.
11. **Switch AI models** to per-workflow config (§0) and move prompts out of inline Java strings into `resources/prompts/*.st` as the spec requires. Add real validation: array shape, count in range, no duplicates, score bounds. Fix the failure loop at `QuestionGenerationWorker:118` — the `FAILED` marker is currently saved inside the same transaction that's about to roll back, so failed sessions re-poll forever.
12. **Delete the stale Recall/Meet/Transcribe/Polly surface** — ~15 files, listed in §5 of the audit report. **Order matters:** `WebhookService.handleRecall` is currently the *only* path that moves a session past INVITED. It must survive until Phase 3 lands, then be removed in the same PR that makes the browser room live.
13. Drop the `transcribestreaming` and `polly` SDK dependencies — declared in `pom.xml` with zero Java usage, pure image bloat.

### Phase 3 — The interview room *(the critical path — 2–3 weeks)*

This is the product. Everything before it is prologue.

**3a — WebSocket transport.** Add `spring-boot-starter-websocket`. `InterviewWebSocketHandler extends TextWebSocketHandler`, registered at `/ws/session/{sessionId}`, authenticated from the `?token=` query param via the existing candidate filter chain. In-memory `ConcurrentHashMap<UUID, WebSocketSession>` registry — correct only at one instance, so comment it accordingly. Implement all 8 events. Server-side 60-minute timer per session via `ScheduledExecutorService`, emitting `timer.warning` at 10/5/1 min and `session.terminated` at cutoff.

**3b — Candidate room backend.** The 7 endpoints from API §6. Candidate JWT (2h, session-scoped) reusing the existing `OtpService` and `TokenService` — no Google OAuth for candidates in MVP, OTP already works and OAuth is pure added surface. New tables: `session_answers` (sessionId, questionIndex, questionText, transcriptText, durationSeconds, isFollowUp, score) and `session_events` (sessionId, eventType, metadata, occurredAt).

**3c — Candidate room frontend.** Replace `CandidateRoomPage.tsx` wholesale — today it's a typed-textarea questionnaire with a "Join Meeting" link, polling REST every 10 seconds. New build: browser + permission preflight, consent screen, `getUserMedia`, `MediaRecorder` chunked capture, TTS question playback, ASR answer capture per §1.1, WebSocket client with reconnect-and-resume, timer UI, proctoring hooks, upload-and-complete flow. **Ship a typed-answer fallback path** — microphone permission will be denied often enough to matter.

**3d — Interview quality controls.** All currently missing: hard duration cap, "could you elaborate?" on answers under 5 words, 60s silence prompt, 90s silence → mark Skipped and advance.

**3e — Delete the Recall path** in the same PR that makes 3a–3d live.

### Phase 4 — Infrastructure *(1 week, parallelisable with Phase 3)*

14. Terraform from zero — but a **much smaller** module set than the doc assumed: `network` (VPC, one public subnet, SG), `compute` (EC2 + EIP + instance profile + user-data), `storage` (3 S3 buckets + lifecycle + SSE-S3), `edge` (CloudFront + ACM + Route 53), `email` (SES domain, DKIM, SPF, DMARC), `secrets` (SSM SecureString), `monitoring` (auto-recovery alarm, disk alarm, SNS). Remote state in S3 with a DynamoDB lock table (~$0). Add `.terraform/`, `*.tfstate*`, `*.tfvars` to `.gitignore` — currently missing.
15. Box provisioning via cloud-init: Docker, Caddy, `docker-compose.prod.yml`, backup cron, `dnf-automatic`.
16. CD: GitHub Actions → build → push GHCR → SSH deploy → health check → rollback on failure. **AWS OIDC role only for the S3 frontend sync + CloudFront invalidation step** — no static AWS keys anywhere, satisfying the spec's security requirement at zero cost.
17. `docker-compose.prod.yml` — pin `postgres:16-alpine` (currently 15), data on the mounted EBS volume, healthchecks, restart policies, memory limits.
18. **Backup restore drill.** Not optional. Prove the `pg_dump` restores into a clean database before you take a paying customer.

### Phase 5 — Revenue completeness *(3–4 days)*

19. `POST /billing/topup/verify` — today the flow depends entirely on the webhook, so a webhook delay leaves the user staring at a stale balance.
20. **GST.** Zero occurrences of "gst" in the backend today. Add `gst_number` to `companies`, gross/tax/net columns on `wallet_transactions`, and `GET /billing/transactions/{txnId}/invoice`. This is a legal requirement for Indian B2B, not a nice-to-have.
21. Low-balance email (≤₹300) and the dashboard banner (<₹300). Handle `payment.failed`. Add the session-completion email to the recruiter — `EmailService` currently has exactly two templates.

### Phase 6 — Supporting modules *(1–2 weeks)*

22. Dashboard endpoints (API §11) — `DashboardPage` currently fabricates stats client-side from `totalElements` on four list queries.
23. Storage controller (API §10) and start actually writing to the `storage_objects` table, which exists and has never held a row.
24. Scheduling slots (API §7) — 5 endpoints, employer management UI, candidate picker, optimistic-lock double-booking guard.
25. Job-level question bank: generate on JD upload (12–20 questions), `GET /jobs/{jobId}/questions`, preview UI. **Note:** the `V024` DB CHECK currently enforces 8–12 and would *reject* the spec's range — fix the constraint in the same migration.
26. Candidate `PUT`/`DELETE` with their guards; `phone` field; `status`/`search` filters on the job and candidate lists.
27. Company logo upload; `industry`/`size`/`gstNumber` fields. Session reinvite, recording playback, transcript download, employer notes (the `session_notes` table exists as dead code).
28. Google OAuth and the SES bounce webhook — **lowest priority.** OAuth is convenience; email OTP already works. The bounce handler matters only once you're sending enough volume to risk sender reputation.

### Phase 7 — Tests *(1 week, ongoing)*

29. Testcontainers + `maven-failsafe`. CI already starts a Postgres service container that **nothing uses**. Cover the session lifecycle end-to-end first — it's the highest-value untested path.
30. Unit tests for the wallet ledger (reserve/settle/release invariants, concurrent top-up), question-generation validation, and evaluation scoring.
31. Frontend: install the missing `jsdom` / `@vitest/coverage-v8` (all three test scripts fail today), write MSW handlers, wire `npm test` and eslint into CI.
32. JaCoCo with a coverage floor — start at 40% and ratchet, rather than picking a number nobody hits.

### Phase 8 — Observability & launch readiness *(3–4 days)*

33. **Logback JSON encoder.** `application-prod.yml:71-72` claims structured JSON output; there is no logback config file at all, and the pattern references an MDC `traceId` that nothing populates. Ship to CloudWatch with 7-day retention (30 days costs more than it's worth at this stage). Skip X-Ray — it's a paid service and of limited value on a single box.
34. Alarms: instance status (auto-recovery), disk >80%, 5xx rate, evaluation-worker failures, low free memory. SNS → email.
35. Fix the nginx security headers — server-level `add_header` is silently dropped by both `location` blocks that set their own. Add CSP, HSTS, `client_max_body_size`. *(Or drop nginx entirely: the SPA is served from CloudFront and Caddy fronts the API, so the container may not be needed in production at all.)*
36. Disable Swagger UI outside `local`/`staging` — currently `permitAll` in production.
37. Move the refresh token to an HTTP-only `Secure` `SameSite=Strict` cookie; set `withCredentials: true`. It's in `localStorage` today.
38. Rate limiting per spec: 5 failed logins/IP/min → 15-min lockout (needs `failed_attempts` + `locked_until` columns, which don't exist), and a 3-per-15-min OTP resend cap.
39. Bound the `RateLimitFilter` bucket map — it grows without eviction today, which is a slow memory leak on a 2GB box.

### Phase 9 — Launch *(1 week)*

40. SES production access (sandbox → production takes days, **start this early — it's the longest external dependency in the plan**). DKIM/SPF/DMARC.
41. Load test at 50 concurrent WebSocket sessions on the actual t4g.small. This is the number that decides whether 2GB holds. Have the t4g.medium upgrade path ready.
42. OWASP Top 10 pass, privacy policy at candidate login, candidate data-deletion endpoint (a PRD commitment with no implementation).
43. Soft launch with 3 pilot clients.

---

## 4. Where this lands

Against the PRD's 6-sprint/12-week plan, the repo is roughly **through Sprint 2** — with parts of Sprints 4–5 built against the architecture you've just retired. Phases 1–2 above are recovery work; Phase 3 is Sprint 3 rebuilt correctly; Phases 4 and 9 are Sprints 0 and 6, untouched to date.

Realistic remaining effort for one full-time developer: **8–10 weeks** to soft launch. Phase 3 is over a third of it and has the most uncertainty — the browser media stack is where estimates go wrong, and §1.1 is the specific thing that could cost you an extra week if the on-device Whisper path turns out to be slow on the low-end laptops your candidates actually use. Prototype that in isolation, first thing, before committing to the rest of Phase 3.

**Immediate next step:** answer the three questions in §1 — particularly 1.1, which is the only one with a compliance dimension. Then Phase 1 can start the same day; it needs no decisions from you.

---

*Pricing checked 1 Aug 2026. Sources: [Vantage — t4g.small](https://instances.vantage.sh/aws/ec2/t4g.small?currency=USD&duration=monthly), [Morph — OpenAI API pricing 2026](https://www.morphllm.com/openai-api-pricing), [CloudZero — OpenAI pricing](https://www.cloudzero.com/blog/openai-pricing/). AWS figures are ap-south-1 on-demand and exclude taxes.*
