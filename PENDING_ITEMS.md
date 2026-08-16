# InterviewIQ — Pending & Unimplemented Items

**Audit date:** 1 August 2026
**Repo:** `/Users/sps/code/interviewiq` — branch `master`, 2 commits
**Scale:** 152 Java files (~11.2k LOC) · 63 TS/TSX files (~8.8k LOC) · 22 Flyway migrations · 5 backend test classes · 0 frontend tests
**Baseline:** InterviewIQ MVP PRD v1.0 (Mar 2026), MVP API Reference (Apr 2026), AWS MVP Architecture (Apr 2026)

---

## 0. Read this first — the spec pivoted and the code did not

The three documents are **two different generations of the product**, and they conflict on the single most important design decision.

| | March 2026 (PRD v1.0) | April 2026 (API Reference + AWS Architecture) |
|---|---|---|
| Interview venue | Google Meet room | **Candidate's browser** |
| Bot / media | Recall.ai bot joins the Meet | **WebRTC + `getUserMedia` + `MediaRecorder`** |
| Speech-to-text | AWS Transcribe streaming | **Browser Web Speech API** |
| Text-to-speech | Amazon Polly Neural | **Browser `SpeechSynthesis`** |
| Transport | Recall webhooks | **WebSocket `/ws/session/{id}`, 8 events** |
| Recording | none | **Video → S3, 7-day lifecycle** |
| Secrets | AWS Secrets Manager | **SSM Parameter Store** |
| Networking | NAT gateway | **VPC endpoints, no NAT** |
| Extras | — | scheduling slots, anti-cheat proctoring, dashboard analytics |

The stated reason for the pivot is in the PRD's own risk table: Recall.ai costs **~₹375 for a 30-minute interview against ₹100 of revenue**. The April architecture removes that cost entirely and lands all-in infra at ~₹7,600/month for 500 interviews (~85% gross margin).

**The codebase implements the March design end-to-end and has zero of the April design.** That is not a bug list — it's a decision you need to make before anything else on this page gets prioritised.

> **Decision needed:** migrate to the April in-browser architecture, or formally revert the spec to Recall.ai and accept the negative unit economics. Everything in §1 assumes the April docs are authoritative.

---

## 1. Entire modules that do not exist

No controller, no service, no table, no client code. These are greenfield.

| # | Module | Spec | What's missing |
|---|---|---|---|
| 1.1 | **WebSocket interview signalling** | API §12 | No `spring-boot-starter-websocket` dependency, no `InterviewWebSocketHandler`, no config. All 8 events absent (`session.start`, `question.next`, `answer.submit`, `followup.question`, `proctoring.event`, `timer.warning`, `session.end`, `session.terminated`). Neither `nginx.conf` nor `vite.config.ts` can proxy a WS connection, so it wouldn't work in dev or prod even if written. |
| 1.2 | **Candidate interview room (backend)** | API §6 — 7 endpoints | `GET /candidate/session?token=`, `POST /candidate/auth/otp` (candidate JWT, 2h, session-scoped), `POST .../start` (consent + 60-min server timer), `GET .../questions/{index}`, `POST .../answers`, `POST .../events` (anti-cheat), `POST .../complete`. No answers table, no proctoring table, no `EVALUATING`/`IN_PROGRESS` statuses. |
| 1.3 | **Candidate interview room (frontend)** | API §6/§12 | `CandidateRoomPage.tsx` is a **typed-textarea questionnaire with a "Join Meeting" link**, polling REST every 10s. Zero occurrences repo-wide of `getUserMedia`, `MediaRecorder`, `SpeechRecognition`, `speechSynthesis`, `WebSocket`, `consent`, `proctor`, `tab_switch`, `Blob`. |
| 1.4 | **Scheduling & availability slots** | API §7 — 5 endpoints | No slots table, entity, or endpoints. `POST /sessions` has no `slotId`. No employer slot-management UI, no candidate slot picker. Optimistic-lock double-booking protection unimplemented. |
| 1.5 | **Dashboard & analytics** | API §11 — 3 endpoints | `/dashboard/stats`, `/recent-sessions`, `/score-distribution` don't exist. `DashboardPage` fabricates stats client-side by reading `totalElements` off four list queries. |
| 1.6 | **Storage controller** | API §10 — 3 endpoints | `/storage/upload-url`, `/candidate/storage/upload-url`, `/storage/upload-confirm`. `StorageService` exists but is only called internally. The `storage_objects` table (V013) is **never written to by any code**. No `RECORDING` or `LOGO` object types. |
| 1.7 | **Terraform / all infrastructure** | AWS Arch | `terraform/` contains exactly two `.DS_Store` files. Everything must be built: networking (no-NAT VPC), VPC endpoints (S3/ECR/SSM/Logs — mandatory without NAT), ECS Fargate, ALB, RDS, S3 ×3, CloudFront, ACM, Route 53, API Gateway (REST **and** WebSocket), ECR, SES, SSM params, CloudWatch/alarms/SNS, GitHub OIDC IAM role, `envs/staging` + `envs/production`, remote state backend. `.gitignore` also lacks `.terraform/`, `*.tfstate`, `*.tfvars`. |
| 1.8 | **Continuous deployment** | AWS Arch | CI pushes images to **GHCR, not ECR**. No ECS deploy step, no `aws-actions/configure-aws-credentials`, no `permissions: id-token: write`, no `role-to-assume`, no terraform plan/apply, no frontend S3 sync + CloudFront invalidation. `ci.yml:25` admits deployment "not yet added". |
| 1.9 | **Google OAuth** | API §1 | `GET /auth/google` + `/auth/google/callback`. No `spring-boot-starter-oauth2-client` dependency. `User.googleSubject` and the `google_subject` column exist but nothing writes them. No OAuth button on Login or Onboarding. |
| 1.10 | **SES bounce handling** | API §9 | `POST /webhooks/ses-bounce`, SNS subscription confirmation, suppression list. `EmailStatus.BOUNCED` is defined but never set; `WebhookProvider` has no SES value so a bounce couldn't even be persisted. |
| 1.11 | **Admin panel** | PRD §7.8.2 | Company list, interview counts, cost tracking, manual wallet credit with mandatory reason. No route, page, endpoint, or API module. |

