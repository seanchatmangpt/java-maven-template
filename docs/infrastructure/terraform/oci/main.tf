# Java Maven Template - OCI Terraform Configuration
# Requires: Terraform >= 1.6.0, OCI API credentials

terraform {
  required_version = ">= 1.6.0"

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

# Data sources
data "oci_identity_availability_domain" "ad" {
  compartment_id = var.tenancy_ocid
  ad_number      = 1
}

# VCN
resource "oci_core_vcn" "main" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.app_name}-vcn"
  cidr_block     = var.vcn_cidr

  freeform_tags = {
    Project     = "java-maven-template"
    Environment = var.environment
  }
}

# Internet Gateway
resource "oci_core_internet_gateway" "main" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${var.app_name}-igw"

  freeform_tags = {
    Project = "java-maven-template"
  }
}

# Route Table
resource "oci_core_route_table" "public" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${var.app_name}-public-rt"

  route_rules {
    destination       = "0.0.0.0/0"
    network_entity_id = oci_core_internet_gateway.main.id
  }
}

# Subnet
resource "oci_core_subnet" "public" {
  compartment_id      = var.compartment_ocid
  vcn_id              = oci_core_vcn.main.id
  cidr_block          = var.subnet_cidr
  display_name        = "${var.app_name}-public-subnet"
  route_table_id      = oci_core_route_table.public.id
  security_list_ids   = [oci_core_security_list.app.id]
  dhcp_options_id     = oci_core_vcn.main.default_dhcp_options_id
}

# Security List
resource "oci_core_security_list" "app" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${var.app_name}-security-list"

  egress_security_rules {
    protocol    = "all"
    destination = "0.0.0.0/0"
  }

  ingress_security_rules {
    protocol = "6"
    source   = "0.0.0.0/0"

    tcp_options {
      min = 22
      max = 22
    }
  }

  ingress_security_rules {
    protocol = "6"
    source   = "0.0.0.0/0"

    tcp_options {
      min = var.app_port
      max = var.app_port
    }
  }
}

# Compute Instance
resource "oci_core_instance" "app" {
  compartment_id      = var.compartment_ocid
  availability_domain = data.oci_identity_availability_domain.ad.name
  display_name        = var.app_name
  shape               = var.shape

  shape_config {
    ocpus         = var.ocpus
    memory_in_gbs = var.memory_in_gbs
  }

  source_details {
    source_type = "image"
    source_id   = var.image_ocid
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.public.id
    assign_public_ip = true
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
  }

  freeform_tags = {
    Project     = "java-maven-template"
    Environment = var.environment
  }
}
