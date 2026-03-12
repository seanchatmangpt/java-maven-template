# Terraform Providers Reference

This document provides detailed configuration reference for Terraform providers used in multi-cloud deployments.

## AWS Provider

### Configuration

```hcl
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  # Optional: Assume role
  assume_role {
    role_arn = "arn:aws:iam::123456789012:role/TerraformRole"
  }

  # Optional: Default tags
  default_tags {
    tags = {
      Project     = "jotp"
      Environment = "production"
      ManagedBy   = "terraform"
    }
  }
}
```

### Common Resources

```hcl
# VPC
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true
}

# Subnet
resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.1.0/24"
  map_public_ip_on_launch = true
}

# Security Group
resource "aws_security_group" "app" {
  name        = "java-maven-sg"
  description = "Security group for Java Maven Template"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# EC2 Instance
resource "aws_instance" "app" {
  ami                    = var.ami_id
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]
  key_name               = var.key_name

  tags = {
    Name = "jotp"
  }
}
```

**Reference**: [Terraform AWS Provider](https://registry.terraform.io/providers/hashicorp/aws/latest/docs)

---

## Azure Provider (azurerm)

### Configuration

```hcl
terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.0"
    }
  }
}

provider "azurerm" {
  features {}

  subscription_id = var.subscription_id
  client_id       = var.client_id
  client_secret   = var.client_secret
  tenant_id       = var.tenant_id
}
```

### Common Resources

```hcl
# Resource Group
resource "azurerm_resource_group" "main" {
  name     = "java-maven-rg"
  location = var.location
}

# Virtual Network
resource "azurerm_virtual_network" "main" {
  name                = "java-maven-vnet"
  address_space       = ["10.0.0.0/16"]
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
}

# Subnet
resource "azurerm_subnet" "internal" {
  name                 = "internal"
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.0.1.0/24"]
}

# Network Security Group
resource "azurerm_network_security_group" "app" {
  name                = "java-maven-nsg"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name

  security_rule {
    name                       = "SSH"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
}

# Virtual Machine
resource "azurerm_linux_virtual_machine" "app" {
  name                = "java-maven-vm"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  size                = var.vm_size
  admin_username      = var.admin_username

  network_interface_ids = [
    azurerm_network_interface.app.id,
  ]

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Premium_LRS"
  }

  source_image_id = var.image_id
}
```

**Reference**: [Terraform AzureRM Provider](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs)

---

## Google Cloud Provider

### Configuration

```hcl
terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}
```

### Common Resources

```hcl
# VPC Network
resource "google_compute_network" "main" {
  name                    = "java-maven-vpc"
  auto_create_subnetworks = false
}

# Subnet
resource "google_compute_subnetwork" "main" {
  name          = "java-maven-subnet"
  ip_cidr_range = "10.0.0.0/24"
  region        = var.region
  network       = google_compute_network.main.id
}

# Firewall
resource "google_compute_firewall" "ssh" {
  name    = "java-maven-ssh"
  network = google_compute_network.main.name

  allow {
    protocol = "tcp"
    ports    = ["22", "8080"]
  }

  source_ranges = ["0.0.0.0/0"]
}

# Compute Instance
resource "google_compute_instance" "app" {
  name         = "jotp"
  machine_type = var.machine_type
  zone         = var.zone

  boot_disk {
    initialize_params {
      image = var.image_name
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.main.id
    access_config {}
  }
}
```

**Reference**: [Terraform Google Provider](https://registry.terraform.io/providers/hashicorp/google/latest/docs)

---

## OCI Provider

### Configuration

```hcl
terraform {
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 5.0"
    }
  }
}

provider "oci" {
  tenancy_ocid     = var.tenancy_ocid
  user_ocid        = var.user_ocid
  fingerprint      = var.fingerprint
  private_key_path = var.private_key_path
  region           = var.region
}
```

### Common Resources

```hcl
# VCN
resource "oci_core_vcn" "main" {
  compartment_id = var.compartment_ocid
  display_name   = "java-maven-vcn"
  cidr_block     = "10.0.0.0/16"
}

# Subnet
resource "oci_core_subnet" "public" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.main.id
  display_name   = "public-subnet"
  cidr_block     = "10.0.1.0/24"
}

# Compute Instance
resource "oci_core_instance" "app" {
  compartment_id      = var.compartment_ocid
  availability_domain = data.oci_identity_availability_domain.ad.name
  display_name        = "jotp"
  shape               = var.shape

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

**Reference**: [Terraform OCI Provider](https://registry.terraform.io/providers/oracle/oci/latest/docs)

---

## IBM Cloud Provider

### Configuration

```hcl
terraform {
  required_providers {
    ibm = {
      source  = "IBM-Cloud/ibm"
      version = "~> 1.0"
    }
  }
}

provider "ibm" {
  region          = var.region
  ibmcloud_api_key = var.ibmcloud_api_key
}
```

### Common Resources

```hcl
# VPC
resource "ibm_is_vpc" "main" {
  name = "java-maven-vpc"
}

# Subnet
resource "ibm_is_subnet" "main" {
  name                     = "java-maven-subnet"
  vpc                      = ibm_is_vpc.main.id
  zone                     = "${var.region}-1"
  total_ipv4_address_count = 256
}

# Instance
resource "ibm_is_instance" "app" {
  name    = "jotp"
  image   = var.image_id
  profile = var.profile
  vpc     = ibm_is_vpc.main.id
  zone    = "${var.region}-1"

  primary_network_interface {
    subnet = ibm_is_subnet.main.id
  }
}
```

**Reference**: [Terraform IBM Provider](https://registry.terraform.io/providers/IBM-Cloud/ibm/latest/docs)

---

## OpenShift/Kubernetes Provider

### Configuration

```hcl
terraform {
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
  cluster_ca_certificate = base64decode(var.cluster_ca_cert)
}
```

### Common Resources

```hcl
# Namespace
resource "kubernetes_namespace" "app" {
  metadata {
    name = var.namespace
  }
}

# Deployment
resource "kubernetes_deployment" "app" {
  metadata {
    name      = var.app_name
    namespace = kubernetes_namespace.app.metadata[0].name
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
            container_port = 8080
          }
        }
      }
    }
  }
}
```

**Reference**: [Terraform Kubernetes Provider](https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs)

## Related Resources

- [Terraform Registry](https://registry.terraform.io/)
- [Terraform Documentation](https://developer.hashicorp.com/terraform/docs)
- [Deploy How-to Guides](../how-to/index.md)
