# InterviewIQ

**AI-powered interview platform** that streamlines the hiring process for employers and candidates — from job posting and candidate management to live AI-assisted interview sessions with automated billing.

---

## Features

- **AI Interview Sessions** — Real-time interview rooms with AI assistance powered by OpenAI
- **Resume Parsing** — Automatic text extraction from PDF/DOCX resumes via Apache Tika
- **Candidate Management** — Full pipeline from application to offer
- **Job Posting & Management** — Rich job listing creation with employer dashboards
- **Team Management** — Multi-member employer teams with role-based access
- **Billing & Subscriptions** — Per-session billing integrated with Razorpay
- **Audio Transcription** — Live speech-to-text via AWS Transcribe Streaming
- **File Storage** — Resume and asset storage on AWS S3
- **Email Notifications** — Transactional emails via AWS SES
- **Rate Limiting** — In-process token-bucket rate limiting with Bucket4j
- **API Documentation** — Auto-generated Swagger UI at `/swagger-ui.html`

---

## Tech Stack

### Backend
| Layer | Technology |
|---|---|
| Runtime | Java 21 |
| Framework | Spring Boot 3.3 |
| Security | Spring Security + JWT (JJWT) |
| Database | PostgreSQL 15 + Spring Data JPA (Hibernate 6) |
| Migrations | Flyway |
| AI | Spring AI 1.0 + OpenAI |
| Storage | AWS S3 |
| Email | AWS SES |
| Transcription | AWS Transcribe Streaming |
| TTS | AWS Polly |
| Payments | Razorpay |
| Document Parsing | Apache Tika |
| Observability | Spring Actuator + Micrometer + Prometheus |

### Frontend
| Layer | Technology |
|---|---|
| Framework | React 18 + TypeScript 5 |
| Build Tool | Vite 5 |
| Routing | React Router DOM 7 |
| Server State | TanStack React Query v5 |
| Client State | Zustand |
| HTTP Client | Axios |
| Forms | React Hook Form + Zod |
| Styling | Tailwind CSS + Radix UI primitives |
| Toasts | Sonner |
| Icons | Lucide React |
| Charts | Recharts |
| Testing | Vitest + Testing Library + MSW |

### Infrastructure
- **Docker** + **Docker Compose** (PostgreSQL, Spring Boot app, React/Nginx)
- Multi-stage Dockerfile for optimized production images

---

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.8+
- Node.js 18+ & npm
- Docker & Docker Compose
- PostgreSQL 15 (or use the included Docker Compose setup)

---

### Local Development

**1. Clone the repository**

```bash
git clone https://github.com/your-username/interviewiq.git
cd interviewiq
```

**2. Start PostgreSQL via Docker**

```bash
docker compose up -d
```

**3. Configure environment**

The repository ships with `src/main/resources/application-local.yml` pre-configured for local Docker Compose. All required properties already have safe local defaults:

- Database connects to `localhost:5432/interviewiq_dev` (Docker credentials)
- OpenAI API key is set to a stub value — replace with a real key only if you need to test AI features
- JWT keys are empty strings, which auto-generates an ephemeral RSA key pair at startup
- AWS is stubbed (`use-local-stub: true`) — no real S3 or SES calls are made
- Flyway includes `db/seed` for local dev data and `clean-on-validation-error: true` for automatic schema repair

To test real AI features locally, set `OPENAI_API_KEY` as an environment variable or update the `api-key` entry in `application-local.yml`.

For Razorpay, Recall.ai, and AWS integrations, override the stub values in `application-local.yml` or via environment variables.

**4. Run the backend**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The API will be available at `http://localhost:8080`.
Swagger UI: `http://localhost:8080/swagger-ui.html`

**5. Run the frontend**

```bash
cd frontend
npm install
npm run dev
```

The frontend will be available at `http://localhost:5173`.

---

### Production (Full Stack with Docker)

**1. Set up environment variables**

```bash
cp .env.example .env
# Edit .env with your real production values
```

**2. Start all services**

```bash
docker compose --profile production up -d
```

This starts:
- PostgreSQL on port `5432`
- Spring Boot API on port `8080`
- React/Nginx frontend on port `3000`

---

## Project Structure

```
interviewiq/
├── src/
│   └── main/
│       └── java/com/interviewiq/
│           ├── ai/           # AI session & prompt logic
│           ├── auth/         # Authentication & JWT
│           ├── billing/      # Razorpay payment integration
│           ├── candidate/    # Candidate management
│           ├── company/      # Company profiles
│           ├── email/        # Email notifications
│           ├── job/          # Job posting & management
│           ├── session/      # Interview session orchestration
│           ├── storage/      # AWS S3 file storage
│           ├── team/         # Employer team management
│           ├── webhook/      # Webhook handlers (Recall.ai)
│           └── shared/       # Shared utilities & config
├── frontend/
│   └── src/
│       ├── pages/
│       │   ├── candidate/    # Candidate-facing pages
│       │   └── employer/     # Employer dashboard pages
│       ├── components/       # Reusable UI components
│       ├── api/              # API client layer
│       ├── stores/           # Zustand state stores
│       └── hooks/            # Custom React hooks
├── docker-compose.yml
├── Dockerfile
└── pom.xml
```

---

## Environment Variables

| Variable | Description |
|---|---|
| `DB_HOST` | PostgreSQL host (default: `localhost`) |
| `DB_PORT` | PostgreSQL port (default: `5432`) |
| `DB_NAME` | PostgreSQL database name |
| `DB_USERNAME` | PostgreSQL username |
| `DB_PASSWORD` | PostgreSQL password |
| `OPENAI_API_KEY` | OpenAI API key for AI features |
| `APP_SECURITY_JWT_PRIVATE_KEY_PEM` | RSA private key (PEM) for JWT signing |
| `APP_SECURITY_JWT_PUBLIC_KEY_PEM` | RSA public key (PEM) for JWT verification |
| `APP_SECURITY_INVITE_SECRET` | Secret for generating invite links |
| `RAZORPAY_KEY_ID` | Razorpay payment gateway key ID |
| `RAZORPAY_KEY_SECRET` | Razorpay payment gateway secret |
| `RECALL_API_KEY` | Recall.ai bot API key |
| `RECALL_WEBHOOK_SECRET` | Recall.ai webhook verification secret |
| `AWS_REGION` | AWS region (default: `ap-south-1`) |
| `AWS_S3_BUCKET` | S3 bucket for file storage |
| `AWS_ACCESS_KEY_ID` | AWS access key |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key |
| `APP_FRONTEND_BASE_URL` | Frontend URL for email links |

---

## API Reference

Interactive API documentation is available via Swagger UI once the server is running:

```
http://localhost:8080/swagger-ui.html
```

Health check endpoint:

```
GET /actuator/health
```

---

## License

This project is proprietary. All rights reserved.
