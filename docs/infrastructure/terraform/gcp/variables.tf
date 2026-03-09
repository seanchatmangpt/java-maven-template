# Java Maven Template - GCP Variables

variable "project_id" {
  type        = string
  description = "GCP project ID"
}

variable "region" {
  type        = string
  default     = "us-central1"
  description = "GCP region"
}

variable "zone" {
  type        = string
  default     = "us-central1-a"
  description = "GCP zone"
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

variable "subnet_cidr" {
  type        = string
  default     = "10.0.0.0/24"
  description = "Subnet CIDR"
}

variable "image_name" {
  type        = string
  description = "Image name (from Packer build)"
}

variable "machine_type" {
  type        = string
  default     = "e2-medium"
  description = "Machine type"
}

variable "disk_size" {
  type        = number
  default     = 20
  description = "Boot disk size in GB"
}

variable "app_port" {
  type        = number
  default     = 8080
  description = "Application port"
}

variable "ssh_user" {
  type        = string
  default     = "packer"
  description = "SSH user"
}

variable "ssh_public_key" {
  type        = string
  description = "SSH public key"
}
