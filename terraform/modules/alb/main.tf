# =============================================================================
# modules/alb/main.tf
#
# Application Load Balancer for InterviewIQ
#
# - HTTPS (443) only in prod/staging; HTTP (80) → redirect to HTTPS
# - HTTP (80) in dev (no cert needed for localhost-style testing)
# - Security Group: only 80/443 inbound from internet
# - Access logs → S3 (compliance + debugging)
# - Deletion protection in prod
# =============================================================================

# ── ALB Security Group ────────────────────────────────────────────────────────

resource "aws_security_group" "alb" {
  name        = "${var.project}-${var.env}-alb-sg"
  description = "Internet-facing ALB for ${var.project} ${var.env}"
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTP from internet"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS from internet"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "To ECS tasks"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  tags = merge(var.tags, { Name = "${var.project}-${var.env}-alb-sg" })
}

# ── ALB Access Logs Bucket ────────────────────────────────────────────────────

resource "aws_s3_bucket" "alb_logs" {
  bucket        = "${var.project}-${var.env}-alb-access-logs-${data.aws_caller_identity.current.account_id}"
  force_destroy = var.env != "prod"
  tags          = var.tags
}

data "aws_caller_identity" "current" {}
data "aws_elb_service_account" "main" {}

resource "aws_s3_bucket_public_access_block" "alb_logs" {
  bucket                  = aws_s3_bucket.alb_logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id
  rule {
    id     = "expire-logs"
    status = "Enabled"
    expiration {
      days = var.env == "prod" ? 90 : 14
    }
  }
}

resource "aws_s3_bucket_policy" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { AWS = data.aws_elb_service_account.main.arn }
      Action    = "s3:PutObject"
      Resource  = "${aws_s3_bucket.alb_logs.arn}/alb/*"
    }]
  })
}

# ── Application Load Balancer ─────────────────────────────────────────────────

resource "aws_lb" "main" {
  name               = "${var.project}-${var.env}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = var.public_subnet_ids

  enable_deletion_protection = var.env == "prod"
  enable_http2               = true
  idle_timeout               = 60

  access_logs {
    bucket  = aws_s3_bucket.alb_logs.id
    prefix  = "alb"
    enabled = true
  }

  tags = merge(var.tags, { Name = "${var.project}-${var.env}-alb" })
}

# ── Target Group ──────────────────────────────────────────────────────────────

resource "aws_lb_target_group" "backend" {
  name        = "${var.project}-${var.env}-backend-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip" # required for Fargate awsvpc networking

  health_check {
    path                = "/actuator/health"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 30
    timeout             = 10
    matcher             = "200"
  }

  deregistration_delay = 30 # matches Spring graceful shutdown timeout

  tags = merge(var.tags, { Name = "${var.project}-${var.env}-backend-tg" })
}

# ── HTTPS Listener (prod + staging) ──────────────────────────────────────────

resource "aws_lb_listener" "https" {
  count             = var.create_https_listener ? 1 : 0
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06" # TLS 1.3 preferred
  certificate_arn   = var.acm_certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }
}

# ── HTTP → HTTPS Redirect ─────────────────────────────────────────────────────

resource "aws_lb_listener" "http_redirect" {
  count             = var.create_https_listener ? 1 : 0
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# ── HTTP Listener (dev only — no cert) ───────────────────────────────────────

resource "aws_lb_listener" "http" {
  count             = var.create_https_listener ? 0 : 1
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }
}

# ── Razorpay Webhook Listener Rule ────────────────────────────────────────────
# Razorpay sends webhooks to /api/v1/webhooks/razorpay — normal routing covers it.
# If you need IP allowlisting for Razorpay IPs, add a WAF rule (see waf module).