---

## 2. Critical defects in code that already exists

These are live bugs, not gaps.

| # | Severity | Defect | Location |
|---|---|---|---|
| 2.1 | **High — money** | **Wallet funds leak on expiry.** `SessionService` reserves at INVITED (line 140), but `SessionExpiryJob` bulk-updates INVITED→EXPIRED with **no `releaseFunds` call** — justified by a Javadoc comment that misstates the code ("a reservation is only created when the session moves to STARTED"). Every expired invite permanently strands the session cost in `reservedPaise`. | `session/scheduler/SessionExpiryJob.java:21-51`, `session/service/SessionService.java:140` |
| 2.2 | **High — security** | **Webhook signature verification fails open.** HMAC is skipped entirely when the secret is blank; `/api/v1/webhooks/**` is `permitAll`. A missing env var silently lets an unauthenticated caller forge `payment.captured` and mint wallet credit. | `webhook/service/WebhookService.java:296-300`, `auth/config/SecurityConfig.java:155` |
| 2.3 | **High** | **Staging generates throwaway JWT/invite keys on every boot.** `application-staging.yml` sets no `private-key-pem`, `public-key-pem`, or invite secret, so `TokenConfig` falls back to ephemeral generation (and logs "NEVER use this in staging or production"). Every restart invalidates all tokens and every outstanding candidate invite link. Its `app.jwt.*` keys also bind to nothing — wrong prefix, should be `app.security.jwt.*`. | `application-staging.yml:58-71`, `auth/config/TokenConfig.java:53-84` |
| 2.4 | **High** | **Production invite emails will contain `localhost` links.** `application-prod.yml` never sets `app.frontend.base-url`; it falls back to `http://localhost:3000`. | `application-prod.yml`, `application.yml:144` |
| 2.5 | **High** | **CORS is not configured anywhere.** Zero `cors` / `CorsConfigurationSource` / `addCorsMappings` hits in `src/main`. The SPA cannot call the API cross-origin as shipped. | `auth/config/SecurityConfig.java` |
| 2.6 | **High** | **Frontend does not compile.** `CandidatesPage.tsx:276-281` and `JobDetailPage.tsx:80` pass `status`/`jobId` to `candidatesApi.list`, which accepts neither → `tsc` excess-property errors under `strict`. Both filters are also silently dead at runtime. | `frontend/src/pages/employer/candidates/CandidatesPage.tsx`, `.../jobs/JobDetailPage.tsx`, `api/modules/candidates.ts:13-17` |
| 2.7 | **High** | **No file size or MIME validation on any upload path.** Backend accepts any `contentType` and silently maps unknown types to `""`; pre-signed PUTs carry no content-length-range condition; `upload-confirm` accepts a **client-supplied S3 key with no ownership/prefix check** (a caller can point their job at any object in the bucket). Frontend checks nothing beyond the HTML `accept` attribute. Spec limits: JD 10MB, resume 5MB, logo 2MB, recording 500MB. | `job/service/JobService.java:129-176`, `candidate/service/CandidateService.java:123-169`, `frontend` upload handlers |
| 2.8 | Medium | **Swagger UI is `permitAll` in production.** `OpenApiConfig` acknowledges this and recommends a conditional bean — not implemented. | `auth/config/SecurityConfig.java:157-158` |
| 2.9 | Medium | **`VITE_COMPANY_SLUG` is not a Docker build arg** — production frontend images silently point auth at the literal `interviewiq-dev` tenant. | `frontend/Dockerfile:12-13` |
| 2.10 | Medium | **Question-generation failure loop.** A response outside 8–12 questions passes the Java validator (which only checks "is this parseable JSON"), then trips the DB CHECK at flush. The `catch` block saves `FAILED` **inside the same doomed transaction**, so the marker never persists and the session re-polls forever. | `ai/service/QuestionGenerationWorker.java:118,186-196`, `V024` |
| 2.11 | Medium | **nginx security headers are silently dropped on every route.** Server-level `add_header` is not inherited by locations that declare their own; both `/assets/` and `/` set `Cache-Control`, nullifying `X-Frame-Options`, `X-Content-Type-Options` and `Referrer-Policy`. Also no CSP, no HSTS, no `client_max_body_size` (nginx default 1MB will reject the 10MB JD upload if it ever routes through nginx). | `frontend/nginx.conf:15-19,31-39` |
| 2.12 | Medium | **Refresh token stored in `localStorage`** and passed in the JSON body. Spec requires an HTTP-only secure cookie; `client.ts` sets `withCredentials: false`. | `frontend/src/stores/authStore.ts:17,66`, `auth/dto/RefreshRequest.java` |
| 2.13 | Low | `GlobalExceptionHandler` has no `ConstraintViolationException` handler, so `@Validated` `@RequestParam` failures return **500 instead of 400**. | `shared/web/GlobalExceptionHandler.java` |
| 2.14 | Low | Missing `frontend/package-lock.json` breaks `npm ci` in both `frontend-ci.yml:41` and `frontend/Dockerfile:8`. No `.dockerignore` at root or in `frontend/` (`.git`, `.env`, `node_modules` ship in the build context). | repo root, `frontend/` |

