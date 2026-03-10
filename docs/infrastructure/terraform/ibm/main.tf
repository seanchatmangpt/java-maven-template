# Java Maven Template - IBM Cloud Terraform Configuration
# Requires: Terraform >= 1.6.0, IBM Cloud API key

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    ibm = {
      source  = "IBM-Cloud/ibm"
      version = "~> 1.0"
    }
  }

  # Backend configuration (uncomment for remote state)
  # backend "pg" {
  #   conn_str = "postgres://user:pass@host/database"
  #   schema_name = "java-maven-template"
  # }
}

provider "ibm" {
  region          = var.region
  ibmcloud_api_key = var.ibmcloud_api_key
}

# Data sources
data "ibm_resource_group" "group" {
  name = var.resource_group
}

# VPC
resource "ibm_is_vpc" "main" {
  name           = var.vpc_name
  resource_group = data.ibm_resource_group.group.id

  tags = [
    "project:java-maven-template",
    "environment:${var.environment}"
  ]
}

# Subnet
resource "ibm_is_subnet" "main" {
  name                     = var.subnet_name
  vpc                      = ibm_is_vpc.main.id
  zone                     = "${var.region}-1"
  total_ipv4_address_count = 256

  tags = [
    "project:java-maven-template"
  ]
}

# Public Gateway
resource "ibm_is_public_gateway" "main" {
  name   = "${var.vpc_name}-gateway"
  vpc    = ibm_is_vpc.main.id
  zone   = "${var.region}-1"

  tags = [
    "project:java-maven-template"
  ]
}

# Subnet Gateway Attachment
resource "ibm_is_subnet_public_gateway_attachment" "main" {
  subnet         = ibm_is_subnet.main.id
  public_gateway = ibm_is_public_gateway.main.id
}

# Security Group
resource "ibm_is_security_group" "app" {
  name           = "${var.app_name}-sg"
  vpc            = ibm_is_vpc.main.id
  resource_group = data.ibm_resource_group.group.id

  tags = [
    "project:java-maven-template"
  ]
}

# Security Group Rules
resource "ibm_is_security_group_rule" "ssh" {
  group     = ibm_is_security_group.app.id
  direction = "inbound"
  remote    = "0.0.0.0/0"

  tcp {
    port_min = 22
    port_max = 22
  }
}

resource "ibm_is_security_group_rule" "app" {
  group     = ibm_is_security_group.app.id
  direction = "inbound"
  remote    = "0.0.0.0/0"

  tcp {
    port_min = var.app_port
    port_max = var.app_port
  }
}

# SSH Key
resource "ibm_is_ssh_key" "app" {
  name       = "${var.app_name}-key"
  public_key = var.ssh_public_key

  tags = [
    "project:java-maven-template"
  ]
}

# Virtual Server Instance
resource "ibm_is_instance" "app" {
  name    = var.instance_name
  image   = var.image_id
  profile = var.profile
  vpc     = ibm_is_vpc.main.id
  zone    = "${var.region}-1"
  keys    = [ibm_is_ssh_key.app.id]

  primary_network_interface {
    name            = "eth0"
    subnet          = ibm_is_subnet.main.id
    security_groups = [ibm_is_security_group.app.id]
  }

  tags = [
    "project:java-maven-template",
    "environment:${var.environment}"
  ]
}

# Floating IP
resource "ibm_is_floating_ip" "app" {
  name   = "${var.app_name}-fip"
  target = ibm_is_instance.app.primary_network_interface[0].id

  tags = [
    "project:java-maven-template"
  ]
}
