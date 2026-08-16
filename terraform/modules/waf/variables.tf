variable "project"         { type = string }
variable "env"             { type = string }
variable "region"          { type = string }
variable "alb_arn"         { type = string; description = "Full ARN of the ALB to attach the WAF to" }
variable "alert_topic_arn" { type = string; description = "SNS topic ARN for WAF attack alerts" }
variable "blocked_requests_alarm_threshold" {
  type        = number
  default     = 500
  description = "Number of blocked requests per 5 min before alerting. Lower in prod."
}
variable "tags" { type = map(string); default = {} }
