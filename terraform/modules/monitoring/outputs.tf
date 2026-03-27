output "notification_channel_id" {
  description = "Notification channel ID"
  value       = google_monitoring_notification_channel.email.id
}

output "uptime_check_id" {
  description = "Uptime check ID"
  value       = google_monitoring_uptime_check_config.cloud_run.id
}

output "dashboard_id" {
  description = "Dashboard ID"
  value       = google_monitoring_dashboard.interviewiq.id
}

output "alert_policy_5xx_id" {
  description = "5xx error rate alert policy ID"
  value       = google_monitoring_alert_policy.error_rate_5xx.id
}

output "alert_policy_cpu_id" {
  description = "CPU alert policy ID"
  value       = google_monitoring_alert_policy.high_cpu.id
}

output "alert_policy_sql_connections_id" {
  description = "SQL connections alert policy ID"
  value       = google_monitoring_alert_policy.high_sql_connections.id
}
