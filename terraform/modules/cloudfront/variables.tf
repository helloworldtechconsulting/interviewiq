variable "project"         { type = string }
variable "env"             { type = string }
variable "domain"          { type = string; description = "Root domain, e.g. interviewiq.ai" }
variable "spa_bucket_name" { type = string; description = "S3 bucket name for SPA static files" }
variable "alb_dns_name"    { type = string; description = "ALB DNS name for API origin" }
variable "route53_zone_id" { type = string; description = "Route53 hosted zone ID for the domain" }
variable "tags"            { type = map(string); default = {} }
