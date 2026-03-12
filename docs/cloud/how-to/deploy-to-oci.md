# Deploy to OCI

This guide shows you how to deploy your Java Maven Template application to Oracle Cloud Infrastructure using Terraform.

## Prerequisites

- Terraform >= 1.6.0 installed
- OCI account with appropriate permissions
- VM image built with Packer
- OCI CLI configured

## Steps

### 1. Navigate to Terraform Directory

```bash
cd docs/infrastructure/terraform/oci
```

### 2. Initialize Terraform

```bash
terraform init
```

### 3. Configure Variables

Create `terraform.tfvars`:

```hcl
tenancy_ocid     = "ocid1.tenancy.oc1..xxxxx"
user_ocid        = "ocid1.user.oc1..xxxxx"
fingerprint      = "xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx"
private_key_path = "~/.oci/oci_api_key.pem"
region           = "us-phoenix-1"
compartment_ocid = "ocid1.compartment.oc1..xxxxx"
image_ocid       = "ocid1.image.oc1.phx.xxxxx"
shape            = "VM.Standard.E4.Flex"
ssh_public_key   = "~/.ssh/id_rsa.pub"
app_name         = "jotp"
}
```

### 4. Review Deployment Plan

```bash
terraform plan
```

Review resources to be created:
- VCN (Virtual Cloud Network)
- Subnet
- Security list
- Internet gateway
- Compute instance

### 5. Apply Configuration

```bash
terraform apply
```

Type `yes` to confirm.

### 6. Verify Deployment

```bash
# Get instance public IP
terraform output public_ip

# SSH into instance
ssh -i ~/.ssh/id_rsa opc@$(terraform output -raw public_ip)

# Check application
curl http://localhost:8080/health
```

## Production Configuration

### Add Load Balancer

```hcl
resource "oci_load_balancer_load_balancer" "app" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.app_name}-lb"
  shape          = "flexible"
  shape_details {
    minimum_bandwidth_in_mbps = 10
    maximum_bandwidth_in_mbps = 100
  }
  subnet_ids = [oci_core_subnet.public.id]
}

resource "oci_load_balancer_backend_set" "app" {
  load_balancer_id = oci_load_balancer_load_balancer.app.id
  name             = "${var.app_name}-backend"
  policy           = "ROUND_ROBIN"

  health_checker {
    protocol    = "HTTP"
    port        = 8080
    url_path    = "/health"
  }
}

resource "oci_load_balancer_backend" "app" {
  load_balancer_id = oci_load_balancer_load_balancer.app.id
  backendset_name  = oci_load_balancer_backend_set.app.name
  ip_address       = oci_core_instance.app.private_ip
  port             = 8080
}
```

### Add Autonomous Database

```hcl
resource "oci_database_autonomous_database" "app" {
  compartment_id           = var.compartment_ocid
  db_name                  = "appdb"
  display_name             = "${var.app_name}-adb"
  admin_password           = var.db_password
  data_storage_size_in_tbs = 1
  cpu_core_count           = 1
  is_free_tier             = false
  db_workload              = "OLTP"
  license_model            = "BRING_YOUR_OWN_LICENSE"
  subnet_id                = oci_core_subnet.private.id
}
```

### Use Flexible Shape

```hcl
resource "oci_core_instance" "app" {
  compartment_id      = var.compartment_ocid
  availability_domain = data.oci_identity_availability_domain.ad.name
  display_name        = var.app_name
  shape               = "VM.Standard.E4.Flex"

  shape_config {
    ocpus         = 2
    memory_in_gbs = 16
  }

  source_details {
    source_type = "image"
    source_id   = var.image_ocid
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.public.id
    assign_public_ip = true
  }
}
```

## Using OCI Free Tier

OCI Always Free includes:
- 2 AMD VMs (1/8 OCPU, 1GB RAM)
- 4 Arm Ampere A1 cores + 24GB RAM
- 2 Autonomous Databases
- 200GB block storage

Configure for Free Tier:

```hcl
shape = "VM.Standard.E2.1.Micro"  # Always Free AMD
# or
shape = "VM.Standard.A1.Flex"     # Always Free Arm

shape_config {
  ocpus         = 1
  memory_in_gbs = 6
}
```

## Clean Up

```bash
terraform destroy
```

## Troubleshooting

| Error | Solution |
|-------|----------|
| `NotAuthenticated` | Verify API key and fingerprint |
| `ServiceLimitExceeded` | Request limit increase or use different shape |
| `ImageNotFound` | Check image OCID and region |

## Next Steps

- [Configure CI/CD](configure-ci-cd.md) - Automate deployments
- [Manage Secrets](manage-secrets.md) - Use OCI Vault

## Related Resources

- [Terraform OCI Provider](https://registry.terraform.io/providers/oracle/oci/latest/docs)
- [OCI Documentation](https://docs.oracle.com/en-us/iaas/)
- [OCI Terraform Guide](https://docs.oracle.com/en-us/iaas/Content/dev/terraform/home.htm)
