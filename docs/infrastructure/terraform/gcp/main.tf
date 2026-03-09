# Java Maven Template - GCP Terraform Configuration
# Requires: Terraform >= 1.6.0, GCP credentials

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }

  # Backend configuration (uncomment for remote state)
  # backend "gcs" {
  #   bucket = "terraform-state-bucket"
  #   prefix = "java-maven-template"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}

# VPC Network
resource "google_compute_network" "main" {
  name                    = "${var.app_name}-vpc"
  auto_create_subnetworks = false

  labels = {
    environment = var.environment
  }
}

# Subnet
resource "google_compute_subnetwork" "main" {
  name          = "${var.app_name}-subnet"
  ip_cidr_range = var.subnet_cidr
  region        = var.region
  network       = google_compute_network.main.id
}

# Firewall Rules
resource "google_compute_firewall" "ssh" {
  name    = "${var.app_name}-ssh"
  network = google_compute_network.main.name

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = ["0.0.0.0/0"]

  target_tags = [var.app_name]
}

resource "google_compute_firewall" "app" {
  name    = "${var.app_name}-firewall"
  network = google_compute_network.main.name

  allow {
    protocol = "tcp"
    ports    = [tostring(var.app_port)]
  }

  source_ranges = ["0.0.0.0/0"]

  target_tags = [var.app_name]
}

# Static IP
resource "google_compute_address" "app" {
  name   = "${var.app_name}-ip"
  region = var.region
}

# Compute Instance
resource "google_compute_instance" "app" {
  name         = var.app_name
  machine_type = var.machine_type
  zone         = var.zone

  tags = [var.app_name]

  boot_disk {
    initialize_params {
      image = var.image_name
      size  = var.disk_size
      type  = "pd-ssd"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.main.id
    access_config {
      nat_ip = google_compute_address.app.address
    }
  }

  metadata = {
    ssh-keys = "${var.ssh_user}:${var.ssh_public_key}"
  }

  labels = {
    environment = var.environment
    app         = var.app_name
  }
}
