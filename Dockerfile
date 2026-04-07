# =============================================================================
# Dockerfile — InterviewIQ Backend
# Multi-stage build: Maven builder → slim JRE runner
#
# Stages
#   1. deps     — download Maven dependencies (cached layer; only re-runs when
#                 pom.xml changes, not when source code changes)
#   2. builder  — compile source + package fat JAR (re-runs on any src change)
#   3. runner   — minimal JRE image; copies only the JAR; runs as non-root
#
# Build
#   docker build -t interviewiq-backend .
#
# Run (local smoke-test against a running Postgres)
#   docker run --rm \
#     -e SPRING_PROFILES_ACTIVE=prod \
#     -e DB_HOST=host.docker.internal \
#     -e DB_PORT=5432 \
#     -e DB_NAME=interviewiq \
#     -e DB_USERNAME=interviewiq \
#     -e DB_PASSWORD=secret \
#     -p 8080:8080 \
#     interviewiq-backend
# =============================================================================

# ── Stage 1: dependency cache ─────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS deps

WORKDIR /build

# Copy only the POM first — Docker layer cache means this expensive step
# (downloading ~300 MB of jars) only re-runs when pom.xml changes.
COPY pom.xml .
RUN mvn dependency:go-offline -B -q


# ── Stage 2: compile and package ─────────────────────────────────────────────
FROM deps AS builder

# Copy source tree on top of the cached dependency layer
COPY src ./src

# Build the fat JAR; skip tests (tests run in CI before the Docker build step)
RUN mvn package -DskipTests -B -q


# ── Stage 3: minimal runtime image ───────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runner

# ── Metadata ──────────────────────────────────────────────────────────────────
LABEL org.opencontainers.image.title="InterviewIQ Backend" \
      org.opencontainers.image.description="AI-powered interview platform — Spring Boot 3.3 / Java 21" \
      org.opencontainers.image.source="https://github.com/your-org/interviewiq"

# ── Security: non-root user ───────────────────────────────────────────────────
# Running as root inside a container is unnecessary and increases blast radius.
# Create a dedicated app user with no home directory and no shell.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the fat JAR from the builder stage.
# The wildcard handles any version suffix (0.0.1-SNAPSHOT, 1.0.0, etc.)
COPY --from=builder --chown=appuser:appgroup \
     /build/target/interviewiq-backend-*.jar app.jar

USER appuser

# ── JVM tuning ────────────────────────────────────────────────────────────────
# -XX:+UseContainerSupport  — honour container CPU/memory limits (default in JDK 17+)
# -XX:MaxRAMPercentage=75   — give the JVM 75% of container RAM; leave 25% for
#                             the OS, Metaspace, and off-heap buffers
# -Djava.security.egd      — use /dev/urandom to avoid blocking on entropy
#                             (important for fast JWT generation in containers)
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

# ── Health check ──────────────────────────────────────────────────────────────
# Docker and compose will use this to know when the app is ready.
# /actuator/health is permit-all and returns 200 when Spring is up + DB is reachable.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
