# Testing Guide — InterviewIQ

This document describes the test suite layout, the **current state** of each
tier (what runs, what's @Disabled and why), how to run it locally, and how the
CI/CD reports are published.

## Test tiers

| Tier | Lives in | Picked up by | Runs on |
|------|----------|--------------|---------|
| Unit tests (Mockito, no Spring) | `src/test/java/**/*Test.java` | Surefire (`mvn test`) | Every push and PR |
| Spring Boot integration tests (Testcontainers + MockMvc) | `src/test/java/**/*IT.java` | Failsafe + `integration` profile (`mvn verify -Pintegration`) | Every push and PR (requires Docker) |
| Selenium UI smoke tests (headless Chrome) | `src/test/java/com/interviewiq/ui/**/*UITest.java` | Failsafe + `selenium` profile (`-Dui.e2e=true`) | Nightly + on demand |
| Frontend component tests (Vitest + RTL) | `frontend/src/__tests__/**/*.test.{ts,tsx}` | Vitest (`npm test`) | Every push and PR |

## Current pass/skip counts (as of last update)

### Backend unit tests (`mvn test`)
- **60 tests run, 0 failures, 25 skipped.**
- Skipped tests are stubs (empty method bodies) for services whose contract
  needs more detective work to specify safely. Each is annotated with
  `@Disabled("TODO: …")`. See "Disabled tests inventory" below.

### Backend integration tests (`mvn verify -Pintegration`)
**Cannot run in this sandbox — requires Docker for Testcontainers.** Compiles
cleanly and is structurally sound. Designed to run on CI runners with Docker.

Currently un-disabled and ready to run on CI:
- `AuthorizationIT` — 401 without JWT, 401 with malformed JWT.
- `CompanyOnboardingIT` — happy path + duplicate slug + check-slug.
- `JobLifecycleIT` — 401 without JWT (full happy path is one nested @Disabled
  test pending the OTP capture seam — see `AuthFlowIT`).
- `WebhookIT` — Razorpay webhook valid HMAC accepted; invalid HMAC rejected
  with a 4xx. Idempotency is asserted at the unit level
  (`WalletServiceTest#confirmTopUp_isIdempotent_whenOrderAlreadyCredited`).
- `AuthFlowIT.registerLeg_returns201` — registration smoke test.

Still entirely @Disabled at the class level (need authenticated-helper):
- `BillingIT`, `SessionLifecycleIT`, `TeamManagementIT`,
  `CandidateLifecycleIT`. Each carries a clear TODO. The blocker is the same
  for all of them: the auth funnel currently issues OTPs whose raw value is
  only sent via email (the DB stores a BCrypt hash). To unblock, add a
  test-profile seam — for example, a Mockito spy on
  `OtpService.sendOtp(...)` that captures the raw OTP into a thread-local —
  then build a `registerCompanyAndLogin()` helper on `AbstractIntegrationTest`.

### Selenium UI tests (`mvn verify -Pselenium`)
Gated on the `-Dui.e2e=true` system property. Will not run unless the frontend
is reachable on `http://localhost:3000` (start the docker-compose stack first).
These tests are intentionally a tiny smoke set — login page renders, dashboard
nav works, job-create flow opens — not a full UAT suite.

### Frontend (`npm test`)
- **3 test files, 6 tests, 0 failures.**
- `LoginPage.test.tsx`, `ProtectedRoute.test.tsx`, `useDebounce.test.ts`.
- Components are inlined in the test files for now (the previous agent didn't
  finish wiring real component imports — when the frontend stabilises, switch
  to importing the actual modules from `src/`).

## Disabled tests inventory

Service-level unit tests that are stubs awaiting body implementation:
- `auth/service/...` — most pass, see the failing ones in TODO comments.
- `company/service/CompanyServiceTest` — 3 stubs.
- `job/service/JobServiceTest` — 3 stubs.
- `session/service/SessionServiceTest` — 4 stubs.
- `team/service/TeamServiceTest` — 3 stubs.
- `email/service/EmailServiceTest` — 2 stubs.
- `ai/service/QuestionGenerationWorkerTest` — 3 stubs.
- `ai/service/EvaluationWorkerTest` — 2 stubs.
- `candidate/service/CandidateServiceTest` — stubs.
- `storage/service/StorageServiceTest` — stubs.

Each carries `@Disabled("TODO: enable once <Service> signatures are confirmed")`
to make the gap visible without breaking the build.

## Running tests locally

### Backend
```bash
# Unit tests only — fast, no Docker required
./mvnw test

# Unit + Spring Boot integration tests (requires Docker for Testcontainers)
./mvnw verify -Pintegration

# Selenium UI smoke tests (requires the docker-compose stack running)
docker compose up -d
./mvnw verify -Pselenium -Dui.e2e=true
```

JaCoCo coverage HTML lives under `target/site/jacoco/index.html` after a
successful `mvn verify`.

The Surefire / Failsafe HTML reports are produced by:
```bash
./mvnw surefire-report:report-only surefire-report:failsafe-report-only \
    site:site -DgenerateReports=false
```
Output: `target/site/surefire-report.html`, `target/site/failsafe-report.html`.

### Frontend
```bash
cd frontend
npm install
npm test                # watch mode
npm run test:coverage   # writes HTML coverage to ./coverage/
```

## CI artifacts

`.github/workflows/test-report.yml` runs on every push to `main`/`develop` and
on every PR.
- Surefire + Failsafe XML are uploaded as the `test-reports` artifact.
- JaCoCo HTML coverage is uploaded as the `jacoco-coverage` artifact.
- `dorny/test-reporter` posts inline test results on the PR.
- `marocchino/sticky-pull-request-comment` posts a coverage summary comment.
- On `main` only, the HTML reports are mirrored to `gh-pages` under
  `/test-report` and `/coverage` (browse at
  `https://<owner>.github.io/<repo>/`).

`.github/workflows/e2e.yml` runs the Selenium smoke tests nightly (or on
manual trigger). It boots the full docker-compose stack, waits for the
frontend to come up, then runs `mvn verify -Pselenium`.

`.github/workflows/ci.yml` is the gate-keeping workflow that runs the unit and
integration tiers and refuses to merge if either fails.

## When a test fails on CI

1. Open the failed run and download the `test-reports` artifact.
2. Look at `target/surefire-reports/TEST-<class>.xml` (or
   `target/failsafe-reports/...` for IT tests) for the stack trace.
3. Reproduce locally:
   ```bash
   ./mvnw test -Dtest=ClassName#methodName
   ./mvnw verify -Pintegration -Dit.test=ClassNameIT#methodName
   ```
4. For integration tests, ensure Docker is running (Testcontainers needs it).
5. For UI tests, ensure `docker compose up` is running and reachable on
   `http://localhost:3000`.

## Test design principles

- **Unit tests never start Spring.** Mock collaborators with Mockito, assert
  state with AssertJ.
- **Integration tests use the singleton `PostgresTestContainer`.** Spawning a
  fresh container per class blows up the run time — Testcontainers reuse keeps
  it under control.
- **Every external SDK is mocked.** `StubAwsConfig` covers S3 / SES / Polly,
  `StubExternalConfig` covers Razorpay. The Spring AI `ChatClient` tolerates a
  dummy API key in the `test` profile.
- **All request bodies use snake_case JSON.** Matches the backend's Jackson
  configuration (`PropertyNamingStrategies.SNAKE_CASE`).
- **`@Disabled` is acceptable as a placeholder** while a test is being
  designed. The compile-time guarantee is the floor; once the corresponding
  service or controller is stable, remove the annotation and flesh out the
  body. Every disabled test must carry a clear TODO comment explaining what
  is needed.

## Known limitations / next steps

1. **OTP capture seam.** Add either a Mockito spy on `OtpService.sendOtp` or a
   profile-gated `peekRawForTest(email)` method to unblock the full auth
   funnel and the four blocked IT classes (Billing/Session/Team/Candidate).
2. **Auth helper on `AbstractIntegrationTest`.** Once (1) is in, add a
   `registerCompanyAndLogin()` method that returns `(slug, accessToken)`. The
   four blocked ITs above can then exercise their full flows.
3. **Service-test stubs.** Roughly 25 stubbed unit-test method bodies await
   implementation — see the inventory above. None block compilation.
4. **Frontend tests** currently inline component bodies. When the frontend's
   public surface stabilises, switch to importing the real modules from
   `frontend/src/`.
