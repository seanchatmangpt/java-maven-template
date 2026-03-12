# Deploy to AWS

This guide shows you how to deploy your Java Maven Template application to Amazon Web Services using Terraform.

## Prerequisites

- Terraform >= 1.6.0 installed
- AWS account with appropriate permissions
- AMI built with Packer (or use existing AMI)
- AWS credentials configured

## Steps

### 1. Navigate to Terraform Directory

```bash
cd docs/infrastructure/terraform/aws
```

### 2. Initialize Terraform

```bash
terraform init
```

### 3. Configure Variables

Create `terraform.tfvars`:

```hcl
aws_region    = "us-east-1"
ami_id        = "ami-xxxxxxxxxxxxxxxxx"  # Your Packer-built AMI
instance_type = "t3.medium"
key_name      = "your-ssh-key-pair"
environment   = "production"
app_name      = "jotp"
}
```

### 4. Review Deployment Plan

```bash
terraform plan
```

Review resources to be created:
- VPC and networking
- Security groups
- EC2 instance(s)
- Load balancer (optional)
- RDS database (optional)

### 5. Apply Configuration

```bash
terraform apply
```

Type `yes` to confirm.

### 6. Verify Deployment

```bash
# Get instance IP
terraform output public_ip

# SSH into instance
ssh -i ~/.ssh/your-key.pem ec2-user@$(terraform output -raw public_ip)

# Check application
curl http://localhost:8080/health
```

## Production Configuration

### Add Load Balancer

Add to `main.tf`:

```hcl
resource "aws_lb" "app" {
  name               = "${var.app_name}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id
}

resource "aws_lb_target_group" "app" {
  name     = "${var.app_name}-tg"
  port     = 8080
  protocol = "HTTP"
  vpc_id   = aws_vpc.main.id
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.app.arn
  port              = "80"
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}
```

### Add Auto Scaling

```hcl
resource "aws_autoscaling_group" "app" {
  name                = "${var.app_name}-asg"
  vpc_zone_identifier = aws_subnet.public[*].id
  target_group_arns   = [aws_lb_target_group.app.arn]
  min_size            = 2
  max_size            = 6
  health_check_type   = "ELB"

  launch_template {
    id      = aws_launch_template.app.id
    version = "$Latest"
  }
}

resource "aws_autoscaling_policy" "scale_up" {
  name                   = "${var.app_name}-scale-up"
  scaling_adjustment     = 1
  adjustment_type        = "ChangeInCapacity"
  autoscaling_group_name = aws_autoscaling_group.app.name
}
```

### Add RDS Database

```hcl
resource "aws_db_instance" "app" {
  identifier           = "${var.app_name}-db"
  engine               = "postgres"
  engine_version       = "15.4"
  instance_class       = "db.t3.medium"
  allocated_storage    = 20
  db_name              = "appdb"
  username             = var.db_username
  password             = var.db_password
  vpc_security_group_ids = [aws_security_group.db.id]
  db_subnet_group_name = aws_db_subnet_group.main.name
  skip_final_snapshot  = true
}
```

## Clean Up

```bash
terraform destroy
```

## Troubleshooting

| Error | Solution |
|-------|----------|
| `InvalidAMIID.NotFound` | Verify AMI exists in target region |
| `KeyPair.DoesNotExist` | Create key pair or specify correct name |
| `UnauthorizedOperation` | Check IAM permissions |

## Next Steps

- [Simulate AWS Locally](simulate-aws-locally.md) - Test without cloud costs
- [Configure CI/CD](configure-ci-cd.md) - Automate deployments
- [Manage Secrets](manage-secrets.md) - Secure credentials

## Related Resources

- [Terraform AWS Provider](https://registry.terraform.io/providers/hashicorp/aws/latest/docs)
- [AWS Best Practices](https://docs.aws.amazon.com/wellarchitect/latest/framework/welcome.html)
- [Terraform AWS Tutorial](https://developer.hashicorp.com/terraform/tutorials/aws-get-started)
