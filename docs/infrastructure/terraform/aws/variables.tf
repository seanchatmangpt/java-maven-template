# Java Maven Template - AWS Variables

variable "aws_region" {
  type        = string
  default     = "us-east-1"
  description = "AWS region"
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

variable "vpc_cidr" {
  type        = string
  default     = "10.0.0.0/16"
  description = "VPC CIDR block"
}

variable "ami_id" {
  type        = string
  description = "AMI ID (from Packer build)"
}

variable "instance_type" {
  type        = string
  default     = "t3.medium"
  description = "EC2 instance type"
}

variable "key_name" {
  type        = string
  description = "SSH key pair name"
}

variable "app_port" {
  type        = number
  default     = 8080
  description = "Application port"
}

variable "root_volume_size" {
  type        = number
  default     = 20
  description = "Root volume size in GB"
}

variable "ssh_cidr_blocks" {
  type        = list(string)
  default     = ["0.0.0.0/0"]
  description = "CIDR blocks allowed for SSH access"
}
