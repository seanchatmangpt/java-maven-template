# Java Maven Template - OCI VM Image Builder
# Requires: Packer >= 1.9.0, OCI API credentials

packer {
  required_plugins {
    oracle = {
      version = ">= 1.0.0"
      source  = "github.com/hashicorp/oracle"
    }
  }
}

variable "tenancy_ocid" {
  type        = string
  description = "OCI tenancy OCID"
  sensitive   = true
}

variable "user_ocid" {
  type        = string
  description = "OCI user OCID"
  sensitive   = true
}

variable "fingerprint" {
  type        = string
  description = "API key fingerprint"
  sensitive   = true
}

variable "private_key_path" {
  type        = string
  default     = "~/.oci/oci_api_key.pem"
  description = "Path to API private key"
  sensitive   = true
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

variable "base_image_ocid" {
  type        = string
  description = "Base image OCID (Oracle Linux 8 or similar)"
}

variable "image_name" {
  type        = string
  default     = "java-maven-template"
  description = "Name of the custom image"
}

variable "shape" {
  type        = string
  default     = "VM.Standard.E4.Flex"
  description = "Instance shape"
}

variable "subnet_ocid" {
  type        = string
  description = "Subnet OCID"
}

variable "availability_domain" {
  type        = string
  default     = ""
  description = "Availability domain (will be auto-selected if empty)"
}

variable "ssh_public_key" {
  type        = string
  default     = ""
  description = "SSH public key"
}

variable "ssh_private_key_file" {
  type        = string
  default     = "~/.ssh/id_rsa"
  description = "SSH private key file"
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

source "oracle-oci" "java-maven" {
  tenancy_ocid     = var.tenancy_ocid
  user_ocid        = var.user_ocid
  fingerprint      = var.fingerprint
  private_key_path = var.private_key_path
  region           = var.region

  base_image_ocid    = var.base_image_ocid
  compartment_ocid   = var.compartment_ocid
  image_name         = "${var.image_name}-${formatdate("YYYYMMDD-HHmmss", timestamp())}"
  shape              = var.shape
  subnet_ocid        = var.subnet_ocid

  availability_domain = var.availability_domain != "" ? var.availability_domain : null

  ssh_username          = "opc"
  ssh_private_key_file  = var.ssh_private_key_file
  ssh_public_key        = var.ssh_public_key != "" ? var.ssh_public_key : null

  # Freeform tags
  freeform_tags = {
    Name        = "java-maven-template"
    Project     = "java-maven-template"
    JavaVersion = var.java_version
    AppVersion  = var.app_version
    BuiltBy     = "packer"
  }
}

build {
  sources = ["source.oracle-oci.java-maven"]

  # Update system
  provisioner "shell" {
    inline = [
      "sudo dnf update -y",
    ]
  }

  # Install Java
  provisioner "shell" {
    inline = [
      "sudo dnf install -y java-${var.java_version}-openjdk",
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
      "sudo dnf clean all",
      "sudo rm -rf /var/cache/dnf",
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
    }
  }
}
