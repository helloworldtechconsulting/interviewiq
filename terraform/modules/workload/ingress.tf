# =============================================================================
# Ingress — sticky sessions are MANDATORY
#
# Arch v4.0 §5.3 is unambiguous: "A candidate's WebSocket AND their REST calls
# must reach the same web pod, because the session registry is in-memory."
#
# Without affinity, a candidate's WebSocket lands on pod A while their
# recording-upload POST lands on pod B — and pod B has no idea their interview
# exists. The registry documents this single-pod assumption in code
# (RoomSessionRegistry); this is the deployment half of the same contract.
# =============================================================================

resource "kubernetes_ingress_v1" "api" {
  metadata {
    name      = "interviewengine-api"
    namespace = var.namespace
    labels    = local.common_labels

    annotations = {
      # ── Sticky sessions (Arch v4.0 §5.3) ───────────────────────────────────
      "nginx.ingress.kubernetes.io/affinity"            = "cookie"
      "nginx.ingress.kubernetes.io/session-cookie-name" = "iiq-affinity"
      "nginx.ingress.kubernetes.io/session-cookie-path" = "/"
      # persistent: keep routing to the same pod even after it starts failing
      # readiness — which is exactly what a draining pod does, and its existing
      # interviews must continue to reach it.
      "nginx.ingress.kubernetes.io/affinity-mode" = "persistent"

      # ── WebSocket timeouts ─────────────────────────────────────────────────
      # Must exceed the longest interview (60 minutes at the Comprehensive tier)
      # or the ingress closes the socket on a candidate mid-interview.
      "nginx.ingress.kubernetes.io/proxy-read-timeout" = tostring(local.interview_grace_seconds)
      "nginx.ingress.kubernetes.io/proxy-send-timeout" = tostring(local.interview_grace_seconds)

      # Recordings go browser-to-storage, so nothing large crosses the ingress.
      "nginx.ingress.kubernetes.io/proxy-body-size" = "10m"

      # ── TLS ────────────────────────────────────────────────────────────────
      "cert-manager.io/cluster-issuer"                 = "letsencrypt-production"
      "nginx.ingress.kubernetes.io/ssl-redirect"       = "true"
      "nginx.ingress.kubernetes.io/force-ssl-redirect" = "true"

      # CORS is handled by the application, which has the authoritative
      # allow-list and must apply it to the WebSocket upgrade too. Doing it in
      # both places would produce duplicate headers, which browsers reject.
      "nginx.ingress.kubernetes.io/enable-cors" = "false"
    }
  }

  spec {
    ingress_class_name = "nginx"

    tls {
      hosts       = [var.api_host]
      secret_name = "interviewengine-api-tls"
    }

    rule {
      host = var.api_host
      http {
        # The WebSocket path is listed first and explicitly. It is the same
        # Service, but calling it out keeps the affinity requirement visible to
        # anyone reading the manifest.
        path {
          path      = "/ws"
          path_type = "Prefix"
          backend {
            service {
              name = kubernetes_service_v1.web.metadata[0].name
              port { number = 80 }
            }
          }
        }

        path {
          path      = "/api"
          path_type = "Prefix"
          backend {
            service {
              name = kubernetes_service_v1.web.metadata[0].name
              port { number = 80 }
            }
          }
        }
      }
    }
  }
}

# =============================================================================
# NetworkPolicy — /internal/** must not be reachable from outside the cluster
#
# The drain endpoint is unauthenticated so the kubelet's preStop hook can call
# it without credentials. That is safe only because it is unreachable from the
# internet: the Ingress above routes /ws and /api only, and this policy is the
# second line of defence.
# =============================================================================

resource "kubernetes_network_policy_v1" "web_ingress" {
  metadata {
    name      = "interviewengine-web-ingress"
    namespace = var.namespace
  }

  spec {
    pod_selector {
      match_labels = { "app.kubernetes.io/component" = "web" }
    }

    ingress {
      # Application traffic from the ingress controller only.
      from {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = "ingress-nginx" }
        }
      }
      ports {
        port     = "8080"
        protocol = "TCP"
      }
    }

    ingress {
      # Prometheus scraping.
      from {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = "monitoring" }
        }
      }
      ports {
        port     = "8080"
        protocol = "TCP"
      }
    }

    policy_types = ["Ingress"]
  }
}
