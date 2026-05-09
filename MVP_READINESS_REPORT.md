# InterviewIQ — MVP Readiness Report

_Date: 2026-05-09_
_Reviewer: code audit pass_

---

## 1. Executive summary

The InterviewIQ backend is a well-structured Spring Boot 3.3 / Java 21 / Postgres 15 codebase with strong fundamentals: clean module boundaries (auth, billing, candidate, company, email, job, session, storage, team, webhook, ai, audit), 26 Flyway migrations with thoughtful constraints, two-chain Spring Security (RS256 employer JWT + HMAC candidate invite token), Razorpay wallet with reserve/settle/release, presigned-S3 upload flows, Apache Tika extraction workers, Spring AI question generation, AI evaluation worker, and a React + TanStack Query frontend that covers every employer page the PRD calls out plus a candidate room. **Two PRD pillars are missing from the actual implementation, however**: (a) there is no Recall.ai bot dispatch — `WebhookService` consumes `bot.joined`/`bot.done` but nothing in the codebase ever calls Recall.ai's "create bot" API, so the candidate's interview never actually happens unless something external creates the bot; (b) there is no Polly TTS or AWS Transcribe streaming — the AI is just a text-to-text transcript-based evaluator. There is also no Google OAuth sign-in, no admin panel, and Terraform contains zero `.tf` files (only GCP-flavoured tfvars in an AWS-flavoured PRD). Everything else is plausibly launchable.

**Overall MVP completion: ~62%**

---

## 2. Methodology

I read:

- The 25 main `*Controller.java` and `*Service.java` files (Auth, Company, Job, Candidate, Session, CandidateSession, Billing, Team, Webhook).
- The five workers / AI clients: `QuestionGenerationWorker`, `EvaluationWorker`, `RecallTranscriptClient`, `JdExtractionWorker`, `ResumeExtractionWorker`.
- `SecurityConfig`, `application.yml`, `application-prod.yml`.
- All 22 Flyway migrations V001–V026.
- All 17 frontend page files under `frontend/src/pages/`.
- The two `terraform.tfvars` files (only files in `terraform/`).
- `.github/workflows/ci.yml`, `frontend-ci.yml`, `e2e.yml`, `test-report.yml`.

I cross-referenced every PRD-listed feature against actual implementation depth, noted stubs explicitly, and graded each module on what _ships_, not on lines of code. % ≈ implementation depth × user-visible completeness.

---

## 3. Module-by-module breakdown

