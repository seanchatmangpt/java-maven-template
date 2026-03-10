# Java Maven Template - OCI Variables

variable "tenancy_ocid" {
  type        = string
  description = "OCI tenancy OCID"
}

variable "user_ocid" {
  type        = string
  description = "OCI user OCID"
}

variable "fingerprint" {
  type        = string
  description = "API key fingerprint"
}

variable "private_key_path" {
  type        = string
  default     = "~/.oci/oci_api_key.pem"
  description = "Path to API private key"
}

variable "region" {
  type        = string
  default     = "us-phoenix-1"
  description = "OCI region"
}

variable "compartment_ocid" {
  type        = string
  description = "Compartment OCID"
}

variable "app_name" {
  type        = string
  default     = "java-maven-template"
  description = "Application name"
}

variable "environment" {
  type        = string
  default     = "production"
  description = "Environment name"
}

variable "vcn_cidr" {
  type        = string
  default     = "10.0.0.0/16"
  description = "VCN CIDR block"
}

variable "subnet_cidr" {
  type        = string
  default     = "10.0.1.0/24"
  description = "Subnet CIDR block"
}

variable "image_ocid" {
  type        = string
  description = "Custom image OCID (from Packer build)"
}

variable "shape" {
  type        = string
  default     = "VM.Standard.E4.Flex"
  description = "Instance shape"
}

variable "ocpus" {
  type        = number
  default     = 2
  description = "Number of OCPUs"
}

variable "memory_in_gbs" {
  type        = number
  default     = 16
  description = "Memory in GB"
}

variable "app_port" {
  type        = number
  default     = 8080
  description = "Application port"
}

variable "ssh_public_key" {
  type        = string
  description = "SSH public key"
}
