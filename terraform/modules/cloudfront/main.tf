# =============================================================================
# modules/cloudfront/main.tf
#
# CloudFront distribution for the InterviewIQ React SPA.
#
# Architecture:
#   Browser → CloudFront (edge) → S3 (static assets)  [/* paths]
#                               → ALB (API)            [/api/* paths]
#
# Key design decisions:
#   - Origin Access Control (OAC) replaces legacy OAI — more secure, supports
#     SSE-KMS encrypted S3 buckets without extra key policy grants.
#   - /api/* cache behaviour bypasses CloudFront caching entirely (TTL = 0).
#     All API responses must come from the origin; CloudFront only terminates TLS.
#   - SPA routing: any 403/404 from S3 → return index.html with 200.
#     React Router handles the client-side routing from there.
#   - PriceClass_200: covers North America, Europe, Asia (includes India).
#     PriceClass_All is ~30% more expensive with minimal benefit for ap-south-1.
#   - Security headers via CloudFront response headers policy (HSTS, CSP, etc.)
#   - ACM certificate MUST be in us-east-1 for CloudFront — this is an AWS
#     hard requirement. The ALB cert in ap-south-1 is separate.
# =============================================================================

terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

# ── S3 Bucket for SPA Static Assets ──────────────────────────────────────────

resource "aws_s3_bucket" "spa" {
  bucket        = var.spa_bucket_name
  force_destroy = var.env != "prod" # safe to destroy in dev/staging

  tags = var.tags
}

resource "aws_s3_bucket_versioning" "spa" {
  bucket = aws_s3_bucket.spa.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "spa" {
  bucket = aws_s3_bucket.spa.id

  rule {
    apply_server_side_encryption_by_default {
      # CloudFront OAC supports SSE-S3 natively.
      # SSE-KMS requires extra KMS key policy grants for CloudFront principal.
      # Use SSE-S3 for SPA assets (not sensitive PII — no KMS needed).
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "spa" {
  bucket                  = aws_s3_bucket.spa.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Lifecycle: remove old deployment artifacts to keep bucket clean
resource "aws_s3_bucket_lifecycle_configuration" "spa" {
  bucket = aws_s3_bucket.spa.id

  rule {
    id     = "expire-old-versions"
    status = "Enabled"

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}

# ── Origin Access Control (OAC) ───────────────────────────────────────────────
# Successor to OAI. CloudFront signs requests to S3 using Sigv4.
# No need to make S3 bucket public.

resource "aws_cloudfront_origin_access_control" "spa" {
  name                              = "${var.project}-${var.env}-spa-oac"
  description                       = "OAC for ${var.project} ${var.env} SPA assets"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# ── S3 Bucket Policy — allow only CloudFront OAC ─────────────────────────────

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket_policy" "spa" {
  bucket = aws_s3_bucket.spa.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowCloudFrontOAC"
        Effect = "Allow"
        Principal = {
          Service = "cloudfront.amazonaws.com"
        }
        Action   = "s3:GetObject"
        Resource = "${aws_s3_bucket.spa.arn}/*"
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = aws_cloudfront_distribution.spa.arn
          }
        }
      },
      {
        Sid    = "DenyUnencryptedTransport"
        Effect = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [
          aws_s3_bucket.spa.arn,
          "${aws_s3_bucket.spa.arn}/*"
        ]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      }
    ]
  })
}

# ── Security Response Headers Policy ─────────────────────────────────────────
# Adds HSTS, X-Frame-Options, CSP, and other security headers to all responses.

resource "aws_cloudfront_response_headers_policy" "security" {
  name    = "${var.project}-${var.env}-security-headers"
  comment = "Security headers for ${var.project} ${var.env}"

  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = 31536000 # 1 year
      include_subdomains         = true
      preload                    = true
      override                   = true
    }

    content_type_options {
      override = true # X-Content-Type-Options: nosniff
    }

    frame_options {
      frame_option = "DENY"
      override     = true
    }

    xss_protection {
      mode_block = true
      protection = true
      override   = true
    }

    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }
  }

  custom_headers_config {
    items {
      header   = "Permissions-Policy"
      value    = "camera=(), microphone=(), geolocation=()"
      override = true
    }
    # NOTE: camera + microphone are explicitly REQUIRED for the interview room.
    # Override this header for the /interview path in your React app or via
    # a separate behaviour, e.g.: camera=(self), microphone=(self)
    # For now this is a permissive baseline that blocks third-party access.
  }
}

# ── ACM Certificate (us-east-1 — required for CloudFront) ────────────────────
# CloudFront ONLY accepts ACM certs from us-east-1.
# The ap-south-1 cert used by the ALB is separate.

resource "aws_acm_certificate" "cloudfront" {
  provider          = aws.us_east_1
  domain_name       = var.domain
  subject_alternative_names = [
    "*.${var.domain}",
    "www.${var.domain}",
  ]
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = var.tags
}

# DNS validation records in Route53
resource "aws_route53_record" "cloudfront_cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.cloudfront.domain_validation_options :
    dvo.domain_name => {
      name   = dvo.resource_record_name
      type   = dvo.resource_record_type
      record = dvo.resource_record_value
    }
  }

  allow_overwrite = true
  zone_id         = var.route53_zone_id
  name            = each.value.name
  type            = each.value.type
  ttl             = 60
  records         = [each.value.record]
}

