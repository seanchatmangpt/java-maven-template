# Java Maven Template - IBM Cloud Variables

variable "ibmcloud_api_key" {
  type        = string
  default     = env("IBMCLOUD_API_KEY")
  description = "IBM Cloud API key"
  sensitive   = true
}

variable "region" {
  type        = string
  default     = "us-south"
  description = "IBM Cloud region"
}

variable "resource_group" {
  type        = string
  default     = "default"
  description = "Resource group name"
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

variable "vpc_name" {
  type        = string
  default     = "java-maven-vpc"
  description = "VPC name"
}

variable "subnet_name" {
  type        = string
  default     = "java-maven-subnet"
  description = "Subnet name"
}

variable "instance_name" {
  type        = string
  default     = "java-maven-template"
  description = "Instance name"
}

variable "image_id" {
  type        = string
  description = "Image ID (e.g., ibm-ubuntu-22-04-2-minimal-amd64-1)"
}

variable "profile" {
  type        = string
  default     = "bx2-2x8"
  description = "Instance profile"
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
