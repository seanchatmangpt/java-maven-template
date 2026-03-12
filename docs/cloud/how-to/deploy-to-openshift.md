# Deploy to OpenShift

This guide shows you how to deploy your Java Maven Template application to Red Hat OpenShift using Terraform and containerized deployment.

## Prerequisites

- Terraform >= 1.6.0 installed
- OpenShift cluster access
- OpenShift CLI (`oc`) installed
- Container image built and pushed to registry

## Steps

### 1. Build and Push Container Image

```bash
# Build JAR
./mvnw package -Dshade

# Build container image
docker build -t jotp:latest .

# Push to registry
docker tag jotp:latest <registry>/jotp:latest
docker push <registry>/jotp:latest
```

### 2. Login to OpenShift

```bash
# Login via token
oc login --token=<token> --server=https://api.<cluster>:6443

# Verify login
oc whoami
```

### 3. Navigate to Terraform Directory

```bash
cd docs/infrastructure/terraform/openshift
```

### 4. Initialize Terraform

```bash
terraform init
```

### 5. Configure Variables

Create `terraform.tfvars`:

```hcl
openshift_server_url = "https://api.<cluster>:6443"
openshift_token      = "<your-oc-token>"
namespace            = "java-maven-app"
app_name             = "jotp"
image                = "<registry>/jotp:latest"
replicas             = 2
}
```

### 6. Review Deployment Plan

```bash
terraform plan
```

### 7. Apply Configuration

```bash
terraform apply
```

Type `yes` to confirm.

### 8. Verify Deployment

```bash
# Get route URL
oc get route jotp -n java-maven-app

# Test application
curl http://jotp-java-maven-app.<cluster>/health
```

## Alternative: Deploy with OpenShift CLI

### Create Project

```bash
oc new-project java-maven-app
```

### Deploy from Image

```bash
oc new-app --name jotp \
  --docker-image=<registry>/jotp:latest

oc expose svc/jotp
```

### Deploy with YAML

Create `deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jotp
  labels:
    app: jotp
spec:
  replicas: 2
  selector:
    matchLabels:
      app: jotp
  template:
    metadata:
      labels:
        app: jotp
    spec:
      containers:
      - name: app
        image: <registry>/jotp:latest
        ports:
        - containerPort: 8080
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: jotp
spec:
  selector:
    app: jotp
  ports:
  - port: 8080
    targetPort: 8080
---
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: jotp
spec:
  to:
    kind: Service
    name: jotp
  port:
    targetPort: 8080
```

Apply:

```bash
oc apply -f deployment.yaml
```

## Production Configuration

### Add Horizontal Pod Autoscaler

```hcl
resource "kubernetes_horizontal_pod_autoscaler_v2" "app" {
  metadata {
    name      = var.app_name
    namespace = var.namespace
  }

  spec {
    scale_target_ref {
      api_version = "apps/v1"
      kind        = "Deployment"
      name        = var.app_name
    }

    min_replicas = 2
    max_replicas = 10

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
```

### Add ConfigMap and Secrets

```hcl
resource "kubernetes_config_map" "app" {
  metadata {
    name      = "${var.app_name}-config"
    namespace = var.namespace
  }

  data = {
    LOG_LEVEL = "INFO"
    JAVA_OPTS = "-Xmx512m"
  }
}

resource "kubernetes_secret" "app" {
  metadata {
    name      = "${var.app_name}-secrets"
    namespace = var.namespace
  }

  data = {
    DB_PASSWORD = base64encode(var.db_password)
  }
}
```

### Add Persistent Volume

```hcl
resource "kubernetes_persistent_volume_claim" "app" {
  metadata {
    name      = "${var.app_name}-pvc"
    namespace = var.namespace
  }

  spec {
    access_modes = ["ReadWriteOnce"]
    resources {
      requests = {
        storage = "10Gi"
      }
    }
  }
}
```

## Clean Up

```bash
# Using Terraform
terraform destroy

# Using CLI
oc delete project java-maven-app
```

## Troubleshooting

| Error | Solution |
|-------|----------|
| `ImagePullBackOff` | Check image exists and registry credentials |
| `CrashLoopBackOff` | Check logs with `oc logs` |
| `Unauthorized` | Verify token with `oc whoami -t` |

## Next Steps

- [Configure CI/CD](configure-ci-cd.md) - Automate deployments
- [Manage Secrets](manage-secrets.md) - Use OpenShift Secrets

## Related Resources

- [Terraform OpenShift Provider](https://registry.terraform.io/providers/openshift/openshift/latest/docs)
- [OpenShift Documentation](https://docs.openshift.com/)
- [Red Hat Developer Sandbox](https://developers.redhat.com/developer-sandbox)
