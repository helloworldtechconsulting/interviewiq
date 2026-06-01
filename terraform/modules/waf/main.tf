# =============================================================================
# modules/waf/main.tf
#
# AWS WAFv2 Web ACL attached to the Application Load Balancer.
#
# Rule set (evaluated in priority order, lowest number = highest priority):
#
#   1.  AWS Managed — Core Rule Set (CRS)          P=10  BLOCK
#       Protects against OWASP Top 10: SQLi, XSS, command injection,
#       HTTP protocol violations, and common web exploits.
#
#   2.  AWS Managed — Known Bad Inputs              P=20  BLOCK
#       Blocks requests with payloads associated with CVEs and common attacks.
#
#   3.  AWS Managed — IP Reputation List            P=30  BLOCK
#       Blocks IPs from Amazon's threat intelligence list (botnets, scrapers).
#
#   4.  AWS Managed — Anonymous IP List             P=40  COUNT (not block)
#       Flags requests from TOR exits and anonymous proxies. Counted only —
#       InterviewIQ candidates may legitimately use VPNs.
#
#   5.  Rate limiting — global (all IPs)            P=100 BLOCK
#       Blocks IPs that send > 2000 requests per 5 minutes across all paths.
#
#   6.  Rate limiting — auth endpoints              P=90  BLOCK
#       Tighter limit: 100 requests per 5 minutes to /auth/* paths.
#       Prevents OTP brute-force and credential stuffing at the CDN layer.
#
# Scope: REGIONAL (attached to ALB, not CloudFront).
# CloudFront WAF requires scope=CLOUDFRONT and must be in us-east-1.
# For a full setup, create a separate WAF module with scope=CLOUDFRONT
# and attach it to the CloudFront distribution.
# =============================================================================

resource "aws_wafv2_web_acl" "main" {
  name        = "${var.project}-${var.env}-waf"
  description = "WAF for ${var.project} ${var.env} ALB"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  # ── Rule 1: AWS Core Rule Set (CRS) ─────────────────────────────────────────
  rule {
    name     = "AWSManagedRulesCommonRuleSet"
    priority = 10

    override_action {
      none {} # use the managed rule group's own action (BLOCK)
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"

        # Exclude SizeRestrictions_BODY — interview transcripts can be large
        rule_action_override {
          name = "SizeRestrictions_BODY"
          action_to_use { count {} }
        }
        # Exclude GenericRFI — Spring Boot actuator paths can trigger this
        rule_action_override {
          name = "GenericRFI_BODY"
          action_to_use { count {} }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project}-${var.env}-crs"
      sampled_requests_enabled   = true
    }
  }

  # ── Rule 2: Known Bad Inputs ─────────────────────────────────────────────────
  rule {
    name     = "AWSManagedRulesKnownBadInputsRuleSet"
    priority = 20

    override_action { none {} }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project}-${var.env}-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  # ── Rule 3: IP Reputation List ────────────────────────────────────────────────
  rule {
    name     = "AWSManagedRulesAmazonIpReputationList"
    priority = 30

    override_action { none {} }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesAmazonIpReputationList"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project}-${var.env}-ip-reputation"
      sampled_requests_enabled   = true
    }
  }

  # ── Rule 4: Anonymous IP List (COUNT only) ─────────────────────────────────
  rule {
    name     = "AWSManagedRulesAnonymousIpList"
    priority = 40

    override_action { count {} } # count, not block — VPN users are legitimate candidates

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesAnonymousIpList"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project}-${var.env}-anonymous-ip"
      sampled_requests_enabled   = true
    }
  }

  # ── Rule 5: Auth endpoint rate limit (tighter) ─────────────────────────────
  # Applies specifically to /api/v1/*/auth/* paths.
  # 100 requests per 5-minute window per IP. Blocks OTP brute-force.
  rule {
    name     = "AuthEndpointRateLimit"
    priority = 90

    action {
      block {
        custom_response {
          response_code = 429
          response_header {
            name  = "Retry-After"
            value = "300"
          }
        }
      }
    }

    statement {
      rate_based_statement {
        limit              = 100
        aggregate_key_type = "IP"

        scope_down_statement {
          byte_match_statement {
            search_string         = "/auth/"
            field_to_match { uri_path {} }
            text_transformation { priority = 0; type = "LOWERCASE" }
            positional_constraint = "CONTAINS"
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project}-${var.env}-auth-rate-limit"
      sampled_requests_enabled   = true
    }
  }

  # ── Rule 6: Global rate limit ─────────────────────────────────────────────
  # 2000 requests per 5-minute window per IP across all endpoints.
  # Protects against DDoS and aggressive scrapers without affecting normal users.
  rule {
    name     = "GlobalRateLimit"
    priority = 100

    action {
      block {
        custom_response {
          response_code = 429
          response_header {
            name  = "Retry-After"
            value = "300"
          }
        }
      }
    }

    statement {
      rate_based_statement {
        limit              = 2000
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project}-${var.env}-global-rate-limit"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.project}-${var.env}-waf"
    sampled_requests_enabled   = true
  }

  tags = var.tags
}

# ── Attach WAF to ALB ─────────────────────────────────────────────────────────

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = var.alb_arn
  web_acl_arn  = aws_wafv2_web_acl.main.arn
}

# ── CloudWatch Alarms: WAF Blocked Requests ───────────────────────────────────

resource "aws_cloudwatch_metric_alarm" "waf_blocked_high" {
  alarm_name          = "${var.project}-${var.env}-waf-blocked-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "BlockedRequests"
  namespace           = "AWS/WAFV2"
  period              = 300 # 5 minutes
  statistic           = "Sum"
  threshold           = var.blocked_requests_alarm_threshold
  alarm_description   = "WAF blocked requests spike — possible attack in progress"
  alarm_actions       = [var.alert_topic_arn]
  treat_missing_data  = "notBreaching"

  dimensions = {
    WebACL = aws_wafv2_web_acl.main.name
    Region = var.region
    Rule   = "ALL"
  }

  tags = var.tags
}

# ── WAF Logging ───────────────────────────────────────────────────────────────
# Logs all WAF decisions to CloudWatch Logs for forensics.

resource "aws_cloudwatch_log_group" "waf" {
  # WAF log group name MUST start with "aws-waf-logs-"
  name              = "aws-waf-logs-${var.project}-${var.env}"
  retention_in_days = var.env == "prod" ? 90 : 14
  tags              = var.tags
}

resource "aws_wafv2_web_acl_logging_configuration" "main" {
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]
  resource_arn            = aws_wafv2_web_acl.main.arn

  # Redact Authorization headers from logs (never log tokens)
  redacted_fields {
    single_header { name = "authorization" }
  }
  redacted_fields {
    single_header { name = "cookie" }
  }
}
