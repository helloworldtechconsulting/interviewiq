# =============================================================================
# edge — Cloudflare. Identical on every cloud.
#
# Arch v4.0 §3 and §10: Cloudflare replaces Route 53 + CloudFront + ACM + AWS
# WAF in one move, at Rs.0, on the free tier — and it is itself cloud-neutral.
#
# It is the one piece of the stack that does NOT move when the compute does,
# because it works with ANY origin. That is why it lives in its own module
# rather than inside a platform module.
#
# THE GAP, stated rather than hidden (Arch v4.0 §9, PRD §8): Cloudflare covers
# the EDGE. It has no equivalent of GuardDuty's workload threat detection. That
# is an accepted trade-off of the portability decision, not an oversight — a
# portable runtime-security tool (Falco) is the answer if a customer contract
# ever requires one.
# =============================================================================

terraform {
  required_version = ">= 1.6"
  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 4.35"
    }
  }
}

# ── DNS ──────────────────────────────────────────────────────────────────────

# The API, proxied so WAF and DDoS protection apply to it.
resource "cloudflare_record" "api" {
  zone_id = var.zone_id
  name    = var.api_subdomain
  content = var.ingress_ip
  type    = "A"
  proxied = true
  comment = "Kubernetes ingress — moves when the compute moves; nothing else here does."
}

# The SPA, served as static assets from object storage.
resource "cloudflare_record" "app" {
  zone_id = var.zone_id
  name    = var.app_subdomain
  content = var.spa_origin_hostname
  type    = "CNAME"
  proxied = true
  comment = "Static SPA from S3-compatible storage, cached at the edge."
}

# ── TLS ──────────────────────────────────────────────────────────────────────

resource "cloudflare_zone_settings_override" "settings" {
  zone_id = var.zone_id

  settings {
    # Full (strict) — TLS 1.3 at the edge AND re-encrypted to origin with a
    # validated certificate (PRD §8). "Flexible" would leave the origin leg in
    # plaintext, which is worse than no TLS because it looks secure.
    ssl                      = "strict"
    min_tls_version          = "1.2"
    tls_1_3                  = "on"
    always_use_https         = "on"
    automatic_https_rewrites = "on"

    # HSTS. Long max-age with preload, because every hostname here is ours and
    # is HTTPS-only.
    security_header {
      enabled            = true
      max_age            = 31536000
      include_subdomains = true
      preload            = true
      nosniff            = true
    }

    brotli     = "on"
    http3      = "on"
    websockets = "on" # the interview room depends on this
  }
}

# ── WAF ──────────────────────────────────────────────────────────────────────

resource "cloudflare_ruleset" "waf" {
  zone_id = var.zone_id
  name    = "interviewiq-waf"
  kind    = "zone"
  phase   = "http_request_firewall_custom"

  # Rate limit the auth surface. PRD §7.1.3 already enforces 5 failed logins per
  # IP per minute in the application; doing it at the edge as well means the
  # volume never reaches a pod at all.
  rules {
    action      = "block"
    expression  = "(http.request.uri.path contains \"/api/v1/auth/\" and rate(1m) > 60)"
    description = "Throttle authentication endpoints at the edge"
    enabled     = true
  }

  # Never rate-limit or challenge the interview room. A candidate mid-interview
  # subjected to a bot challenge loses their interview, and there is no retry
  # that gives it back.
  rules {
    action      = "skip"
    expression  = "(http.request.uri.path contains \"/ws/session/\")"
    description = "Exempt the interview WebSocket from all custom rules"
    enabled     = true

    action_parameters {
      ruleset = "current"
    }
  }

  # The Razorpay webhook is HMAC-verified and fails closed at the application,
  # but blocking non-Razorpay sources at the edge removes the noise entirely.
  rules {
    action      = "block"
    expression  = "(http.request.uri.path eq \"/api/v1/webhooks/razorpay\" and not ip.src in $razorpay_ips)"
    description = "Razorpay webhook accepts only Razorpay source IPs"
    enabled     = var.enforce_webhook_source_ips
  }
}

# ── Caching ──────────────────────────────────────────────────────────────────

resource "cloudflare_ruleset" "cache" {
  zone_id = var.zone_id
  name    = "interviewiq-cache"
  kind    = "zone"
  phase   = "http_request_cache_settings"

  # The API is never cached. Every response is tenant-scoped, and a cached
  # response served to the wrong company would be a cross-tenant data leak.
  rules {
    action     = "set_cache_settings"
    expression = "(http.host eq \"${var.api_subdomain}.${var.zone_name}\")"
    enabled    = true

    action_parameters {
      cache = false
    }
  }

  # The SPA's fingerprinted assets are immutable and cached hard.
  rules {
    action     = "set_cache_settings"
    expression = "(http.host eq \"${var.app_subdomain}.${var.zone_name}\" and http.request.uri.path contains \"/assets/\")"
    enabled    = true

    action_parameters {
      cache = true
      edge_ttl {
        mode    = "override_origin"
        default = 31536000
      }
    }
  }
}
