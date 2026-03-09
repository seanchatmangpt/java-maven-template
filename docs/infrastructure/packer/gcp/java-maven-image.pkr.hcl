# Java Maven Template - GCP VM Image Builder
# Requires: Packer >= 1.9.0, GCP credentials (Application Default Credentials)

packer {
  required_plugins {
    googlecompute = {
      version = ">= 1.0.0"
      source  = "github.com/hashicorp/googlecompute"
    }
  }
}

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

variable "image_name" {
  type        = string
  default     = "java-maven-template"
  description = "Name of the image"
}

variable "image_family" {
  type        = string
  default     = "java-maven"
  description = "Image family"
}

variable "machine_type" {
  type        = string
  default     = "e2-medium"
  description = "Machine type for building"
}

variable "source_image_project" {
  type        = string
  default     = "ubuntu-os-cloud"
  description = "Source image project"
}

variable "source_image_family" {
  type        = string
  default     = "ubuntu-2204-lts"
  description = "Source image family"
}

variable "app_version" {
  type        = string
  default     = "1.0.0-SNAPSHOT"
  description = "Application version"
}

variable "java_version" {
  type        = string
  default     = "21"
  description = "Java version to install"
}

variable "network" {
  type        = string
  default     = "default"
  description = "VPC network"
}

variable "subnetwork" {
  type        = string
  default     = "default"
  description = "Subnetwork"
}

source "googlecompute" "java-maven" {
  project_id   = var.project_id
  region       = var.region
  zone         = var.zone

  image_name        = "${var.image_name}-${formatdate("YYYYMMDD-HHmmss", timestamp())}"
  image_family      = var.image_family
  image_description = "Java Maven Template Image with Java ${var.java_version}"

  machine_type = var.machine_type

  source_image_project = [var.source_image_project]
  source_image_family  = var.source_image_family

  network    = var.network
  subnetwork = var.subnetwork

  ssh_username = "packer"

  # Labels
  labels = {
    name        = "java-maven-template"
    project     = "java-maven-template"
    java-version = var.java_version
    app-version  = var.app_version
    built-by     = "packer"
  }
}

build {
  sources = ["source.googlecompute.java-maven"]

  # Update system
  provisioner "shell" {
    inline = [
      "sudo apt-get update",
      "sudo apt-get upgrade -y",
    ]
  }

  # Install Java
  provisioner "shell" {
    inline = [
      "sudo apt-get install -y openjdk-${var.java_version}-jre-headless",
      "java -version",
    ]
  }

  # Create application directory
  provisioner "shell" {
    inline = [
      "sudo mkdir -p /opt/java-maven-app",
      "sudo chmod 755 /opt/java-maven-app",
    ]
  }

  # Copy application JAR
  provisioner "file" {
    source      = "../../../target/java-maven-template-${var.app_version}.jar"
    destination = "/tmp/app.jar"
  }

  # Move JAR to application directory
  provisioner "shell" {
    inline = [
      "sudo mv /tmp/app.jar /opt/java-maven-app/app.jar",
      "sudo chmod 644 /opt/java-maven-app/app.jar",
    ]
  }

  # Create systemd service
  provisioner "shell" {
    inline = [
      "sudo tee /etc/systemd/system/java-maven-app.service > /dev/null <<'EOF'",
      "[Unit]",
      "Description=Java Maven Template Application",
      "After=network.target",
      "",
      "[Service]",
      "Type=simple",
      "User=nobody",
      "WorkingDirectory=/opt/java-maven-app",
      "ExecStart=/usr/bin/java -jar /opt/java-maven-app/app.jar",
      "Restart=on-failure",
      "RestartSec=10",
      "",
      "[Install]",
      "WantedBy=multi-user.target",
      "EOF",
      "sudo systemctl daemon-reload",
      "sudo systemctl enable java-maven-app",
    ]
  }

  # Cleanup
  provisioner "shell" {
    inline = [
      "sudo apt-get clean",
      "sudo rm -rf /var/lib/apt/lists/*",
    ]
  }

  # Post-processor: Create manifest
  post-processor "manifest" {
    output     = "manifest.json"
    strip_path = true
    custom_data = {
      java_version = var.java_version
      app_version  = var.app_version
      region       = var.region
      zone         = var.zone
    }
  }
}