| Module | PRD ask | What's implemented | What's missing | % done |
|---|---|---|---|---|
| **Auth — email/password** | Register, verify email OTP, login, refresh, logout, forgot/reset password | Full implementation in `AuthService` (lines 87–264). RS256 keys, BCrypt cost 12, OTP via SES, refresh-token rotation with hash-only storage, no user enumeration on forgot-password. SecurityConfig two-chain. | Nothing. Solid. | **95%** |
| **Auth — Google OAuth** | "email + Google OAuth + email OTP" per PRD §1 | Nothing — grep on `Google\|OAuth\|oauth` in src/main/java returns only Google Meet URL string handling. | Entire OAuth2 flow: client config, callback endpoint, Google client lib, `oauth_provider`/`oauth_subject` columns on users. | **0%** |
| **Company onboarding** | Create company + admin user + wallet + slug + OTP | `CompanyService.onboard()` does all of this atomically including auto-slug deduplication. `/register` and `/check-slug` are public. | `domain` field exists but no domain-verification flow. Acceptable for MVP. | **95%** |
| **Job Opening management** | CRUD + JD upload + Tika extraction | Full CRUD in `JobController` (`POST/GET/PATCH/DELETE`), presigned PUT URL, confirm-upload, `JdExtractionWorker` polls every 30s and runs Tika `AutoDetectParser`. Soft-delete to CLOSED. | No JD versioning (PRD doesn't require). | **95%** |
| **Candidate management** | CRUD + resume upload + Tika extraction | `CandidateController` has create / list (by-job + all) / get / resume-upload-url / resume-upload-confirm. `ResumeExtractionWorker` runs Tika async. Stub mode supported. | No bulk import (PRD doesn't require for MVP). | **95%** |
| **Invite link (HMAC, 72h TTL)** | Auto-generated, HMAC-signed, 72h TTL, email via SES | `TokenService.generateInviteToken()` + invite-token filter chain + `EmailService.sendCandidateInviteEmail`. PRD says 72h; default in `application.yml` is 7d (`app.security.invite.expiration: 7d`). Easy config tweak. | Reduce expiration to 72h or document deviation. | **90%** |
| **AI question generation** | 8–12 questions, Spring AI + GPT-4o, 4 dimensions: Technical 40% / Role 25% / Behavioral 20% / Motivation 15% | `QuestionGenerationWorker` generates exactly 10 questions using `gpt-4o`. Async, retries on FAILED status. **Dimensions are wrong**: prompt asks for `TECHNICAL, COMMUNICATION, PROBLEM_SOLVING, RELEVANCE` (line 174 of `QuestionGenerationWorker.java`), not the PRD's Technical/Role/Behavioral/Motivation. No weighting metadata in output. | Update prompt to PRD dimensions, persist per-dimension weights with the question array. | **70%** |
| **Recall.ai bot — dispatch** | Bot joins Google Meet on candidate's behalf | **Nothing.** `RecallTranscriptClient` only fetches transcripts; nowhere calls `POST /api/v1/bot/` to create a bot. Webhook handler accepts `bot.joined` events from a bot it never started. SessionService.create() emails the invite and stops. | Implement `RecallBotClient.createBot(meetingUrl, sessionId)`. Either trigger from candidate-set-meet-url endpoint or have a scheduler. | **5%** (transcript fetch is implemented; dispatch is the actual blocker) |
| **Polly TTS / AWS Transcribe streaming** | PRD §1 calls for Polly TTS for the bot voice and AWS Transcribe streaming for live STT | Zero references. Recall.ai handles both natively in their bot product, but our prompt says "Polly + Transcribe" which is the older non-Recall design. | Either drop from PRD (Recall.ai already does TTS+STT) or wire Polly/Transcribe explicitly. Recommend dropping — Recall covers it. | **0% (or N/A if PRD pivoted)** |
| **AI evaluation** | overall 0–100, dimension scores, recommendation enum (Strong Yes/Yes/No/Strong No), summary | `EvaluationWorker` polls PENDING/IN_PROGRESS reports every 30s, calls GPT-4o with question JSON + transcript, parses overall + 4 dimension scores + recommendation + summary + strengths + improvements. Crash recovery via IN_PROGRESS resume, max-attempts cap. | Same dimension naming mismatch as questions. Recommendation enum maps `STRONG_HIRE/HIRE/NO_HIRE/STRONG_NO_HIRE` (DB constraint V009 line 82) — semantically equivalent to Strong Yes/Yes/No/Strong No, OK. | **80%** |
| **Razorpay wallet** | Top-up min ₹500, ₹100 deduction per session, transaction history, low-balance alerts | `WalletService` with reserve/settle/release, optimistic locking via @Version, idempotent `confirmTopUp`. Webhook HMAC-verified. **PRD says ₹100/session**; default config is `session-cost-paise: 5000` (= ₹50) in `application.yml` line 142. **PRD says ₹500 minimum top-up**; frontend `BillingPage.tsx` line 60 enforces `min(100, "Minimum top-up is ₹100")`. Both are config nits. | Update price to ₹100 and minimum to ₹500. Add low-balance email alert (no scheduler exists for it). | **80%** |
| **Employer dashboard** | KPIs, activity feed, low balance banner | `DashboardPage.tsx` queries jobs/candidates/sessions/billing and renders stat cards + recent sessions. | No explicit "low balance banner" component visible — needs verification or implementation. | **75%** |
| **Evaluation report UI** | Scores, transcript, dimension chart, recommendation | `SessionDetailPage.tsx` imports `RadarChart, Radar, PolarGrid, PolarAngleAxis` from recharts and renders RecommendationBadge. Strengths/improvements rendered. | Transcript download not visible in the page; `transcript_s3_key` column exists on `evaluation_reports` so wiring is straightforward. | **80%** |
| **Webhook handlers** | Razorpay payment.captured + Recall bot lifecycle | `WebhookService` handles `payment.captured`, `bot.joined`, `bot.done`, `bot.failed` with idempotency + HMAC verification. | None for the events listed. | **95%** |
| **Email — SES** | OTP + invite emails | `EmailService` uses SES SDK, persists `email_events` row per send, stub mode for dev, structured templates. | Bounce/complaint webhook not wired. Acceptable for MVP. | **85%** |
| **Storage — S3** | Presigned PUT/GET, download, delete | `StorageService` does all four ops via AWS SDK v2. Stub mode returns synthetic URLs. | Bucket lifecycle rules (PRD §12) not in code; should live in Terraform anyway. | **90%** |
| **Audit logging** | PRD §X (audit trail) | `@Auditable` annotation + `AuditAspect` aspect. Used on `JOB_CREATED/UPDATED/DELETED`, `SESSION_CREATED/CANCELLED`. | Apply to more state-change endpoints (auth, billing). | **70%** |
| **Team management** | Invite, list, role update | `TeamController` covers list, invite (ADMIN-only), patch (ADMIN-only). | OK for MVP. | **90%** |
| **Admin panel** | "basic admin panel" per PRD | Zero pages, zero controllers. No `/admin/**` routes. | Whole module — minimal MVP version: read-only company list + session viewer. | **0%** |
| **Frontend — auth pages** | Login, onboard, verify, forgot, reset | All present (5 pages under `pages/auth/`). | None. | **95%** |
| **Frontend — employer pages** | Dashboard, Jobs, Candidates, Sessions, Billing, Team, Settings | All 7 pages exist. | Verify low-balance banner; verify transcript download in evaluation. | **85%** |
| **Frontend — candidate page** | Interview room | `CandidateRoomPage.tsx` exists with state machine WAITING → IN_PROGRESS → ANSWERING → SUBMITTED → COMPLETED. **Note**: candidate _types_ written answers per the page — this contradicts the PRD spec of voice-only interview via Recall bot. | Decide: text answers (current) or voice via Recall? If voice, the ANSWERING phase shouldn't exist. | **60%** (works, but inconsistent with PRD) |

### API endpoints implemented vs PRD

| Endpoint | Implemented? | File |
|---|---|---|
| `POST /api/v1/{slug}/auth/register` | yes | AuthController |
| `POST /api/v1/{slug}/auth/verify-email` | yes | AuthController |
| `POST /api/v1/{slug}/auth/resend-verification` | yes | AuthController |
| `POST /api/v1/{slug}/auth/login` | yes | AuthController |
| `POST /api/v1/{slug}/auth/refresh` | yes | AuthController |
| `POST /api/v1/{slug}/auth/logout` | yes | AuthController |
| `POST /api/v1/{slug}/auth/forgot-password` | yes | AuthController |
| `POST /api/v1/{slug}/auth/reset-password` | yes | AuthController |
| `POST /api/v1/auth/google` (OAuth) | **no** | n/a |
| `POST /api/v1/companies/register` | yes | CompanyController |
| `GET  /api/v1/companies/check-slug` | yes | CompanyController |
| `GET  /api/v1/companies/me` | yes | CompanyController |
| `PATCH /api/v1/companies/me` | yes | CompanyController |
| `POST/GET/PATCH/DELETE /api/v1/jobs` + `/{id}` | yes | JobController |
| `GET  /api/v1/jobs/{id}/jd-upload-url` | yes | JobController |
| `POST /api/v1/jobs/{id}/jd-upload-confirm` | yes | JobController |
| `POST/GET /api/v1/candidates`, `/{id}` | yes | CandidateController |
| `GET  /api/v1/candidates/{id}/resume-upload-url` | yes | CandidateController |
| `POST /api/v1/candidates/{id}/resume-upload-confirm` | yes | CandidateController |
| `POST/GET /api/v1/sessions`, `/{id}` | yes | SessionController |
| `PATCH /api/v1/sessions/{id}/meet-url` | yes | SessionController |
| `POST /api/v1/sessions/{id}/cancel` | yes | SessionController |
| `GET  /api/v1/sessions/{id}/evaluation` | yes | SessionController |
| `GET  /api/v1/candidate/session` | yes | CandidateSessionController |
| `PATCH /api/v1/candidate/session/meet-url` | yes | CandidateSessionController |
| `GET/POST /api/v1/billing/*` | yes | BillingController |
| `GET/POST/PATCH /api/v1/team/*` | yes | TeamController |
| `POST /api/v1/webhooks/razorpay` | yes | WebhookController |
| `POST /api/v1/webhooks/recall` | yes | WebhookController |
| `POST /api/v1/admin/*` | **no** — no admin module | n/a |

---

## 4. Critical launch blockers (P0)

These _must_ be fixed before any paid customer touches the product. Each is concrete, citeable, and shippable.

1. **No Recall.ai bot dispatch.** `WebhookService.processRecallBotJoined` (line 193) handles _incoming_ "bot joined" events, but nothing in the codebase ever creates a bot. The interview literally cannot happen. **Fix**: add `RecallBotClient.createBot(meetingUrl, sessionId)` and call it from a new endpoint (e.g. `POST /api/v1/sessions/{id}/dispatch-bot`) or trigger it when the candidate sets the meet URL in `SessionService.setCandidateMeetUrl`. Pass `metadata.session_id` so the existing webhook router can match it.
2. **Terraform has zero `.tf` files.** Only `terraform/envs/staging/terraform.tfvars` and `terraform/envs/production/terraform.tfvars` exist, and they're for **GCP** (`gcp_project_id`, `cloud_run_service_name`, `artifact_registry_repository`, `asia-south1`) — but the PRD specifies AWS `ap-south-1`. Cannot deploy at all. See `TERRAFORM_REVIEW.md`.
3. **Session-cost mismatch with PRD.** PRD says ₹100/session; `application.yml` line 142 says `session-cost-paise: 5000` (= ₹50). One-line fix; do it before billing reconciliation gets weird.
4. **Top-up minimum mismatch.** PRD says ₹500 minimum; `BillingPage.tsx` line 60 says `min(100, "Minimum top-up is ₹100")`. One-line fix.
5. **Dimension naming mismatch.** PRD: Technical / Role / Behavioral / Motivation (40/25/20/15). Code: Technical / Communication / Relevance / Problem_Solving (in `QuestionGenerationWorker` line 174 and `EvaluationWorker` line 211, plus DB columns `communication_score`, `relevance_score`, `problem_solving_score` in V009). Pick one and align prompts + DB + chart labels. **If you keep the current names**, update the PRD; if you keep the PRD names, you need a Flyway V027 to rename columns.

## 5. Important gaps (P1)

6. **No Google OAuth.** PRD lists "email + Google OAuth + email OTP" as the registration paths. Only the email path exists.
7. **Invite TTL is 7d, PRD says 72h.** Trivial config change in `application.yml`.
8. **Low-balance email alert** has no scheduler. Banner may exist in UI but nothing fires an email at threshold. Add a scheduled job.
9. **Candidate UX inconsistency.** `CandidateRoomPage.tsx` has an `ANSWERING` phase where candidates _type_ answers, which is incompatible with a voice-bot interview. Either remove that phase or document text fallback.
10. **No admin panel.** PRD calls for "basic admin panel". Even a thin one (read-only sessions / refund button) is missing entirely.
11. **CORS.** `SecurityConfig` does not declare a `CorsConfigurationSource` bean. The frontend on a separate origin will be blocked by the browser. Add `cors(...)` to both filter chains.
12. **No bot.joined timeout / no-show handling.** Sessions stuck in INVITED with no bot.joined webhook are picked up only by `SessionExpiryJob` once `invite_expires_at` passes. Worth adding a "bot dispatch timeout" alert.
13. **No transcript download in UI.** `transcript_s3_key` exists in DB but no presigned-GET endpoint to expose it.
14. **Polly TTS / AWS Transcribe.** Either remove from PRD (Recall already covers) or wire them. Recommend the former.
15. **`@Auditable` coverage.** Applied on jobs and sessions; not on auth, billing, team. Compliance gap for a product handling PII + payments.

## 6. Nice-to-haves (P2)

16. Bulk candidate CSV import.
17. JD versioning / preview-edit before save.
18. Bounce/complaint SES webhook.
19. Domain verification flow for company onboarding.
20. Per-question time-limit enforcement.
21. Slack/MS Teams notification on session COMPLETED.
22. Multi-language interview support.
23. White-label company branding on invite emails.

## 7. Risks & mitigations

| Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|
| Bot dispatch missing → "interview never happens" | catastrophic | certain on launch | P0 #1 above; ship before any paid customer |
| GCP/AWS Terraform mismatch → no infra | catastrophic | certain on launch | P0 #2; see Terraform review |
| Dimension mismatch → reports don't match marketing | medium | certain | P0 #5; pick one and align |
| Refresh-token theft | high | low | already mitigated: hash-only storage, rotation on use, revoke on password reset (`AuthService` line 263) |
| Razorpay duplicate credit | high | low | already mitigated: `txRepository.findByRazorpayOrderId` idempotency check (`WalletService` line 150) |
| Optimistic-lock contention on hot wallet | medium | low at MVP scale | already mitigated: `@Version` on Wallet |
| LLM JSON parse failures | medium | medium | already mitigated: `validateJson` in both workers, max-attempts cap with FAILED state |
| OpenAI cost overrun | medium | medium | add per-company monthly token cap (P1) |
| SES bounce → silent delivery failure | medium | medium | persist `email_events`, status FAILED visible; add bounce webhook P2 |
| No CORS → frontend cannot call backend | high | certain | add CORS config (P1 #11) |

---

## 8. Recommended sprint plan to ship MVP (1–3 weeks)

### Sprint 1 — Make the interview actually happen (week 1)

- Day 1–2: Implement `RecallBotClient.createBot(meetingUrl, sessionId)` and a new `POST /api/v1/sessions/{id}/dispatch-bot` endpoint. Pass `metadata.session_id` for the existing webhook router. Add a 5-minute bot-dispatch timeout via a scheduled poller that flips sessions to ERROR.
- Day 3: Align dimensions everywhere. Either:
  - Option A (cheaper): keep DB columns, update PRD wording, change prompt labels.
  - Option B (PRD-faithful): write `V027__rename_score_columns.sql`, update `EvaluationReport` entity, update `EvaluationReportResponse`, update `RadarChart` axis labels.
- Day 4: Fix the four config mismatches: session cost → 5000 paise (₹100? PRD says ₹100 → 10000 paise; `application.yml` says 5000 = ₹50 with comment "₹50"); top-up min → ₹500; invite TTL → 72h; clarify the comment in application.yml.
- Day 5: Wire CORS in `SecurityConfig`. Add `@Auditable` to `WalletService.confirmTopUp` and `AuthService.login`.

### Sprint 2 — Make the infra real (week 2)

Cover the entire Terraform remediation plan (see `TERRAFORM_REVIEW.md`). Pick AWS or GCP and write the actual `.tf` modules. End of week 2: ALB/CloudRun-fronted backend reachable from internet with HTTPS, RDS/Cloud SQL up, S3/GCS bucket + IAM.

### Sprint 3 — Polish + production readiness (week 3)

- Add Google OAuth (1 day).
- Add basic admin panel: 2 pages (`/admin/sessions`, `/admin/companies`) read-only with a refund button (1.5 days).
- Add low-balance email scheduler (0.5 day).
- Reconcile candidate UX: remove `ANSWERING` phase if going voice-only, or label clearly as fallback (0.5 day).
- Wire transcript-download presigned URL endpoint + UI button (0.5 day).
- E2E smoke on staging with one real Recall.ai bot, one real Razorpay test payment (1 day).

### Out of MVP scope (defer)

Polly/Transcribe (Recall covers), bulk imports, JD versioning, Slack notifications, multi-language, branding.

---

## 9. What's surprisingly good

- Migrations V001–V026 are unusually well-written for a startup MVP: composite FKs guaranteeing tenant isolation, JSONB structural CHECKs (V024), GIN indexes (V021), updated_at triggers (V023), wallet integrity trigger (V022).
- Two-chain SecurityConfig with separate filter pipelines for candidates and employers; filters intentionally _not_ declared as beans to avoid servlet-level auto-registration. Most teams get this wrong.
- Worker self-injection pattern (`@Lazy` self-reference) is correctly used in all three async workers to make `@Transactional` actually fire on scheduled-method calls. Subtle, easy to miss, done right.
- Optimistic-lock + reserve/settle/release in WalletService is production-grade.
- Stub mode in StorageService, EmailService, RecallTranscriptClient, WebhookService — same pattern, properly wired off `useLocalStub` / blank API key. Local dev and CI work without external dependencies.

---

## 10. Bottom line

**The application logic is ~75% of an MVP. The infra is 0% of an MVP. Ship-blocker count = 5 P0 items, all of them small and concrete except for Terraform.** Realistic path to launch is 2.5–3 weeks if one engineer focuses purely on this.