---

## 3. Wrong values — spec says X, code says Y

| Item | Spec | Code | Where |
|---|---|---|---|
| Price per interview | **₹100** | **₹50** (5000 paise) | `application.yml:142`, `.env.example:126`, `docker-compose.yml:101`, `BillingPage.tsx:193,333,358`, `DashboardPage.tsx:131` |
| Access token TTL | 60 min | 15 min | `application.yml:127` |
| Refresh token TTL | 7 days | 30 days | `application.yml:128` |
| Invite token TTL | 72h (48h on reinvite) | 7 days | `application.yml:131`, hardcoded in the email body too |
| Evaluation weights | Tech 40 / Comm 20 / Relevance 25 / Problem-solving 15 | 30 / 20 / 30 / 20 | `ai/service/EvaluationWorker.java:216` |
| Question count | 8–12 (PRD) / 12–20 (API ref) | 10, DB CHECK forces 8–12 | `QuestionGenerationWorker.java:53`, `V024:49-54` — the API-ref range would be **rejected by the DB constraint** |
| Job statuses | DRAFT / ACTIVE / PAUSED / ARCHIVED | ACTIVE / ARCHIVED / CLOSED; new jobs start ACTIVE, delete sets CLOSED | `job/domain/JobStatus.java`, `JobService.java:74,115` |
| Session statuses | + IN_PROGRESS, EVALUATING | INVITED/STARTED/COMPLETED/EXPIRED/ERROR/CANCELLED | `SessionStatus.java`, `V007:91-92` |
| Recommendation bands | Strong Yes 85-100 / Yes 65-84 / No 40-64 / Strong No 0-39 | LLM told to pick a label, **no thresholds enforced**; UI shows HIRE/HOLD/REJECT | `EvaluationWorker.java:218,240-261`, `SessionDetailPage.tsx:59-68` |
| Score dimensions | 5 (incl. **confidence**) | 4 | `V009`, `SessionDetailPage.tsx:243-250` |
| Rate limit | 5 failed logins/IP/min → 15-min lockout | 20-burst / 10-per-min per-IP over **all** `/api/v1/**`; no lockout columns exist | `RateLimitFilter.java`, `SecurityConfig.java:65-74` |
| Top-up presets | ₹500/1000/2500/5000/10000 | `@Min(5000 paise)` = ₹50 floor, no ceiling, no enum; UI shows 500/1000/2000/5000 | `TopUpRequest.java:13`, `BillingPage.tsx:68` |
| Base URL | `/api/...` | `/api/v1/...`, and auth is `/api/v1/{companySlug}/auth/...` | every controller |
| Domain | `interviewiq.in` | `interviewiq.ai` in config/emails | `application.yml:139`, `docker-compose.yml:100` |
| Secrets | SSM Parameter Store | **Environment variables everywhere**, incl. the RSA private key and static AWS access keys | `application-prod.yml:24-103`, `docker-compose.yml:78-97`, `.env.example` |
| Container registry | ECR | GHCR | `ci.yml:44-45` |

