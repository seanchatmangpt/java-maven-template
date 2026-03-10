# Java Maven Template - OpenShift Variables

variable "openshift_server_url" {
  type        = string
  description = "OpenShift API server URL"
}

variable "openshift_token" {
  type        = string
  description = "OpenShift API token"
  sensitive   = true
}

variable "cluster_ca_certificate" {
  type        = string
  default     = ""
  description = "Base64 encoded cluster CA certificate"
}

variable "namespace" {
  type        = string
  default     = "java-maven-app"
  description = "OpenShift namespace"
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

variable "image" {
  type        = string
  description = "Container image"
}

variable "app_port" {
  type        = number
  default     = 8080
  description = "Application port"
}

variable "replicas" {
  type        = number
  default     = 2
  description = "Number of replicas"
}

variable "min_replicas" {
  type        = number
  default     = 2
  description = "Minimum replicas for HPA"
}

variable "max_replicas" {
  type        = number
  default     = 10
  description = "Maximum replicas for HPA"
}
