# =============================================================================
# The WORKER Deployment — schedulers on, serves nothing
#
# Same image as web, distinguished only by Spring profile (Implementation
# Architecture Decisions §4). There is no Service in front of it: it reaches the
# database and the LLM providers, and nothing reaches it.
#
# It holds no WebSockets, so — unlike web — it is FREELY EVICTABLE. No drain
# hook, no long grace period, no PodDisruptionBudget. Work a dying worker was
# holding is reclaimed by the staleness sweep on another pod, because every
# worker claims with SELECT ... FOR UPDATE SKIP LOCKED (§7.9).
# =============================================================================

resource "kubernetes_deployment_v1" "worker" {
  metadata {
    name      = "interviewiq-worker"
    namespace = var.namespace
    labels    = merge(local.common_labels, { "app.kubernetes.io/component" = "worker" })
  }

  spec {
    replicas = var.worker_min_replicas

    selector {
      match_labels = { "app.kubernetes.io/component" = "worker", "app.kubernetes.io/name" = "interviewiq" }
    }

    strategy {
      type = "RollingUpdate"
      rolling_update {
        # Workers are interchangeable and their work is reclaimable, so a
        # rollout can be aggressive here in a way it must never be on web.
        max_unavailable = 1
        max_surge       = 1
      }
    }

    template {
      metadata {
        labels = merge(local.common_labels, { "app.kubernetes.io/component" = "worker" })
        annotations = {
          "interviewiq.in/config-hash" = sha256(jsonencode(kubernetes_config_map_v1.app.data))
          "prometheus.io/scrape"       = "true"
          "prometheus.io/path"         = "/actuator/prometheus"
          "prometheus.io/port"         = "8080"
        }
      }

      spec {
        # Short. The longest unit of work is one ~20-second LLM call, and
        # anything unfinished is reclaimed after app.worker.stale-claim-after.
        termination_grace_period_seconds = 120

        container {
          name  = "interviewiq"
          image = var.image

          port {
            name           = "http"
            container_port = 8080 # actuator only — no Service routes to it
          }

          env {
            name  = "SPRING_PROFILES_ACTIVE"
            value = "${var.environment},worker"
          }

          env_from {
            config_map_ref { name = kubernetes_config_map_v1.app.metadata[0].name }
          }
          env_from {
            secret_ref { name = kubernetes_secret_v1.app.metadata[0].name }
          }

          readiness_probe {
            http_get {
              path = "/actuator/health/readiness"
              port = 8080
            }
            initial_delay_seconds = 20
            period_seconds        = 10
          }

          liveness_probe {
            http_get {
              path = "/actuator/health/liveness"
              port = 8080
            }
            initial_delay_seconds = 60
            period_seconds        = 20
            failure_threshold     = 3
          }

          resources {
            requests = {
              cpu    = "250m"
              memory = "512Mi"
            }
            limits = {
              memory = "1Gi"
            }
          }

          security_context {
            run_as_non_root            = true
            run_as_user                = 1000
            read_only_root_filesystem  = true
            allow_privilege_escalation = false
            capabilities { drop = ["ALL"] }
          }

          volume_mount {
            name       = "tmp"
            mount_path = "/tmp"
          }
        }

        volume {
          name = "tmp"
          empty_dir {}
        }
      }
    }
  }
}

# =============================================================================
# KEDA — scales the worker on QUEUE DEPTH, read straight from PostgreSQL
#
# Implementation Architecture Decisions §4: "Queue-depth autoscaling uses the
# KEDA Postgres scaler against a pending-work count — for example a count of
# evaluation_reports rows at generation_status = 'PENDING'. Portable, free, and
# no broker required."
#
# This is the concrete payoff of the no-broker decision (§3). The queue is a
# Postgres table, so the autoscaler reads the queue with a SELECT — no message
# broker to run, upgrade, monitor or back up, and nothing cloud-specific like
# SQS or Pub/Sub to tie the deployment to one provider.
# =============================================================================

resource "kubernetes_manifest" "worker_scaled_object" {
  count = var.enable_keda ? 1 : 0

  manifest = {
    apiVersion = "keda.sh/v1alpha1"
    kind       = "ScaledObject"
    metadata = {
      name      = "interviewiq-worker"
      namespace = var.namespace
    }
    spec = {
      scaleTargetRef = {
        name = kubernetes_deployment_v1.worker.metadata[0].name
      }
      minReplicaCount = var.worker_min_replicas
      maxReplicaCount = var.worker_max_replicas

      pollingInterval = 30
      # Long enough that a worker finishing a claimed batch is not scaled away
      # underneath it, short enough that a burst drains and the pods go.
      cooldownPeriod = 300

      triggers = [
        {
          type = "postgresql"
          metadata = {
            # Both queues matter: evaluation is the one with an SLA attached
            # (30 minutes hard, ~5 soft), question generation gates the
            # candidate's readiness gate.
            query = join(" ", [
              "SELECT (SELECT COUNT(*) FROM evaluation_reports WHERE generation_status = 'PENDING')",
              "+ (SELECT COUNT(*) FROM interview_sessions WHERE question_generation_status = 'PENDING')"
            ])
            # One extra worker per 10 queued items. At the 30-minute SLA even a
            # single worker clears a 50-interview burst in ~17 minutes, so this
            # exists to protect the ~5-minute soft target, not the hard one.
            targetQueryValue  = "10"
            connectionFromEnv = "KEDA_POSTGRES_CONNECTION"
          }
          authenticationRef = {
            name = "interviewiq-postgres-auth"
          }
        }
      ]
    }
  }
}

resource "kubernetes_manifest" "worker_trigger_auth" {
  count = var.enable_keda ? 1 : 0

  manifest = {
    apiVersion = "keda.sh/v1alpha1"
    kind       = "TriggerAuthentication"
    metadata = {
      name      = "interviewiq-postgres-auth"
      namespace = var.namespace
    }
    spec = {
      secretTargetRef = [
        {
          parameter = "connection"
          name      = kubernetes_secret_v1.app.metadata[0].name
          key       = "KEDA_POSTGRES_CONNECTION"
        }
      ]
    }
  }
}
