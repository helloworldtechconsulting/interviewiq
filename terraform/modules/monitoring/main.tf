# Notification Channel
resource "google_monitoring_notification_channel" "email" {
  display_name = "InterviewIQ ${var.environment} Email Alerts"
  type         = "email"
  labels = {
    email_address = var.notification_email_address
  }
  enabled = true
}

# Uptime Check for Cloud Run Service
resource "google_monitoring_uptime_check_config" "cloud_run" {
  display_name = "interviewiq-${var.environment}-uptime"
  timeout      = "10s"
  period       = "60s"

  http_check {
    path   = "/actuator/health"
    port   = 443
    use_ssl = true
    request_method = "GET"
  }

  monitored_resource {
    type = "uptime-url"
    labels = {
      host = replace(var.cloud_run_service_url, "https://", "")
    }
  }

  selected_regions = ["USA", "INDIA"]
}

# Alert Policy: 5xx Error Rate
resource "google_monitoring_alert_policy" "error_rate_5xx" {
  display_name = "interviewiq-${var.environment}-5xx-errors"
  combiner     = "OR"
  enabled      = true

  conditions {
    display_name = "High 5xx error rate"

    condition_threshold {
      filter          = <<-EOT
      resource.type = "cloud_run_revision"
      resource.label.service_name = "interviewiq-backend"
      metric.type = "run.googleapis.com/request_count"
      metric.label.response_code_class = "5xx"
      EOT
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = var.alert_5xx_error_threshold

      trigger_percent = 0.0

      aggregations {
        alignment_period  = "60s"
        per_series_aligner = "ALIGN_RATE"
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.email.id]

  documentation {
    content = "5xx error rate is above threshold. Check Cloud Run logs for details."
  }
}

# Alert Policy: Cloud Run CPU Usage
resource "google_monitoring_alert_policy" "high_cpu" {
  display_name = "interviewiq-${var.environment}-high-cpu"
  combiner     = "OR"
  enabled      = true

  conditions {
    display_name = "High CPU usage"

    condition_threshold {
      filter = <<-EOT
      resource.type = "cloud_run_revision"
      resource.label.service_name = "interviewiq-backend"
      metric.type = "run.googleapis.com/container/cpu/cores_allocated"
      EOT
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = var.alert_cpu_threshold / 100

      aggregations {
        alignment_period  = "60s"
        per_series_aligner = "ALIGN_MEAN"
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.email.id]

  documentation {
    content = "Cloud Run CPU usage is above ${var.alert_cpu_threshold}%. Consider increasing instance resources or scaling."
  }
}

# Alert Policy: Cloud SQL Connections
resource "google_monitoring_alert_policy" "high_sql_connections" {
  display_name = "interviewiq-${var.environment}-high-sql-connections"
  combiner     = "OR"
  enabled      = true

  conditions {
    display_name = "High SQL connections"

    condition_threshold {
      filter = <<-EOT
      resource.type = "cloudsql_database"
      metric.type = "cloudsql.googleapis.com/database/network/connections"
      EOT
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = var.alert_sql_connections_threshold

      aggregations {
        alignment_period  = "60s"
        per_series_aligner = "ALIGN_MEAN"
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.email.id]

  documentation {
    content = "Cloud SQL connections are above ${var.alert_sql_connections_threshold}%. Check for connection pool issues."
  }
}

# Log-based Metric: Interview Completions
resource "google_logging_metric" "interview_completions" {
  name   = "interview_completions"
  filter = <<-EOT
    resource.type = "cloud_run_revision"
    severity = "INFO"
    jsonPayload.event = "interview_completed"
  EOT

  metric_descriptor {
    metric_kind = "DELTA"
    value_type  = "INT64"
    unit        = "1"

    labels {
      key         = "interview_id"
      value_type  = "STRING"
      description = "Interview ID"
    }

    labels {
      key         = "status"
      value_type  = "STRING"
      description = "Completion status"
    }
  }
}

# Dashboard
resource "google_monitoring_dashboard" "interviewiq" {
  dashboard_json = jsonencode({
    displayName = "InterviewIQ ${var.environment} Dashboard"
    mosaicLayout = {
      columns = 12
      tiles = [
        {
          width  = 6
          height = 4
          widget = {
            title = "Cloud Run Request Rate"
            xyChart = {
              dataSets = [{
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "resource.type=\"cloud_run_revision\" metric.type=\"run.googleapis.com/request_count\""
                    aggregation = {
                      alignmentPeriod  = "60s"
                      perSeriesAligner = "ALIGN_RATE"
                    }
                  }
                }
              }]
            }
          }
        },
        {
          xPos   = 6
          width  = 6
          height = 4
          widget = {
            title = "Cloud Run Error Rate"
            xyChart = {
              dataSets = [{
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "resource.type=\"cloud_run_revision\" metric.type=\"run.googleapis.com/request_count\" metric.label.response_code_class=\"5xx\""
                    aggregation = {
                      alignmentPeriod  = "60s"
                      perSeriesAligner = "ALIGN_RATE"
                    }
                  }
                }
              }]
            }
          }
        },
        {
          yPos   = 4
          width  = 6
          height = 4
          widget = {
            title = "Cloud SQL Connections"
            xyChart = {
              dataSets = [{
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "resource.type=\"cloudsql_database\" metric.type=\"cloudsql.googleapis.com/database/network/connections\""
                    aggregation = {
                      alignmentPeriod  = "60s"
                      perSeriesAligner = "ALIGN_MEAN"
                    }
                  }
                }
              }]
            }
          }
        },
        {
          xPos   = 6
          yPos   = 4
          width  = 6
          height = 4
          widget = {
            title = "Interview Completions"
            xyChart = {
              dataSets = [{
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "metric.type=\"logging.googleapis.com/user/interview_completions\""
                    aggregation = {
                      alignmentPeriod  = "60s"
                      perSeriesAligner = "ALIGN_RATE"
                    }
                  }
                }
              }]
            }
          }
        }
      ]
    }
  })
}
