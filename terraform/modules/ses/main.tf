# =============================================================================
# modules/ses/main.tf
#
# Amazon SES domain verification for InterviewIQ transactional email.
#
# Without this module, SES rejects all outbound emails.
# This module provisions:
#   1. Domain identity (proves AWS can send from @interviewiq.ai)
#   2. DKIM tokens → published as CNAME records in Route53
#   3. MAIL FROM domain (improves deliverability; avoids amazonses.com in headers)
#   4. SPF record  (tells receiving servers that SES can send for your domain)
#   5. DMARC record (tells receiving servers what to do with failed checks)
#
# Prerequisites:
#   - Route53 hosted zone for the domain must exist
#   - SES must be in ap-south-1 (same region as everything else)
#
# Post-deploy:
#   - If account is in SES Sandbox, request production access via AWS console
#     before live candidate emails can be sent.
#   - Test with: aws ses send-email --from noreply@interviewiq.ai ...
# =============================================================================

# ── Domain Identity ───────────────────────────────────────────────────────────

resource "aws_ses_domain_identity" "main" {
  domain = var.domain
}

# ── DKIM Setup ────────────────────────────────────────────────────────────────
# SES generates 3 DKIM CNAME records. Publishing them proves you control the domain
# and enables SES to sign outgoing emails — improves deliverability and passes DMARC.

resource "aws_ses_domain_dkim" "main" {
  domain = aws_ses_domain_identity.main.domain
}

resource "aws_route53_record" "dkim" {
  count   = 3
  zone_id = var.route53_zone_id
  name    = "${aws_ses_domain_dkim.main.dkim_tokens[count.index]}._domainkey.${var.domain}"
  type    = "CNAME"
  ttl     = 600
  records = ["${aws_ses_domain_dkim.main.dkim_tokens[count.index]}.dkim.amazonses.com"]
}

# ── Domain Verification TXT Record ───────────────────────────────────────────
# Proves domain ownership to SES.

resource "aws_route53_record" "ses_verification" {
  zone_id = var.route53_zone_id
  name    = "_amazonses.${var.domain}"
  type    = "TXT"
  ttl     = 600
  records = [aws_ses_domain_identity.main.verification_token]
}

resource "aws_ses_domain_identity_verification" "main" {
  domain = aws_ses_domain_identity.main.id
  depends_on = [aws_route53_record.ses_verification]
}

# ── MAIL FROM Domain ──────────────────────────────────────────────────────────
# Uses mail.interviewiq.ai as the envelope sender domain instead of
# amazonses.com. This makes the From header match the envelope From,
# which is required for strict DMARC alignment.

resource "aws_ses_domain_mail_from" "main" {
  domain           = aws_ses_domain_identity.main.domain
  mail_from_domain = "mail.${var.domain}"
}

# MX record for MAIL FROM subdomain (required)
resource "aws_route53_record" "mail_from_mx" {
  zone_id = var.route53_zone_id
  name    = "mail.${var.domain}"
  type    = "MX"
  ttl     = 600
  records = ["10 feedback-smtp.${var.region}.amazonses.com"]
}

# SPF record for MAIL FROM subdomain
resource "aws_route53_record" "mail_from_spf" {
  zone_id = var.route53_zone_id
  name    = "mail.${var.domain}"
  type    = "TXT"
  ttl     = 600
  records = ["v=spf1 include:amazonses.com ~all"]
}

# ── SPF Record on Root Domain ─────────────────────────────────────────────────
# Authorises SES to send email on behalf of @interviewiq.ai.
# ~all = soft fail (recommended; -all is too strict and causes false positives).

resource "aws_route53_record" "spf" {
  zone_id = var.route53_zone_id
  name    = var.domain
  type    = "TXT"
  ttl     = 600
  records = ["v=spf1 include:amazonses.com ~all"]
}

# ── DMARC Record ──────────────────────────────────────────────────────────────
# Policy: reject messages that fail both SPF and DKIM checks.
# rua: aggregate reports sent to dmarc@interviewiq.ai (set up a mailbox for this).
# Start with p=none (monitoring) then move to p=quarantine → p=reject.

resource "aws_route53_record" "dmarc" {
  zone_id = var.route53_zone_id
  name    = "_dmarc.${var.domain}"
  type    = "TXT"
  ttl     = 600
  records = [
    "v=DMARC1; p=${var.dmarc_policy}; rua=mailto:dmarc@${var.domain}; ruf=mailto:dmarc@${var.domain}; fo=1; adkim=r; aspf=r"
  ]
}

# ── SES Configuration Set ─────────────────────────────────────────────────────
# Tracks sending events (bounces, complaints, deliveries) for monitoring.

resource "aws_ses_configuration_set" "main" {
  name = "${var.project}-${var.env}"

  delivery_options {
    tls_policy = "Require" # never send over plain HTTP
  }

  reputation_metrics_enabled = true # enables bounce/complaint rate tracking
  sending_enabled            = true
}

# ── SNS: Bounce and Complaint Notifications ───────────────────────────────────
# SES will notify this topic when emails bounce or recipients complain.
# High bounce/complaint rates cause SES to suspend sending.

resource "aws_ses_identity_notification_topic" "bounces" {
  topic_arn                = var.alert_topic_arn
  notification_type        = "Bounce"
  identity                 = aws_ses_domain_identity.main.domain
  include_original_headers = false
}

resource "aws_ses_identity_notification_topic" "complaints" {
  topic_arn                = var.alert_topic_arn
  notification_type        = "Complaint"
  identity                 = aws_ses_domain_identity.main.domain
  include_original_headers = false
}

# ── CloudWatch Alarms: Bounce + Complaint Rate ────────────────────────────────
# SES suspends sending if bounce rate > 10% or complaint rate > 0.5%.
# Alert early at 5% and 0.1% respectively.

resource "aws_cloudwatch_metric_alarm" "ses_bounce_rate" {
  alarm_name          = "${var.project}-${var.env}-ses-bounce-rate-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "Reputation.BounceRate"
  namespace           = "AWS/SES"
  period              = 300
  statistic           = "Average"
  threshold           = 0.05 # alert at 5% (SES suspends at 10%)
  alarm_description   = "SES bounce rate > 5% — review recipient list quality"
  alarm_actions       = [var.alert_topic_arn]
  treat_missing_data  = "notBreaching"
  tags                = var.tags
}

resource "aws_cloudwatch_metric_alarm" "ses_complaint_rate" {
  alarm_name          = "${var.project}-${var.env}-ses-complaint-rate-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "Reputation.ComplaintRate"
  namespace           = "AWS/SES"
  period              = 300
  statistic           = "Average"
  threshold           = 0.001 # alert at 0.1% (SES suspends at 0.5%)
  alarm_description   = "SES complaint rate > 0.1% — review email content and list"
  alarm_actions       = [var.alert_topic_arn]
  treat_missing_data  = "notBreaching"
  tags                = var.tags
}
