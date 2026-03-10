# Packer vs Terraform

This document explains the differences between Packer and Terraform, and when to use each tool.

## Overview

| Aspect | Packer | Terraform |
|--------|--------|-----------|
| Purpose | Build machine images | Provision infrastructure |
| Output | AMI, VHD, Image | Running infrastructure |
| State | Stateless | Stateful |
| Timing | Build time | Deploy time |

## Packer: Image Builder

Packer creates machine images (AMIs, VHDs, etc.) with pre-installed software and configurations.

### When to Use Packer

- Creating golden images with pre-baked software
- Ensuring consistent base images across environments
- Reducing deployment time (pre-installed dependencies)
- Immutable infrastructure patterns

### Packer Workflow

```
┌──────────┐     ┌──────────────┐     ┌─────────────┐
│  Source  │ ──→ │  Provision   │ ──→ │    Image    │
│  Image   │     │   (Install)  │     │   Output    │
└──────────┘     └──────────────┘     └─────────────┘
```

### Example: Building Java Application Image

```hcl
source "amazon-ebs" "java-maven" {
  ami_name      = "java-maven-{{timestamp}}"
  instance_type = "t3.medium"
  region        = "us-east-1"
  source_ami    = "ami-0c7217cdde317cfec"
  ssh_username  = "ec2-user"
}

build {
  sources = ["source.amazon-ebs.java-maven"]

  provisioner "shell" {
    inline = [
      "sudo yum install -y java-21-openjdk",
      "sudo mkdir -p /opt/app",
    ]
  }

  provisioner "file" {
    source      = "target/app.jar"
    destination = "/tmp/app.jar"
  }

  provisioner "shell" {
    inline = [
      "sudo mv /tmp/app.jar /opt/app/",
    ]
  }
}
```

## Terraform: Infrastructure Provisioner

Terraform provisions and manages infrastructure resources (VMs, networks, databases, etc.).

### When to Use Terraform

- Provisioning cloud resources
- Managing infrastructure state
- Creating networks, load balancers, databases
- Multi-cloud deployments

### Terraform Workflow

```
┌──────────────┐     ┌──────────┐     ┌─────────────┐
│  Configure   │ ──→ │   Plan   │ ──→ │   Apply     │
│   (.tf)      │     │          │     │             │
└──────────────┘     └──────────┘     └─────────────┘
```

### Example: Deploying Infrastructure

```hcl
resource "aws_instance" "app" {
  ami                    = var.ami_id  # From Packer
  instance_type          = "t3.medium"
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]

  tags = {
    Name = "java-maven-app"
  }
}
```

## Comparison Matrix

| Feature | Packer | Terraform |
|---------|--------|-----------|
| **State** | None | Maintains state file |
| **Idempotency** | Always creates new image | Updates only changes |
| **Execution** | One-time build | Continuous management |
| **Rollback** | Use previous image | `terraform destroy` |
| **Speed** | Slow (builds image) | Fast (provisions resources) |
| **Use Case** | Golden images | Infrastructure |

## Workflow Integration

### Typical Workflow

```
1. Packer: Build machine image
   └─→ Creates AMI: ami-12345678

2. Terraform: Provision infrastructure
   └─→ Uses AMI to create EC2 instance
   └─→ Creates VPC, security groups, etc.

3. Application: Runs on provisioned infrastructure
```

### Code Example

**Step 1: Build with Packer**

```bash
cd packer/aws
packer build app.pkr.hcl
# Output: ami-12345678
```

**Step 2: Deploy with Terraform**

```bash
cd terraform/aws
terraform apply -var="ami_id=ami-12345678"
```

## When to Choose Which

### Use Packer When:

1. You need pre-configured images
2. Boot time matters (pre-installed software)
3. You follow immutable infrastructure
4. You need consistent base images

### Use Terraform When:

1. You need to provision cloud resources
2. Infrastructure changes over time
3. You need state management
4. Multiple environments (dev/staging/prod)

### Use Both When:

1. Building production deployments
2. Following DevOps best practices
3. Need both images and infrastructure
4. Implementing GitOps workflows

## Immutable vs Mutable Infrastructure

### Immutable (Packer)

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│  Image   │ ──→ │ Instance │ ──→ │   Run    │
│  v1.0    │     │   A      │     │          │
└──────────┘     └──────────┘     └──────────┘

Update: Replace entire instance
┌──────────┐     ┌──────────┐     ┌──────────┐
│  Image   │ ──→ │ Instance │ ──→ │   Run    │
│  v1.1    │     │   B      │     │          │
└──────────┘     └──────────┘     └──────────┘
```

### Mutable (Terraform only)

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│  Base    │ ──→ │ Instance │ ──→ │ Configure│
│  Image   │     │          │     │ on boot  │
└──────────┘     └──────────┘     └──────────┘

Update: Modify running instance
```

## Best Practices

### Packer Best Practices

1. Keep builds deterministic
2. Use versioned source images
3. Minimize provisioner complexity
4. Test images locally when possible

### Terraform Best Practices

1. Use remote state
2. Modularize configurations
3. Use workspaces for environments
4. Implement state locking

## Related Topics

- [Architecture Overview](architecture-overview.md)
- [Multi-Cloud Strategy](multi-cloud-strategy.md)
- [Build AMI with Packer](../how-to/build-ami-with-packer.md)
- [Deploy to AWS](../how-to/deploy-to-aws.md)
