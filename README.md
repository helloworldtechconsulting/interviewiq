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
| Framework | React + TypeScript |
| Build Tool | Vite |
| Routing | React Router |
| State | Zustand |
| Styling | Tailwind CSS |

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

Create `src/main/resources/application-local.yml` and set the required properties:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/interviewiq_dev
    username: interviewiq
    password: interviewiq_secret
  ai:
    openai:
      api-key: your-openai-api-key

app:
  security:
    jwt:
      private-key-pem: |
        -----BEGIN PRIVATE KEY-----
        ...
        -----END PRIVATE KEY-----
      public-key-pem: |
        -----BEGIN PUBLIC KEY-----
        ...
        -----END PUBLIC KEY-----

aws:
  region: ap-south-1
  s3:
    bucket: your-s3-bucket
  access-key-id: your-access-key
  secret-access-key: your-secret-key
```

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