---

## 4. Missing behaviour inside endpoints that do exist

**Auth** — `GET /auth/me` missing (the SPA decodes the JWT client-side instead). OTP resend has no 3-per-15-min cap. No account-lockout columns. Team invite emails a **temporary password** instead of a set-password link; no invite-accept page, no revoke, no pending state, no work-email domain check.

**Company** — `POST /company/logo` missing (no `MultipartFile` anywhere in the backend). `logoUrl`, `industry`, `size`, `gstNumber` exist in **neither** the DTO, the entity, nor the `companies` table — which makes the GST-invoice requirement unimplementable as designed.

**Jobs** — `GET /jobs/{id}/questions` missing; there is no job-level question bank at all (generation is per-session at invite time, not per-job at JD upload). `GET /jobs` ignores `status` and `search` (the repository method exists but is unused; the UI filters client-side over the current page only). `GET /jobs/{id}` returns neither the question bank nor candidate-count-by-status. `JobOpening` is missing `description`, `experienceMin`, `experienceMax`.

**Candidates** — `PUT /candidates/{id}` and its "only before invite sent" guard: missing. `DELETE /candidates/{id}` and its "only if no completed session" guard: missing. `phone` field missing from DTO, entity and table. List endpoint ignores `status`.

**Sessions** — `POST /sessions/{id}/reinvite` missing entirely. `GET /sessions/{id}/recording` missing (no `recording_s3_key` column). `GET /sessions/{id}/transcript` missing — the transcript is fetched in-memory at evaluation time and **discarded**; `EvaluationReport.transcriptS3Key` is a dead column. `GET /sessions/{id}/report` exists as `/evaluation` but returns a raw JSON blob with no per-question breakdown. **STARTED and COMPLETED are reachable only via the Recall.ai webhook** — remove Recall and sessions can never leave INVITED. `SessionStatus` Javadoc claims transitions are "enforced by `SessionStateMachine`" — **no such class exists**.

**AI** — Prompts are inline Java text blocks; the spec's `resources/prompts/*.st` StringTemplate files **do not exist** (no `.st` file anywhere in the repo). No category mix enforcement (Technical 40% / Role-specific 25% / Behavioural 20% / Motivation 15%) — the prompt uses a different taxonomy entirely. Validation is parse-only: no min-count check, no duplicate detection, no array-shape check, no score-range check. No adaptive follow-up questions. No circuit breaker, no queued retry, no cached-question-bank fallback (no Resilience4j dependency). Evaluation JSON is never written to S3. "Flag for manual review" has no surface — failures just land in `FAILED` with no queue or notification. **All interview quality controls are missing**: no 30/60-min cap, no "<5 words → could you elaborate", no 60s silence prompt, no 90s → Skipped.

**Billing** — `POST /billing/topup/verify` missing (the flow relies solely on the webhook). `GET /billing/transactions/{txnId}/invoice` missing. **Zero occurrences of "gst" in the entire backend** — no tax column, no gross/net split. `payment.failed` unhandled. No low-balance email (≤₹300), no low-balance banner (<₹300), no completion email, no slot-confirmation email — `EmailService` has exactly two senders: OTP and candidate invite.

**Reports UI** — no recording player, no reinvite button, no print-to-PDF (no `@media print` CSS), no employer internal notes (`session_notes` table V008 and its entity/repository are **dead code, referenced by nothing**), no proctoring-event display, no score-distribution chart, no question-bank preview.

---

## 5. Stale code to delete (if the April architecture is confirmed)

