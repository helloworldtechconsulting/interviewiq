# =============================================================================
# Observability — Prometheus, Grafana, Alertmanager (Arch v4.0 §3, PRD §8)
#
# Arch v4.0 §9 states the cost of leaving AWS plainly: "You now own ingress,
# cert-manager, the secrets operator and the monitoring stack." Ingress,
# cert-manager and External Secrets were already provisioned. This is the
# monitoring stack — the last of the four, and until now the only one where
# the application emitted metrics that nothing collected.
#
# kube-prometheus-stack because it is one Helm release for Prometheus, Grafana,
# Alertmanager, node-exporter and kube-state-metrics, all cloud-neutral. Grafana
# Cloud's free tier is the alternative §3 names, and remains available — the
# app-facing contract is /actuator/prometheus either way.
# =============================================================================

resource "kubernetes_namespace_v1" "monitoring" {
  count = var.monitoring_enabled ? 1 : 0

  metadata {
    name   = "monitoring"
    labels = local.common_labels
  }
}

resource "helm_release" "kube_prometheus_stack" {
  count = var.monitoring_enabled ? 1 : 0

  name       = "kube-prometheus-stack"
  repository = "https://prometheus-community.github.io/helm-charts"
  chart      = "kube-prometheus-stack"
  version    = var.kube_prometheus_stack_version
  namespace  = kubernetes_namespace_v1.monitoring[0].metadata[0].name

  # The chart installs a large set of CRDs; a short timeout leaves a release in
  # a half-applied state that the next apply then has to clean up.
  timeout = 900

  values = [yamlencode({
    # ── Prometheus ──────────────────────────────────────────────────────────
    prometheus = {
      prometheusSpec = {
        retention = var.metrics_retention

        # Scrape any pod carrying the prometheus.io/scrape annotations that
        # deployment_web.tf and deployment_worker.tf already set. Without this
        # the chart would only discover ServiceMonitors, and the annotations on
        # those Deployments would go on being ignored — which is what they were
        # doing before this file existed.
        additionalScrapeConfigs = [{
          job_name = "interviewengine-pods"
          kubernetes_sd_configs = [{
            role = "pod"
          }]
          relabel_configs = [
            {
              source_labels = ["__meta_kubernetes_pod_annotation_prometheus_io_scrape"]
              action        = "keep"
              regex         = "true"
            },
            {
              source_labels = ["__meta_kubernetes_pod_annotation_prometheus_io_path"]
              action        = "replace"
              target_label  = "__metrics_path__"
              regex         = "(.+)"
            },
            {
              source_labels = ["__address__", "__meta_kubernetes_pod_annotation_prometheus_io_port"]
              action        = "replace"
              target_label  = "__address__"
              regex         = "([^:]+)(?::\\d+)?;(\\d+)"
              replacement   = "$1:$2"
            },
            # Carry the pod and component through, so "is this the web or the
            # worker deployment?" is answerable in a query. They run the same
            # image and are otherwise indistinguishable.
            {
              source_labels = ["__meta_kubernetes_pod_name"]
              action        = "replace"
              target_label  = "pod"
            },
            {
              source_labels = ["__meta_kubernetes_pod_label_app_kubernetes_io_component"]
              action        = "replace"
              target_label  = "component"
            },
          ]
        }]

        resources = {
          requests = { cpu = "200m", memory = "1Gi" }
          limits   = { memory = "2Gi" }
        }
      }
    }

    # ── Grafana ─────────────────────────────────────────────────────────────
    grafana = {
      enabled = true
      # No ingress. Grafana is reached with `kubectl port-forward`, which needs
      # no public hostname, no certificate and no second authentication system
      # to get wrong. An internet-facing Grafana on default credentials is a
      # well-travelled way to leak an entire metrics estate.
      ingress = { enabled = false }

      adminPassword = var.grafana_admin_password

      resources = {
        requests = { cpu = "100m", memory = "256Mi" }
        limits   = { memory = "512Mi" }
      }
    }

    # ── Alertmanager ────────────────────────────────────────────────────────
    alertmanager = {
      enabled = true
      config = {
        route = {
          group_by        = ["alertname", "component"]
          group_wait      = "30s"
          group_interval  = "5m"
          repeat_interval = "4h"
          receiver        = "default"
        }
        receivers = [{
          name = "default"
          # Deliberately empty until a destination is chosen. Alerts still fire
          # and are visible in the Alertmanager UI; they are simply not routed
          # anywhere yet. Wiring this to email would mean routing "the mail
          # pipeline is down" through the mail pipeline.
          }
        ]
      }
    }

    # Node-level metrics. Cheap, and the first thing wanted when a pod is
    # evicted or throttled.
    nodeExporter     = { enabled = true }
    kubeStateMetrics = { enabled = true }
  })]
}

