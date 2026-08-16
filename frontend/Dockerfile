# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM node:22-alpine AS builder

WORKDIR /app

# Install deps first (layer cache)
COPY package*.json ./
RUN npm ci --prefer-offline

# Copy source and build
COPY . .

# ── Build-time configuration ─────────────────────────────────────────────────
# Vite inlines every VITE_* value into the bundle at build time. There is no
# runtime override: changing any of these requires rebuilding the image.
# Every VITE_* referenced anywhere in src/ must be declared here, or it is
# silently baked in as `undefined`.

# Base URL of the Spring Boot API. Empty = same origin, Nginx proxies /api.
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

# Tenant slug used in every auth path: /api/v1/{slug}/auth/*
# Defaults to the dev seed tenant. A production build MUST pass the real slug —
# an unset value points auth at interviewengine-dev with no visible error.
ARG VITE_COMPANY_SLUG="interviewengine-dev"
ENV VITE_COMPANY_SLUG=$VITE_COMPANY_SLUG

# Google OAuth 2.0 Client ID. Blank disables the Google sign-in button.
ARG VITE_GOOGLE_CLIENT_ID=""
ENV VITE_GOOGLE_CLIENT_ID=$VITE_GOOGLE_CLIENT_ID

RUN npm run build

# ── Stage 2: serve with Nginx ─────────────────────────────────────────────────
FROM nginx:1.27-alpine AS runner

# Remove default nginx static assets
RUN rm -rf /usr/share/nginx/html/*

# Copy built assets
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy nginx config
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
