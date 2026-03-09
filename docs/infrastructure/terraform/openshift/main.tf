# Java Maven Template - OpenShift Terraform Configuration
# Requires: Terraform >= 1.6.0, OpenShift cluster access

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.0"
    }
    openshift = {
      source  = "openshift/openshift"
      version = "~> 0.1"
    }
  }
}

provider "kubernetes" {
  host                   = var.openshift_server_url
  token                  = var.openshift_token
  cluster_ca_certificate = base64decode(var.cluster_ca_certificate)
}

# Namespace
resource "kubernetes_namespace" "app" {
  metadata {
    name = var.namespace
    labels = {
      app         = var.app_name
      environment = var.environment
    }
  }
}

# ConfigMap
resource "kubernetes_config_map" "app" {
  metadata {
    name      = "${var.app_name}-config"
    namespace = kubernetes_namespace.app.metadata[0].name
  }

  data = {
    JAVA_OPTS = "-Xmx512m -XX:+UseContainerSupport"
    LOG_LEVEL = "INFO"
  }
}

# Secret
resource "kubernetes_secret" "app" {
  metadata {
    name      = "${var.app_name}-secrets"
    namespace = kubernetes_namespace.app.metadata[0].name
  }

  data = {
    # Base64 encoded values - replace with actual secrets
    # DB_PASSWORD = base64encode(var.db_password)
  }

  type = "Opaque"
}

# Deployment
resource "kubernetes_deployment" "app" {
  metadata {
    name      = var.app_name
    namespace = kubernetes_namespace.app.metadata[0].name
    labels = {
      app = var.app_name
    }
  }

  spec {
    replicas = var.replicas

    selector {
      match_labels = {
        app = var.app_name
      }
    }

    template {
      metadata {
        labels = {
          app = var.app_name
        }
      }

      spec {
        container {
          name  = "app"
          image = var.image

          port {
            container_port = var.app_port
          }

          env_from {
            config_map_ref {
              name = kubernetes_config_map.app.metadata[0].name
            }
          }

          env_from {
            secret_ref {
              name = kubernetes_secret.app.metadata[0].name
            }
          }

          resources {
            requests = {
              cpu    = "100m"
              memory = "256Mi"
            }
            limits = {
              cpu    = "500m"
              memory = "512Mi"
            }
          }

          liveness_probe {
            http_get {
              path = "/health"
              port = var.app_port
            }
            initial_delay_seconds = 30
            period_seconds        = 10
          }

          readiness_probe {
            http_get {
              path = "/health"
              port = var.app_port
            }
            initial_delay_seconds = 5
            period_seconds        = 5
          }
        }
      }
    }
  }
}

# Service
resource "kubernetes_service" "app" {
  metadata {
    name      = var.app_name
    namespace = kubernetes_namespace.app.metadata[0].name
  }

  spec {
    selector = {
      app = var.app_name
    }

    port {
      port        = 80
      target_port = var.app_port
    }

    type = "ClusterIP"
  }
}

# Route (OpenShift-specific)
resource "kubernetes_manifest" "route" {
  manifest = {
    apiVersion = "route.openshift.io/v1"
    kind       = "Route"
    metadata = {
      name      = var.app_name
      namespace = kubernetes_namespace.app.metadata[0].name
    }
    spec = {
      to = {
        kind = "Service"
        name = var.app_name
      }
      port = {
        targetPort = var.app_port
      }
      tls = {
        termination = "edge"
      }
    }
  }
}

# Horizontal Pod Autoscaler
resource "kubernetes_horizontal_pod_autoscaler_v2" "app" {
  metadata {
    name      = "${var.app_name}-hpa"
    namespace = kubernetes_namespace.app.metadata[0].name
  }

  spec {
    scale_target_ref {
      api_version = "apps/v1"
      kind        = "Deployment"
      name        = var.app_name
    }

    min_replicas = var.min_replicas
    max_replicas = var.max_replicas

    metric {
      type = "Resource"
      resource {
        name = "cpu"
        target {
          type               = "Utilization"
          average_utilization = 70
        }
      }
    }
  }
}
