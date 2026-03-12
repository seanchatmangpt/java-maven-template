# Configuration Schema Reference

This document provides schema references for configuration files used in multi-cloud deployments.

## Packer HCL Schema

### Variable Block

```hcl
variable "region" {
  type        = string
  default     = "us-east-1"
  description = "AWS region for the build"
  sensitive   = false  // Mark as sensitive
}

variable "instance_types" {
  type        = list(string)
  default     = ["t3.micro", "t3.small"]
}

variable "tags" {
  type = map(string)
  default = {
    Environment = "production"
  }
}

variable "config" {
  type = object({
    name = string
    size = number
  })
}
```

### Source Block

```hcl
source "amazon-ebs" "java-maven" {
  // Required fields
  ami_name      = string
  instance_type = string
  region        = string
  source_ami    = string
  ssh_username  = string

  // Optional fields
  ami_description       = optional(string)
  ami_virtualization_type = optional(string, "hvm")
  vpc_id               = optional(string)
  subnet_id            = optional(string)
  security_group_ids   = optional(list(string))
  tags                 = optional(map(string))
}
```

### Build Block

```hcl
build {
  sources = [
    "source.amazon-ebs.java-maven",
  ]

  provisioner "shell" {
    inline = list(string)
    script = optional(string)
    scripts = optional(list(string))
  }

  provisioner "file" {
    source      = string
    destination = string
    direction   = optional(string, "upload")
  }

  post-processor "manifest" {
    output = string
  }
}
```

---

## Terraform HCL Schema

### Provider Block

```hcl
terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    bucket = string
    key    = string
    region = string
  }
}

provider "aws" {
  region = string

  assume_role {
    role_arn = string
  }

  default_tags {
    tags = map(string)
  }
}
```

### Variable Block

```hcl
variable "instance_type" {
  type        = string
  default     = "t3.medium"
  description = "EC2 instance type"
  nullable    = false
  sensitive   = false

  validation {
    condition     = contains(["t3.micro", "t3.small", "t3.medium"], var.instance_type)
    error_message = "Instance type must be t3.micro, t3.small, or t3.medium."
  }
}

variable "environment" {
  type = object({
    name   = string
    region = string
    vpc_cidr = string
  })
}

variable "ports" {
  type = list(number)
  default = [22, 80, 443]
}
```

### Resource Block

```hcl
resource "aws_instance" "app" {
  ami                    = string
  instance_type          = string
  subnet_id              = string
  vpc_security_group_ids = list(string)
  key_name               = string
  user_data              = optional(string)

  root_block_device {
    volume_size = number
    volume_type = string
  }

  tags = map(string)

  lifecycle {
    create_before_destroy = true
    prevent_destroy       = false
    ignore_changes        = [tags]
  }
}
```

### Output Block

```hcl
output "public_ip" {
  value       = aws_instance.app.public_ip
  description = "Public IP of the instance"
  sensitive   = false
}

output "instance_id" {
  value = aws_instance.app.id
}
```

---

## Terraform tfvars Schema

### Basic tfvars

```hcl
# String values
region        = "us-east-1"
instance_type = "t3.medium"

# Number values
instance_count = 3

# Boolean values
enable_monitoring = true

# List values
availability_zones = ["us-east-1a", "us-east-1b"]

# Map values
tags = {
  Environment = "production"
  Project     = "jotp"
}

# Object values
database = {
  engine  = "postgres"
  version = "15"
  size    = "db.t3.medium"
}
```

---

## Docker Compose Schema

```yaml
version: '3.8'

services:
  service-name:
    image: string
    container_name: string
    build:
      context: string
      dockerfile: string
    ports:
      - "host:container"
    environment:
      - KEY=VALUE
    env_file:
      - .env
    volumes:
      - host_path:container_path
    networks:
      - network_name
    depends_on:
      - other_service
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 30s
      timeout: 10s
      retries: 3
    restart: unless-stopped

networks:
  network_name:
    driver: bridge

volumes:
  volume_name:
    driver: local
```

---

## GitHub Actions Schema

### Workflow

```yaml
name: Workflow Name

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
  workflow_dispatch:
    inputs:
      environment:
        description: 'Environment'
        required: true
        default: 'staging'

env:
  GLOBAL_VAR: value

jobs:
  job-name:
    runs-on: ubuntu-latest
    environment: production
    needs: [dependency-job]
    if: github.ref == 'refs/heads/main'

    steps:
      - name: Step Name
        uses: actions/checkout@v4

      - name: Run command
        run: echo "Hello"
        env:
          STEP_VAR: value
```

---

## Kubernetes/OpenShift Schema

### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: app-name
  namespace: namespace
  labels:
    app: app-name
spec:
  replicas: 2
  selector:
    matchLabels:
      app: app-name
  template:
    metadata:
      labels:
        app: app-name
    spec:
      containers:
      - name: app
        image: image:tag
        ports:
        - containerPort: 8080
        env:
        - name: ENV_VAR
          value: "value"
        resources:
          requests:
            memory: "256Mi"
            cpu: "250m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
```

### Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: app-name
  namespace: namespace
spec:
  selector:
    app: app-name
  ports:
  - port: 80
    targetPort: 8080
  type: ClusterIP
```

### Route (OpenShift)

```yaml
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: app-name
  namespace: namespace
spec:
  to:
    kind: Service
    name: app-name
  port:
    targetPort: 8080
  tls:
    termination: edge
```

---

## Environment Files

### .env

```bash
# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=appdb
DB_USER=appuser
DB_PASSWORD=secret

# Application
APP_PORT=8080
LOG_LEVEL=info
```

### .tfvars (gitignored)

```hcl
db_password    = "secure-password"
api_key        = "api-key-value"
ssh_private_key = "-----BEGIN RSA PRIVATE KEY-----..."
```

---

## Validation Commands

### Packer

```bash
packer validate config.pkr.hcl
packer fmt -check .
```

### Terraform

```bash
terraform validate
terraform fmt -check
terraform plan
```

### YAML

```bash
yamllint docker-compose.yml
yamllint .github/workflows/
```

## Related Resources

- [Packer HCL Syntax](https://developer.hashicorp.com/packer/docs/templates/hcl_templates)
- [Terraform Configuration Syntax](https://developer.hashicorp.com/terraform/language/syntax)
- [Docker Compose Specification](https://docs.docker.com/compose/compose-file/)
- [Kubernetes API Reference](https://kubernetes.io/docs/reference/kubernetes-api/)
