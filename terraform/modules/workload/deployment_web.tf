# =============================================================================
# The WEB Deployment — serves REST and WebSocket, schedulers off
#
# Arch v4.0 §5.2 calls the drain configuration below "the single easiest thing to
# get wrong", with the worst consequence: a pod evicted while holding live
# WebSockets kills those candidates' interviews mid-sentence. PRD §17 rates it
# HIGH severity AND HIGH probability — the highest-risk pairing in the register.
#
# The four mechanisms only work TOGETHER. Removing any one silently reintroduces
# the failure:
#
#   1. preStop → POST /internal/drain   fail readiness, keep sockets alive
#   2. terminationGracePeriodSeconds    wait for those interviews to finish
#   3. PodDisruptionBudget              limit voluntary evictions
#   4. HPA scaleDown stabilization      stop the autoscaler thrashing held pods
# =============================================================================

locals {
  # 60-minute maximum interview (Comprehensive tier) plus grace.
  interview_grace_seconds = 3900

  common_labels = {
    "app.kubernetes.io/name"       = "interviewiq"
    "app.kubernetes.io/part-of"    = "interviewiq"
    "app.kubernetes.io/managed-by" = "terraform"
    "interviewiq.in/environment"   = var.environment
  }
}

resource "kubernetes_deployment_v1" "web" {
  metadata {
    name      = "interviewiq-web"
    namespace = var.namespace
    labels    = merge(local.common_labels, { "app.kubernetes.io/component" = "web" })
  }

  spec {
    replicas = var.web_min_replicas

    selector {
      match_labels = { "app.kubernetes.io/component" = "web", "app.kubernetes.io/name" = "interviewiq" }
    }

    strategy {
      type = "RollingUpdate"
      rolling_update {
        # Never below the floor during a rollout: a live interview must always
        # have somewhere to reconnect to.
        max_unavailable = 0
        max_surge       = 1
      }
    }

    template {
      metadata {
        labels = merge(local.common_labels, { "app.kubernetes.io/component" = "web" })
        annotations = {
          # Roll pods when configuration changes, not only when the image does.
          "interviewiq.in/config-hash" = sha256(jsonencode(kubernetes_config_map_v1.app.data))
          "prometheus.io/scrape"       = "true"
          "prometheus.io/path"         = "/actuator/prometheus"
          "prometheus.io/port"         = "8080"
        }
      }

      spec {
        # THE critical setting. Kubernetes waits this long after preStop before
        # SIGKILL, which is what lets a 60-minute interview finish during a
        # rollout instead of being cut off.
        termination_grace_period_seconds = local.interview_grace_seconds

        # Spread web pods across nodes so losing one node cannot take out the
        # whole floor.
        topology_spread_constraint {
          max_skew           = 1
          topology_key       = "kubernetes.io/hostname"
          when_unsatisfiable = "ScheduleAnyway"
          label_selector {
            match_labels = { "app.kubernetes.io/component" = "web" }
          }
        }

        container {
          name  = "interviewiq"
          image = var.image

          port {
            name           = "http"
            container_port = 8080
          }

          env {
            name  = "SPRING_PROFILES_ACTIVE"
            value = "${var.environment},web"
          }

          env_from {
            config_map_ref { name = kubernetes_config_map_v1.app.metadata[0].name }
          }
          env_from {
            secret_ref { name = kubernetes_secret_v1.app.metadata[0].name }
          }

          env {
            name  = "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"
            value = tostring(var.hikari_pool_size)
          }

          lifecycle {
            pre_stop {
              exec {
                # Fails the readiness probe so the Service stops routing new
                # traffic here, while keeping existing WebSockets alive. The
                # sleep gives the endpoints controller time to propagate the
                # removal before termination proceeds.
                command = ["/bin/sh", "-c",
                "curl -sf -X POST localhost:8080/internal/drain || true; sleep 5"]
              }
            }
          }

          # Readiness and liveness point at DIFFERENT endpoints on purpose.
          # Arch v4.0 §5.2: "Liveness must not kill a pod that is merely
          # draining." If liveness also failed during drain, Kubernetes would
          # kill the pod mid-drain and defeat the entire mechanism.
          readiness_probe {
            http_get {
              path = "/actuator/health/readiness"
              port = 8080
            }
            initial_delay_seconds = 20
            period_seconds        = 5
            failure_threshold     = 3
          }

          liveness_probe {
            http_get {
              path = "/actuator/health/liveness"
              port = 8080
            }
            initial_delay_seconds = 60
            period_seconds        = 15
            # Generous: killing a pod that is merely slow ends live interviews.
            failure_threshold = 6
          }

          startup_probe {
            http_get {
              path = "/actuator/health/liveness"
              port = 8080
            }
            period_seconds    = 5
            failure_threshold = 30 # JVM start can take 30s+; see Arch v4.0 §0
          }

          resources {
            requests = {
              cpu    = "250m"
              memory = "512Mi"
            }
            limits = {
              # No CPU limit deliberately: throttling a pod mid-interview adds
              # latency to a real-time conversation. Memory is limited because
              # a leak should kill one pod rather than starve the node.
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
# PodDisruptionBudget — limits VOLUNTARY evictions (node drains, cluster
# upgrades, descheduling). Without it, a node drain can take every web pod at
# once regardless of the grace period.
# =============================================================================

resource "kubernetes_pod_disruption_budget_v1" "web" {
  metadata {
    name      = "interviewiq-web"
    namespace = var.namespace
  }
  spec {
    min_available = var.web_min_replicas
    selector {
      match_labels = { "app.kubernetes.io/component" = "web" }
    }
  }
}

resource "kubernetes_service_v1" "web" {
  metadata {
    name      = "interviewiq-web"
    namespace = var.namespace
    labels    = local.common_labels
  }
  spec {
    selector = { "app.kubernetes.io/component" = "web", "app.kubernetes.io/name" = "interviewiq" }
    port {
      name        = "http"
      port        = 80
      target_port = 8080
    }
    type = "ClusterIP"
  }
}

# =============================================================================
# HPA — scales on CPU, with a scale-down stabilisation window as long as the
# maximum interview.
#
# Arch v4.0 §5.2: without the long window the autoscaler thrashes pods that
# still hold sessions. Scaling UP stays responsive; only scale-down waits.
# =============================================================================

resource "kubernetes_horizontal_pod_autoscaler_v2" "web" {
  metadata {
    name      = "interviewiq-web"
    namespace = var.namespace
  }

  spec {
    scale_target_ref {
      api_version = "apps/v1"
      kind        = "Deployment"
      name        = kubernetes_deployment_v1.web.metadata[0].name
    }

    min_replicas = var.web_min_replicas
    max_replicas = var.web_max_replicas

    metric {
      type = "Resource"
      resource {
        name = "cpu"
        target {
          type                = "Utilization"
          average_utilization = 70
        }
      }
    }

    behavior {
      scale_down {
        # Matches the maximum interview length. A pod that held an interview
        # must not be removed while that interview could still be running.
        stabilization_window_seconds = local.interview_grace_seconds
        select_policy                = "Min"
        policy {
          type           = "Pods"
          value          = 1
          period_seconds = 300
        }
      }
      scale_up {
        stabilization_window_seconds = 30
        select_policy                = "Max"
        policy {
          type           = "Pods"
          value          = 2
          period_seconds = 60
        }
      }
    }
  }
}
