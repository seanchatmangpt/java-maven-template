# Packer Builders Reference

This document provides detailed configuration reference for Packer builders used in multi-cloud deployments.

## Amazon EBS Builder

Builds AMI images for AWS using EBS-backed volumes.

### Basic Configuration

```hcl
source "amazon-ebs" "java-maven" {
  // Required
  ami_name      = "jotp-{{timestamp}}"
  instance_type = "t3.medium"
  region        = "us-east-1"
  source_ami    = "ami-0c7217cdde317cfec"
  ssh_username  = "ec2-user"

  // Optional
  ami_description  = "Java Maven Template AMI"
  ami_virtualization_type = "hvm"

  // Tags
  tags = {
    Name    = "jotp"
    Project = "jotp"
    Version = "1.0.0"
  }
}
```

### Configuration Options

| Option | Required | Description |
|--------|----------|-------------|
| `ami_name` | Yes | Name of the resulting AMI |
| `instance_type` | Yes | EC2 instance type for building |
| `region` | Yes | AWS region |
| `source_ami` | Yes | Base AMI ID |
| `ssh_username` | Yes | SSH username for connection |
| `ami_description` | No | AMI description |
| `ami_virtualization_type` | No | Virtualization type (hvm/paravirtual) |
| `tags` | No | Tags to apply to AMI |
| `vpc_id` | No | VPC ID for build instance |
| `subnet_id` | No | Subnet ID for build instance |
| `security_group_ids` | No | Security group IDs |

### Common Source AMIs

| OS | AMI ID (us-east-1) | SSH User |
|----|-------------------|----------|
| Amazon Linux 2023 | `ami-0c7217cdde317cfec` | `ec2-user` |
| Ubuntu 22.04 LTS | Search Ubuntu Cloud Finder | `ubuntu` |
| RHEL 9 | AWS Marketplace | `ec2-user` |

**Reference**: [Packer Amazon EBS Builder](https://developer.hashicorp.com/packer/plugins/builders/amazon/ebs)

---

## Azure ARM Builder

Builds VM images for Microsoft Azure using ARM deployments.

### Basic Configuration

```hcl
source "azure-arm" "java-maven" {
  // Authentication
  client_id       = var.client_id
  client_secret   = var.client_secret
  subscription_id = var.subscription_id
  tenant_id       = var.tenant_id

  // Image settings
  managed_image_name                = "jotp"
  managed_image_resource_group_name = "packer-images"

  // Build VM settings
  build_resource_group_name = "packer-build-rg"
  location                  = "eastus"
  vm_size                   = "Standard_B2s"

  // Source image
  image_publisher = "Canonical"
  image_offer     = "0001-com-ubuntu-server-jammy"
  image_sku       = "22_04-lts"
  image_version   = "latest"

  // OS settings
  os_type  = "Linux"
  ssh_username = "packer"
}
```

### Configuration Options

| Option | Required | Description |
|--------|----------|-------------|
| `client_id` | Yes | Azure service principal client ID |
| `client_secret` | Yes | Azure service principal secret |
| `subscription_id` | Yes | Azure subscription ID |
| `tenant_id` | Yes | Azure tenant ID |
| `managed_image_name` | Yes | Name of resulting image |
| `managed_image_resource_group_name` | Yes | Resource group for image |
| `location` | Yes | Azure region |
| `vm_size` | No | VM size for building |
| `image_publisher` | Yes* | Source image publisher |
| `image_offer` | Yes* | Source image offer |
| `image_sku` | Yes* | Source image SKU |
| `os_type` | Yes | Linux or Windows |

**Reference**: [Packer Azure ARM Builder](https://developer.hashicorp.com/packer/plugins/builders/azure/arm)

---

## Google Compute Builder

Builds VM images for Google Cloud Platform.

### Basic Configuration

```hcl
source "googlecompute" "java-maven" {
  // Authentication (uses Application Default Credentials)
  project_id = var.project_id

  // Image settings
  image_name        = "jotp-{{timestamp}}"
  image_family      = "java-maven"
  image_description = "Java Maven Template Image"

  // Build instance settings
  machine_type = "e2-medium"
  zone         = "us-central1-a"

  // Source image
  source_image_project = "ubuntu-os-cloud"
  source_image_family  = "ubuntu-2204-lts"

  // Network
  network = "default"
  subnetwork = "default"

  // SSH
  ssh_username = "packer"
}
```

### Configuration Options

| Option | Required | Description |
|--------|----------|-------------|
| `project_id` | Yes | GCP project ID |
| `image_name` | Yes | Name of resulting image |
| `image_family` | No | Image family |
| `machine_type` | No | Instance type for building |
| `zone` | Yes | GCP zone |
| `source_image_project` | Yes* | Source image project |
| `source_image_family` | Yes* | Source image family |
| `network` | No | VPC network |
| `ssh_username` | Yes | SSH username |

**Reference**: [Packer Google Compute Builder](https://developer.hashicorp.com/packer/plugins/builders/googlecompute)

---

## Oracle OCI Builder

Builds custom images for Oracle Cloud Infrastructure.

### Basic Configuration

```hcl
source "oracle-oci" "java-maven" {
  // Authentication
  tenancy_ocid     = var.tenancy_ocid
  user_ocid        = var.user_ocid
  fingerprint      = var.fingerprint
  private_key_path = var.private_key_path
  region           = var.region

  // Image settings
  base_image_ocid = var.base_image_ocid
  compartment_ocid = var.compartment_ocid
  image_name      = "jotp-{{timestamp}}"

  // Build instance settings
  shape          = "VM.Standard.E4.Flex"
  availability_domain = "AD-1"
  subnet_ocid    = var.subnet_ocid

  // SSH
  ssh_username = "opc"
  ssh_private_key_file = var.ssh_private_key
}
```

### Configuration Options

| Option | Required | Description |
|--------|----------|-------------|
| `tenancy_ocid` | Yes | OCI tenancy OCID |
| `user_ocid` | Yes | OCI user OCID |
| `fingerprint` | Yes | API key fingerprint |
| `private_key_path` | Yes | Path to API private key |
| `region` | Yes | OCI region |
| `base_image_ocid` | Yes | Source image OCID |
| `compartment_ocid` | Yes | Compartment OCID |
| `shape` | Yes | Instance shape |
| `ssh_username` | Yes | SSH username |

**Reference**: [Packer OCI Builder](https://developer.hashicorp.com/packer/plugins/builders/oracle/oci)

---

## Common Provisioners

### Shell Provisioner

```hcl
provisioner "shell" {
  inline = [
    "sudo apt-get update",
    "sudo apt-get install -y openjdk-21-jre-headless",
  ]
}
```

### File Provisioner

```hcl
provisioner "file" {
  source      = "target/app.jar"
  destination = "/tmp/app.jar"
}
```

### Ansible Provisioner

```hcl
provisioner "ansible" {
  playbook_file = "./playbook.yml"
  extra_arguments = [
    "--extra-vars", "app_version=1.0.0"
  ]
}
```

---

## Build Block

Common build configuration:

```hcl
build {
  sources = [
    "source.amazon-ebs.java-maven",
    "source.azure-arm.java-maven",
  ]

  provisioner "shell" {
    inline = ["echo 'Building image...'"]
  }

  post-processor "manifest" {
    output = "manifest.json"
  }
}
```

## Related Resources

- [Packer Documentation](https://developer.hashicorp.com/packer/docs)
- [Packer Plugins](https://developer.hashicorp.com/packer/plugins)
- [Build AMI How-to](../how-to/build-ami-with-packer.md)
