# InterviewIQ Docker Compose Stacks

Three compose files cover the three environments. Pick the one that matches
what you're trying to do.

| File | Purpose | When to use |
| --- | --- | --- |
| `docker-compose.yml` | Dev stack (default) — Postgres + MinIO + MailHog + optional app/frontend/pgAdmin | Local development on your laptop |
| `docker-compose.test.yml` | Lightweight Postgres-only stack for CI / integration tests | `mvn verify`, GitHub Actions |
| `docker-compose.prod.yml` | Production overlay — prebuilt images, real AWS, no dev infra | Server deploy with real secrets |

All three live at the repo root. `docker/` holds supporting assets (this
README, `postgres-init/` bootstrap SQL).

---

## Quick-start commands

```bash
# 1. DB + supporting infra only — run app from your IDE / Maven
docker compose up -d

# 2. Full stack (DB + infra + backend + frontend) in one command
docker compose --profile app up -d

# 3. Add pgAdmin DB browser
docker compose --profile app --profile tools up -d

# 4. Production deploy (requires .env with real secrets + APP_IMAGE/FRONTEND_IMAGE)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

Tear down (preserve volumes): `docker compose down`
Tear down + wipe data: `docker compose down -v`

For the integration test suite:

```bash
docker compose -f docker-compose.test.yml up -d
mvn verify -Dspring.profiles.active=test \
  -Dspring.datasource.url=jdbc:postgresql://localhost:5433/interviewiq_test
docker compose -f docker-compose.test.yml down -v
```

---

## Recommended workflow

For day-to-day development, **run `docker compose up -d` and start the
Spring Boot app from your IDE** (`SPRING_PROFILES_ACTIVE=local`). You get:

- Hot-reload, debugger attach, fast incremental builds.
- A real Postgres at `localhost:5432` (already populated with seed data via
  Flyway's `db/seed/R__dev_seed_data.sql`).
- A separate `interviewiq_test` DB on the same instance, ready for `mvn test`.
- MinIO and MailHog reachable for any code path that needs an S3 / SMTP
  endpoint (the app currently runs with `app.aws.use-local-stub=true` so it
  doesn't hit them — see "Caveats" below).

Use `--profile app` only when you specifically need to test the containerised
build (release rehearsal, frontend-served-by-nginx integration, etc.).

---

## Port layout

| Service | Container port | Host port | Notes |
| --- | --- | --- | --- |
| Postgres (dev) | 5432 | **5432** | `DB_PORT_EXPOSED` to remap |
| Postgres (test) | 5432 | **5433** | Runs alongside dev |
| Backend (Spring Boot) | 8080 | **8080** | `--profile app` only |
| Frontend (Nginx) | 80 | **3000** | `--profile app` only |
| MinIO API | 9000 | **9000** | S3-compatible endpoint |
| MinIO Console | 9001 | **9001** | http://localhost:9001 |
| MailHog SMTP | 1025 | **1025** | smtp://mailhog:1025 (in-network) |
| MailHog UI | 8025 | **8025** | http://localhost:8025 |
| pgAdmin | 80 | **5050** | `--profile tools` only |

---

## Environment variables

The dev stack runs without any `.env` file — every variable has a sensible,
non-secret default. Copy `.env.example` -> `.env` only if you want to:

- Connect to real OpenAI / Razorpay / Recall.ai for end-to-end tests.
- Use real RSA keypair PEMs instead of ephemeral keys.
- Override host port bindings (`DB_PORT_EXPOSED`, `APP_PORT`, etc.).

**Production requires every secret variable** (see the header of
`docker-compose.prod.yml` for the full list — compose will refuse to start if
any are missing thanks to `${VAR:?required}`).

### Default credentials (DEV ONLY — never use in prod)

| Where | User | Password |
| --- | --- | --- |
| Postgres | `interviewiq` | `interviewiq_secret` |
| MinIO | `interviewiq` | `interviewiq_secret` |
| pgAdmin | `admin@interviewiq.local` | `admin` |

These are intentionally checked into the repo so a fresh clone runs without
config. Override every one of them in production via `.env`.

---

## Caveats / tradeoffs worth knowing

1. **MinIO is available infra but the app does not currently call it.** The
   Spring app uses `app.aws.use-local-stub=true` in the `local` profile,
   meaning S3 calls are short-circuited to log statements. MinIO is wired up
   (and its bucket auto-created on first run) so that future endpoint-override
   support — or any integration tests that hit the S3 SDK directly — Just
   Works. The `AWS_ENDPOINT_URL_S3` env var is set on the app container for
   when that switch flips.
2. **MailHog is similarly available but unused by default.** The app
   currently sends email through AWS SES, not SMTP. If you wire an SMTP
   sender it can target `smtp://mailhog:1025` to capture all outbound mail
   in the http://localhost:8025 web UI.
3. **The `app` and `frontend` build is not the recommended dev loop.**
   Building the backend image takes ~2 minutes. Run from your IDE for the
   fast iteration loop and only `--profile app` for release-style smoke tests.
4. **The test compose's tmpfs data dir is wiped on `docker compose down`.**
   That's the point — every run starts from a known-empty DB. If you want
   to inspect failures, use `docker compose -f docker-compose.test.yml stop`
   instead, then `exec` into the container.
5. **The prod overlay assumes you push prebuilt images** to a registry
   (`APP_IMAGE`, `FRONTEND_IMAGE`). It will not build images from the
   repo — that's CI's job. For RDS-backed deploys, omit the `postgres`
   service entirely (`docker compose -f docker-compose.yml -f docker-compose.prod.yml up app frontend`).
6. **Postgres init scripts only run on first volume creation.** If you need
   to re-run `01-init.sql` (e.g. after editing it), `docker compose down -v`
   to drop the volume.

---

## File map

```
docker-compose.yml          # base / dev stack
docker-compose.test.yml     # CI / integration tests (Postgres only)
docker-compose.prod.yml     # production overlay (use with -f base -f prod)
docker/
  README.md                 # this file
  postgres-init/
    01-init.sql             # creates interviewiq_test DB on first volume init
```
