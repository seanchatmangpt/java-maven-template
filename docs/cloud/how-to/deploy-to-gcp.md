# Deploy to GCP

This guide shows you how to deploy your Java Maven Template application to Google Cloud Platform using Terraform.

## Prerequisites

- Terraform >= 1.6.0 installed
- GCP project with Compute Engine API enabled
- VM image built with Packer
- Google Cloud SDK authenticated

## Steps

### 1. Authenticate with GCP

```bash
gcloud auth application-default login
```

### 2. Navigate to Terraform Directory

```bash
cd docs/infrastructure/terraform/gcp
```

### 3. Initialize Terraform

```bash
terraform init
```

### 4. Configure Variables

Create `terraform.tfvars`:

```hcl
project_id   = "your-project-id"
region       = "us-central1"
zone         = "us-central1-a"
image_name   = "jotp-xxxxx"
machine_type = "e2-medium"
environment  = "production"
app_name     = "jotp"
}
```

### 5. Review Deployment Plan

```bash
terraform plan
```

Review resources to be created:
- VPC network
- Subnet
- Firewall rules
- Static IP
- Compute instance

### 6. Apply Configuration

```bash
terraform apply
```

Type `yes` to confirm.

### 7. Verify Deployment

```bash
# Get instance IP
terraform output instance_ip

# SSH into instance
gcloud compute ssh jotp --zone=us-central1-a

# Check application
curl http://localhost:8080/health
```

## Production Configuration

### Add Managed Instance Group

```hcl
resource "google_compute_instance_template" "app" {
  name        = "${var.app_name}-template"
  machine_type = var.machine_type

  disk {
    source_image = "selfLink of your image"
    auto_delete  = true
    boot         = true
  }

  network_interface {
    network = google_compute_network.main.self_link
    access_config {}
  }

  metadata = {
    startup-script = "#!/bin/bash\njava -jar /opt/app/app.jar"
  }
}

resource "google_compute_instance_group_manager" "app" {
  name = "${var.app_name}-igm"
  zone = var.zone

  base_instance_name = var.app_name
  target_size        = 2

  version {
    instance_template = google_compute_instance_template.app.self_link
    name              = "primary"
  }

  named_port {
    name = "http"
    port = 8080
  }
}
```

### Add Cloud SQL Database

```hcl
resource "google_sql_database_instance" "app" {
  name             = "${var.app_name}-db"
  database_version = "POSTGRES_15"
  region           = var.region

  settings {
    tier = "db-custom-2-4096"

    ip_configuration {
      ipv4_enabled = true
      authorized_networks {
        value = google_compute_address.app.address
      }
    }
  }
}

resource "google_sql_database" "app" {
  name     = "appdb"
  instance = google_sql_database_instance.app.name
}
```

### Add Cloud Load Balancer

```hcl
resource "google_compute_global_forwarding_rule" "app" {
  name       = "${var.app_name}-lb"
  target     = google_compute_target_http_proxy.app.self_link
  port_range = "80"
}

resource "google_compute_target_http_proxy" "app" {
  name    = "${var.app_name}-http-proxy"
  url_map = google_compute_url_map.app.self_link
}

resource "google_compute_url_map" "app" {
  name            = "${var.app_name}-url-map"
  default_service = google_compute_backend_service.app.self_link
}

resource "google_compute_backend_service" "app" {
  name        = "${var.app_name}-backend"
  port_name   = "http"
  protocol    = "HTTP"
  timeout_sec = 10

  backend {
    group = google_compute_instance_group_manager.app.instance_group
  }

  health_checks = [google_compute_health_check.app.self_link]
}
```

## Clean Up

```bash
terraform destroy
```

## Troubleshooting

| Error | Solution |
|-------|----------|
| `PERMISSION_DENIED` | Check IAM roles (Compute Admin) |
| `IMAGE_NOT_FOUND` | Verify image name and project |
| `QUOTA_EXCEEDED` | Request quota increase |

## Next Steps

- [Configure CI/CD](configure-ci-cd.md) - Automate deployments
- [Manage Secrets](manage-secrets.md) - Use Secret Manager

## Related Resources

- [Terraform Google Provider](https://registry.terraform.io/providers/hashicorp/google/latest/docs)
- [GCP Architecture Framework](https://cloud.google.com/architecture/framework)
- [GCP Terraform Guide](https://cloud.google.com/docs/terraform)
