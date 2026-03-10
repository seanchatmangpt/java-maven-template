# Security Best Practices

This document explains security best practices for multi-cloud deployments.

## Security Layers

```
┌─────────────────────────────────────────────────────────────┐
│                      Application                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           Application Security                       │   │
│  │   (Input validation, auth, encryption)              │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                      Container/Image                         │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           Container Security                         │   │
│  │   (Vulnerability scanning, minimal base)            │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                      Infrastructure                          │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           Infrastructure Security                    │   │
│  │   (Network, IAM, encryption at rest)                │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                      Network                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           Network Security                           │   │
│  │   (VPC, security groups, NACLs)                      │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Network Security

### Principle of Least Privilege

Only allow necessary traffic:

```hcl
# Good: Specific port from specific source
resource "aws_security_group_rule" "app_ingress" {
  type              = "ingress"
  from_port         = 8080
  to_port           = 8080
  protocol          = "tcp"
  cidr_blocks       = ["10.0.0.0/8"]  # Internal only
  security_group_id = aws_security_group.app.id
}

# Bad: Open to the world
# cidr_blocks = ["0.0.0.0/0"]
```

### Network Segmentation

```
┌─────────────────────────────────────────────────────────────┐
│                          VPC                                 │
│                                                              │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐  │
│  │   Public      │  │   Private     │  │   Database    │  │
│  │   Subnet      │  │   Subnet      │  │   Subnet      │  │
│  │               │  │               │  │               │  │
│  │ ┌───────────┐ │  │ ┌───────────┐ │  │ ┌───────────┐ │  │
│  │ │    ALB    │ │  │ │   App     │ │  │ │    DB     │ │  │
│  │ └───────────┘ │  │ │ Instances │ │  │ │ Instance  │ │  │
│  │               │  │ └───────────┘ │  │ └───────────┘ │  │
│  └───────────────┘  └───────────────┘  └───────────────┘  │
│         │                   │                   │          │
│         └───────────────────┴───────────────────┘          │
│                    Security Groups                         │
└─────────────────────────────────────────────────────────────┘
```

### Security Group Rules

```hcl
# ALB: Allow HTTPS from internet
resource "aws_security_group_rule" "alb_https" {
  type              = "ingress"
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.alb.id
}

# App: Allow traffic from ALB only
resource "aws_security_group_rule" "app_from_alb" {
  type                     = "ingress"
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.alb.id
  security_group_id        = aws_security_group.app.id
}

# DB: Allow traffic from App only
resource "aws_security_group_rule" "db_from_app" {
  type                     = "ingress"
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.app.id
  security_group_id        = aws_security_group.db.id
}
```

## Identity and Access Management (IAM)

### Principle of Least Privilege

```hcl
# Good: Specific permissions for specific resources
resource "aws_iam_role_policy" "app_s3_access" {
  name = "app-s3-access"
  role = aws_iam_role.app.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject"
        ]
        Resource = "${aws_s3_bucket.app.arn}/*"
      }
    ]
  })
}

# Bad: Too permissive
# Action = ["s3:*"]
# Resource = ["*"]
```

### Service Account Pattern

```hcl
# Create service account for application
resource "aws_iam_role" "app" {
  name = "java-maven-app-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
        Action = "sts:AssumeRole"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "app" {
  name = "java-maven-app-profile"
  role = aws_iam_role.app.name
}
```

## Secrets Management

### Never Hardcode Secrets

```hcl
# Bad: Hardcoded credentials
# password = "my-password-123"

# Good: Reference secrets manager
data "aws_secretsmanager_secret_version" "db_password" {
  secret_id = "java-maven-app/db-password"
}

resource "aws_db_instance" "app" {
  password = data.aws_secretsmanager_secret_version.db_password.secret_string
}
```

### Environment Variables

```bash
# Use environment variables for sensitive values
export TF_VAR_db_password="secure-password"
terraform apply
```

### Secrets Rotation

| Secret Type | Rotation Frequency |
|-------------|-------------------|
| Database passwords | 30-90 days |
| API keys | 90 days |
| SSH keys | 180 days |
| Service account keys | 90 days |

## Encryption

### Encryption at Rest

```hcl
# RDS encryption
resource "aws_db_instance" "app" {
  # ...
  storage_encrypted = true
  kms_key_id       = aws_kms_key.database.arn
}

# S3 encryption
resource "aws_s3_bucket_server_side_encryption_configuration" "app" {
  bucket = aws_s3_bucket.app.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.s3.arn
    }
  }
}
```

### Encryption in Transit

```hcl
# Enforce HTTPS
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.app.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS-1-2-2017-01"
  certificate_arn   = aws_acm_certificate.app.arn
}

# Redirect HTTP to HTTPS
resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.app.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}
```

## Audit and Compliance

### Logging

```hcl
# CloudTrail for API logging
resource "aws_cloudtrail" "main" {
  name           = "java-maven-trail"
  s3_bucket_name = aws_s3_bucket.logs.id

  event_selector {
    read_write_type           = "All"
    include_management_events = true
  }
}

# VPC Flow Logs
resource "aws_flow_log" "main" {
  iam_role_arn    = aws_iam_role.flow_logs.arn
  log_destination = aws_cloudwatch_log_group.flow_logs.arn
  traffic_type    = "ALL"
  vpc_id          = aws_vpc.main.id
}
```

### Monitoring

```hcl
# CloudWatch Alarms for security events
resource "aws_cloudwatch_metric_alarm" "unauthorized_api_calls" {
  alarm_name          = "unauthorized-api-calls"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = "1"
  metric_name         = "UnauthorizedAPICalls"
  namespace           = "CloudTrail"
  period              = "300"
  statistic           = "Sum"
  threshold           = "1"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}
```

## Container Security

### Image Scanning

```yaml
# GitHub Actions - Container scanning
- name: Build and scan image
  uses: aquasecurity/trivy-action@master
  with:
    image-ref: 'myapp:latest'
    format: 'table'
    exit-code: '1'
    severity: 'CRITICAL,HIGH'
```

### Minimal Base Image

```dockerfile
# Bad: Full OS image
# FROM ubuntu:22.04

# Good: Minimal image
FROM eclipse-temurin:21-jre-alpine

# Run as non-root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --chown=appuser:appgroup app.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

## Infrastructure as Code Security

### Terraform Security

```hcl
# Enable versioning on state bucket
resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Enable encryption on state bucket
resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}

# Block public access
resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
```

### Static Analysis

```bash
# Terraform security scanning
tfsec .

# Checkov
checkov -d .

# Terrascan
terrascan scan -i terraform
```

## Security Checklist

### Network
- [ ] Use private subnets for sensitive resources
- [ ] Restrict security group rules to minimum required
- [ ] Enable VPC Flow Logs
- [ ] Use Network ACLs for additional protection

### Identity
- [ ] Use IAM roles, not access keys on instances
- [ ] Implement least privilege permissions
- [ ] Enable MFA for all users
- [ ] Rotate credentials regularly

### Data
- [ ] Encrypt data at rest
- [ ] Encrypt data in transit
- [ ] Use customer-managed keys for sensitive data
- [ ] Implement backup encryption

### Monitoring
- [ ] Enable CloudTrail/Activity Logs
- [ ] Configure security alerts
- [ ] Monitor for anomalous activity
- [ ] Regular security reviews

## Related Topics

- [Architecture Overview](architecture-overview.md)
- [Multi-Cloud Strategy](multi-cloud-strategy.md)
- [Manage Secrets](../how-to/manage-secrets.md)