| Artifact | Note |
|---|---|
| `ai/service/RecallTranscriptClient.java` (253 lines) | Fully functional Recall.ai client with a hardcoded `STUB_TRANSCRIPT` fallback |
| `WebhookService.handleRecall` + `processRecallBotJoined/Done/Failed` | **Currently the only path that advances a session past INVITED** — must be replaced before deletion |
| `POST /api/v1/webhooks/recall`, `WebhookProvider.RECALL_AI` | API doc says `/candidate/sessions/{id}/events` replaces this |
| `PATCH /sessions/{id}/meet-url`, `PATCH /candidate/session/meet-url`, `SetMeetUrlRequest` (hard-validates `https://meet.google.com/...`), `SessionResponse.googleMeetUrl` | |
| Columns `recall_bot_id`, `google_meet_url` (V007:16-23) | |
| `shared/config/RecallProperties.java` + `app.recall.*` in all 4 profiles + `.env.example` + `docker-compose.yml` | Prod/staging **fail to start** without `RECALL_API_KEY` |
| `shared/exception/BotServiceException.java` | Recall-only |
| `pom.xml` `transcribestreaming` (:190-194) and `polly` (:196-200) | Declared but **zero Java usage** — pure image bloat |
| `shared/audit/AuditSource.java` | Byte-identical duplicate of `audit/domain/AuditSource.java`; unused |
| `SessionNote` / `NoteType` / `SessionNoteRepository` / table V008 | Dead code |
| Frontend: `sessions.setMeetUrl`, Meet copy in `CandidateRoomPage`/`CandidateDetailPage`/`BillingPage`, "Bot ID" display | |
| `frontend/README.md` (still the default Vite template), `index.html` `<title>frontend</title>`, duplicate `tailwind.config.js`+`.ts`, committed `vite.config.ts.timestamp-*.mjs`, `.DS_Store` files | Housekeeping |

---

## 6. Testing & observability

**Backend tests:** 5 classes / 35 tests covering `TokenService`, `AuthController` (slice, security excluded), `WalletService`, `WebhookService`, `AuditAspect`. That's **4 of 13 modules**, ~3.4% of classes.

**Zero tests:** ai, candidate, company, email, job, session, storage, team, shared. The two highest-value flows — session lifecycle and AI question-generation/evaluation — are entirely untested.

**Missing test infrastructure:** no integration tests (`ci.yml` spins up a Postgres service container that **nothing uses**), no Testcontainers, no `maven-failsafe` (so `mvn verify` runs surefire only), no JaCoCo or any coverage gate, no Flyway migration test, no Gatling load test (spec: 50 concurrent), no OWASP dependency-check, no contract tests.

**Frontend tests:** zero test files. Vitest + Testing Library + MSW are configured but `jsdom`, `@vitest/ui` and `@vitest/coverage-v8` are **not installed**, so all three test scripts fail today. MSW has no handlers. CI never runs `npm test` or eslint.

**Observability:** Actuator and the Micrometer Prometheus registry are present. Everything else is missing — no X-Ray (0 hits repo-wide), **no logback config file at all** despite `application-prod.yml:71-72` claiming "structured JSON output" (the pattern references `%X{traceId}` but nothing populates the MDC), no CloudWatch log group or 30-day retention, no alarms, no SNS, no CloudTrail, no dashboards, no custom business metrics.

---

## 7. Suggested sequencing

**Step 0 — decide the architecture.** Nothing below is worth starting until §0 is settled. If April wins, §1.1–1.3 is the critical path and roughly half the current session/webhook/AI code gets rewritten.

**Then, in order:**

1. **Ship-blockers that are cheap** — §2.1 (funds leak), §2.2 (fail-open webhook), §2.3 (staging keys), §2.4 (localhost emails), §2.5 (CORS), §2.6 (broken build). A day or two, and 2.2 is a live security hole.
2. **Correctness sweep** — §3. Mostly config-value changes; the ₹50/₹100 mismatch touches billing, dashboard and marketing copy.
3. **Interview core** — §1.1, §1.2, §1.3. This is the product. Budget the bulk of the remaining time here.
4. **Infrastructure** — §1.7, §1.8. Terraform from zero plus a real CD pipeline; can run in parallel with 3.
5. **Revenue completeness** — GST + invoices + `topup/verify` + low-balance alerts (§4 Billing).
6. **Supporting modules** — §1.4 slots, §1.5 dashboard, §1.6 storage, §1.9 OAuth, §1.10 SES bounce, §1.11 admin panel.
7. **Test & observability floor** — §6. At minimum: Testcontainers integration tests over the session lifecycle, a JaCoCo gate, and a logback JSON encoder before production traffic.
8. **Delete stale code** — §5, once §1.1–1.3 replace it.

Against the PRD's own 6-sprint / 12-week plan, the repo is roughly **through Sprint 2** (auth, jobs, candidates, question generation) with parts of Sprints 4–5 (evaluation, wallet) done against the superseded architecture. Sprints 0 (Terraform), 3 (interview delivery) and 6 (QA, load testing, production deployment) are essentially untouched.