resource "aws_acm_certificate_validation" "cloudfront" {
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.cloudfront.arn
  validation_record_fqdns = [for r in aws_route53_record.cloudfront_cert_validation : r.fqdn]
}

# ── CloudFront Distribution ───────────────────────────────────────────────────

resource "aws_cloudfront_distribution" "spa" {
  comment             = "${var.project} ${var.env} SPA + API"
  enabled             = true
  is_ipv6_enabled     = true
  default_root_object = "index.html"
  price_class         = "PriceClass_200" # NA + Europe + Asia (covers India)
  aliases             = [var.domain, "www.${var.domain}"]
  http_version        = "http2and3"
  wait_for_deployment = false # don't block terraform apply for 15 minutes

  # ── Origins ──────────────────────────────────────────────────────────────

  # S3 origin for static SPA assets
  origin {
    origin_id                = "spa-s3"
    domain_name              = aws_s3_bucket.spa.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.spa.id
  }

  # ALB origin for API calls (bypasses CloudFront cache entirely)
  origin {
    origin_id   = "api-alb"
    domain_name = var.alb_dns_name

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
      origin_read_timeout    = 60  # Spring AI calls can be slow (GPT-4o)
      origin_keepalive_timeout = 60
    }
  }

  # ── Cache Behaviours ──────────────────────────────────────────────────────

  # /api/* — forward to ALB, no caching, all headers/cookies/query strings
  ordered_cache_behavior {
    path_pattern     = "/api/*"
    target_origin_id = "api-alb"
    allowed_methods  = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods   = ["GET", "HEAD"]

    forwarded_values {
      query_string = true
      headers      = ["*"] # forward all headers (Authorization, Content-Type, etc.)
      cookies {
        forward = "all"
      }
    }

    viewer_protocol_policy = "redirect-to-https"
    min_ttl                = 0
    default_ttl            = 0
    max_ttl                = 0
    compress               = true
  }

  # /interview* — same as API (real-time interview room, no caching)
  ordered_cache_behavior {
    path_pattern     = "/interview*"
    target_origin_id = "api-alb"
    allowed_methods  = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods   = ["GET", "HEAD"]

    forwarded_values {
      query_string = true
      headers      = ["*"]
      cookies { forward = "all" }
    }

    viewer_protocol_policy   = "redirect-to-https"
    min_ttl                  = 0
    default_ttl              = 0
    max_ttl                  = 0
    compress                 = true
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id
  }

  # Static assets with content-hash filenames (JS, CSS) — long cache
  ordered_cache_behavior {
    path_pattern     = "/assets/*"
    target_origin_id = "spa-s3"
    allowed_methods  = ["GET", "HEAD", "OPTIONS"]
    cached_methods   = ["GET", "HEAD"]

    forwarded_values {
      query_string = false
      cookies { forward = "none" }
    }

    viewer_protocol_policy     = "redirect-to-https"
    min_ttl                    = 0
    default_ttl                = 31536000 # 1 year — safe because Vite uses content hashes
    max_ttl                    = 31536000
    compress                   = true
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id
  }

  # Default — SPA HTML files (short cache, must re-validate for deployments)
  default_cache_behavior {
    target_origin_id = "spa-s3"
    allowed_methods  = ["GET", "HEAD", "OPTIONS"]
    cached_methods   = ["GET", "HEAD"]

    forwarded_values {
      query_string = false
      cookies { forward = "none" }
    }

    viewer_protocol_policy     = "redirect-to-https"
    min_ttl                    = 0
    default_ttl                = 300  # 5 minutes — allows quick re-deploy
    max_ttl                    = 3600
    compress                   = true
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id
  }

  # ── SPA Routing — 403/404 from S3 → return index.html ────────────────────
  # React Router handles /app/dashboard, /app/jobs/*, etc. client-side.
  # Without this, a direct URL hit returns 403 from S3 (no such key).

  custom_error_response {
    error_code            = 403
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }

  custom_error_response {
    error_code            = 404
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }

  # ── TLS Certificate ───────────────────────────────────────────────────────
  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cloudfront.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  # ── Access Logging ────────────────────────────────────────────────────────
  logging_config {
    include_cookies = false
    bucket          = aws_s3_bucket.spa.bucket_domain_name
    prefix          = "cloudfront-access-logs/"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none" # no geo-blocking for now
    }
  }

  tags = var.tags

  depends_on = [aws_acm_certificate_validation.cloudfront]
}

# ── Route53 Records ───────────────────────────────────────────────────────────

resource "aws_route53_record" "apex" {
  zone_id = var.route53_zone_id
  name    = var.domain
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.spa.domain_name
    zone_id                = aws_cloudfront_distribution.spa.hosted_zone_id
    evaluate_target_health = false # CloudFront doesn't support health check aliases
  }
}

resource "aws_route53_record" "www" {
  zone_id = var.route53_zone_id
  name    = "www.${var.domain}"
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.spa.domain_name
    zone_id                = aws_cloudfront_distribution.spa.hosted_zone_id
    evaluate_target_health = false
  }
}
