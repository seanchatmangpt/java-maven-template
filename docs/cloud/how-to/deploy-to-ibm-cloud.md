# Deploy to IBM Cloud

This guide shows you how to deploy your Java Maven Template application to IBM Cloud using Terraform.

## Prerequisites

- Terraform >= 1.6.0 installed
- IBM Cloud account
- IBM Cloud API key
- IBM Cloud CLI installed

## Steps

### 1. Set Environment Variables

```bash
export IBMCLOUD_API_KEY="<your-api-key>"
export IAAS_CLASSIC_API_KEY="<your-classic-api-key>"
export IAAS_CLASSIC_USERNAME="<your-username>"
```

### 2. Navigate to Terraform Directory

```bash
cd docs/infrastructure/terraform/ibm
```

### 3. Initialize Terraform

```bash
terraform init
```

### 4. Configure Variables

Create `terraform.tfvars`:

```hcl
region         = "us-south"
resource_group = "default"
image_name     = "ibm-ubuntu-22-04-2-minimal-amd64-1"
profile        = "bx2-2x8"
ssh_public_key = "ssh-rsa AAAA..."
vpc_name       = "java-maven-vpc"
subnet_name    = "java-maven-subnet"
instance_name  = "jotp"
}
```

### 5. Review Deployment Plan

```bash
terraform plan
```

Review resources to be created:
- VPC
- Subnet
- Public gateway
- Security group
- SSH key
- Virtual server instance
- Floating IP

### 6. Apply Configuration

```bash
terraform apply
```

Type `yes` to confirm.

### 7. Deploy Application

```bash
# Get instance IP
INSTANCE_IP=$(terraform output -raw floating_ip)

# Copy JAR to instance
scp -i ~/.ssh/id_rsa target/jotp-*.jar root@$INSTANCE_IP:/opt/app/

# SSH and start application
ssh -i ~/.ssh/id_rsa root@$INSTANCE_IP
java -jar /opt/app/jotp-*.jar
```

### 8. Verify Deployment

```bash
curl http://$INSTANCE_IP:8080/health
```

## Production Configuration

### Add Application Load Balancer

```hcl
resource "ibm_is_lb" "app" {
  name    = "${var.app_name}-lb"
  subnets = [ibm_is_subnet.app.id]
  type    = "public"
}

resource "ibm_is_lb_pool" "app" {
  lb       = ibm_is_lb.app.id
  name     = "${var.app_name}-pool"
  protocol = "http"
}

resource "ibm_is_lb_pool_member" "app" {
  lb             = ibm_is_lb.app.id
  pool           = ibm_is_lb_pool.app.id
  port           = 8080
  target_address = ibm_is_instance.app.primary_network_interface[0].primary_ipv4_address
}
```

### Add IBM Cloud Databases

```hcl
resource "ibm_database" "postgres" {
  name              = "${var.app_name}-postgres"
  service           = "databases-for-postgresql"
  plan              = "standard"
  location          = var.region
  resource_group_id = data.ibm_resource_group.group.id

  adminpassword = var.db_password
  members {
    allocation_count = 2
  }
}
```

### Add VPC File Storage

```hcl
resource "ibm_is_share" "app" {
  name            = "${var.app_name}-share"
  size            = 100
  profile         = "dp2"
  zone            = "${var.region}-1"
  resource_group  = data.ibm_resource_group.group.id

  access_control_list {
    vpc = ibm_is_vpc.app.id
  }
}
```

## Using IBM Cloud Schematics

IBM Cloud Schematics provides managed Terraform:

```bash
# Create workspace
ibmcloud schematics workspace create --name jotp --region us-south

# Point to your Terraform code
ibmcloud schematics template create --workspace-id <workspace-id> --template-url <git-repo-url>
```

## Clean Up

```bash
terraform destroy
```

## Troubleshooting

| Error | Solution |
|-------|----------|
| `401 Unauthorized` | Verify API key is valid |
| `QuotaExceeded` | Request quota increase |
| `ImageNotFound` | Check image name for region |

## Next Steps

- [Configure CI/CD](configure-ci-cd.md) - Automate deployments
- [Manage Secrets](manage-secrets.md) - Use IBM Cloud Secrets Manager

## Related Resources

- [Terraform IBM Provider](https://registry.terraform.io/providers/IBM-Cloud/ibm/latest/docs)
- [IBM Cloud VPC Documentation](https://cloud.ibm.com/docs/vpc)
- [IBM Cloud Schematics](https://cloud.ibm.com/docs/schematics?topic=schematics-getting-started)
