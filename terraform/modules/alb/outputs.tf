output "alb_arn"              { value = aws_lb.main.arn }
output "alb_arn_suffix"       { value = aws_lb.main.arn_suffix; description = "Short suffix for CloudWatch metric dimensions (e.g. app/interviewiq-prod-alb/abc123)" }
output "alb_dns_name"         { value = aws_lb.main.dns_name }
output "alb_zone_id"          { value = aws_lb.main.zone_id }
output "security_group_id"    { value = aws_security_group.alb.id }
output "target_group_arn"     { value = aws_lb_target_group.backend.arn }
output "https_listener_arn"   { value = try(aws_lb_listener.https[0].arn, null) }
