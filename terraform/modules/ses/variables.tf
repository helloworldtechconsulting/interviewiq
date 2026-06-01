variable "project"         { type = string }
variable "env"             { type = string }
variable "region"          { type = string }
variable "domain"          { type = string; description = "Root domain, e.g. interviewiq.ai" }
variable "route53_zone_id" { type = string }
variable "alert_topic_arn" { type = string; description = "SNS topic ARN for bounce/complaint alerts" }
variable "dmarc_policy" {
  type        = string
  default     = "none"
  description = "DMARC policy: 'none' (monitor), 'quarantine', or 'reject'. Start with none, graduate to reject."
  validation {
    condition     = contains(["none", "quarantine", "reject"], var.dmarc_policy)
    error_message = "dmarc_policy must be 'none', 'quarantine', or 'reject'."
  }
}
variable "tags" { type = map(string); default = {} }