# =============================================================================
# Alert rules
#
# Every rule below fires on a metric that actually exists — either a Micrometer
# default or one of the two gauges PipelineMetrics registers. Rules written
# against metrics nobody emits are the usual failure here: they never fire, and
# their silence is indistinguishable from health.
# =============================================================================

resource "kubernetes_manifest" "alert_rules" {
  count = var.monitoring_enabled ? 1 : 0

  manifest = {
    apiVersion = "monitoring.coreos.com/v1"
    kind       = "PrometheusRule"
    metadata = {
      name      = "interviewengine-alerts"
      namespace = kubernetes_namespace_v1.monitoring[0].metadata[0].name
      labels = merge(local.common_labels, {
        # The chart's Prometheus only picks up rules carrying its release label.
        release = "kube-prometheus-stack"
      })
    }
    spec = {
      groups = [
        {
          name = "interviewengine.slo"
          rules = [
            # ── The one number the product promises ─────────────────────────
            {
              alert = "EvaluationReportSlaBreach"
              # PRD §8 commits to a report within 30 minutes. Alerting at 20
              # leaves room to act before the promise is actually broken —
              # an alert that fires exactly at the SLA boundary is a
              # notification of failure, not a chance to prevent it.
              expr   = "interviewengine_evaluation_queue_oldest_age_seconds > 1200"
              for    = "5m"
              labels = { severity = "critical" }
              annotations = {
                summary     = "Evaluation backlog is approaching the 30-minute SLA"
                description = "Oldest pending report has been queued {{ $value | humanizeDuration }}. PRD v2.1 §8 promises 30 minutes."
              }
            },
            {
              alert  = "EvaluationQueueGrowing"
              expr   = "interviewengine_evaluation_queue_depth > 50"
              for    = "15m"
              labels = { severity = "warning" }
              annotations = {
                summary     = "Evaluation queue depth sustained above 50"
                description = "KEDA should be scaling workers on this. If depth stays high while worker replicas sit at their maximum, the ceiling is too low."
              }
            },
          ]
        },
        {
          name = "interviewengine.saturation"
          rules = [
            # ── The failure the connection budget guards against ────────────
            {
              alert = "DatabaseConnectionsExhausted"
              # Threads waiting on a connection. Anything above zero for a
              # sustained period means the pool is the bottleneck — the
              # condition Arch v4.0 §5.4 warns about, which presents as
              # unexplained latency rather than as an obvious database error.
              expr   = "sum(hikaricp_connections_pending) by (component) > 0"
              for    = "5m"
              labels = { severity = "critical" }
              annotations = {
                summary     = "Threads are waiting for a database connection ({{ $labels.component }})"
                description = "HikariCP has no free connections. Check total usage against the instance limit before raising pool size — see the connection budget check in deployment_web.tf."
              }
            },
            {
              alert  = "HighServerErrorRate"
              expr   = "sum(rate(http_server_requests_seconds_count{outcome=\"SERVER_ERROR\"}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.05"
              for    = "10m"
              labels = { severity = "critical" }
              annotations = {
                summary     = "Over 5% of requests are returning 5xx"
                description = "Sustained server errors across the API."
              }
            },
            {
              alert  = "JvmHeapPressure"
              expr   = "sum(jvm_memory_used_bytes{area=\"heap\"}) by (pod) / sum(jvm_memory_max_bytes{area=\"heap\"}) by (pod) > 0.90"
              for    = "15m"
              labels = { severity = "warning" }
              annotations = {
                summary     = "Heap above 90% on {{ $labels.pod }}"
                description = "The container memory limit is 1Gi; an OOMKill here terminates whatever interviews that pod is holding."
              }
            },
          ]
        },
        {
          name = "interviewengine.availability"
          rules = [
            {
              alert  = "WebDeploymentDegraded"
              expr   = "kube_deployment_status_replicas_available{deployment=\"interviewengine-web\"} < 2"
              for    = "10m"
              labels = { severity = "critical" }
              annotations = {
                summary     = "Fewer than 2 web pods available"
                description = "Arch v4.0 §0: the 2-pod floor exists for availability, not capacity. Below it there is nowhere for a dropped interview WebSocket to reconnect to."
              }
            },
            {
              alert  = "WorkerDeploymentDown"
              expr   = "kube_deployment_status_replicas_available{deployment=\"interviewengine-worker\"} < 1"
              for    = "10m"
              labels = { severity = "critical" }
              annotations = {
                summary     = "No worker pods available"
                description = "Nothing is generating evaluation reports or question banks. The web tier keeps accepting interviews, so this backs up silently."
              }
            },
            {
              alert  = "PodCrashLooping"
              expr   = "rate(kube_pod_container_status_restarts_total{namespace=\"${var.namespace}\"}[15m]) * 900 > 3"
              for    = "10m"
              labels = { severity = "warning" }
              annotations = {
                summary = "{{ $labels.pod }} is restarting repeatedly"
              }
            },
          ]
        },
      ]
    }
  }

  depends_on = [helm_release.kube_prometheus_stack]
}
