# Java Maven Template - Azure VM Image Builder
# Requires: Packer >= 1.9.0, Azure service principal credentials

packer {
  required_plugins {
    azure = {
      version = ">= 1.0.0"
      source  = "github.com/hashicorp/azure"
    }
  }
}

variable "client_id" {
  type        = string
  default     = env("ARM_CLIENT_ID")
  description = "Azure service principal client ID"
  sensitive   = true
}

variable "client_secret" {
  type        = string
  default     = env("ARM_CLIENT_SECRET")
  description = "Azure service principal secret"
  sensitive   = true
}

variable "subscription_id" {
  type        = string
  default     = env("ARM_SUBSCRIPTION_ID")
  description = "Azure subscription ID"
  sensitive   = true
}

variable "tenant_id" {
  type        = string
  default     = env("ARM_TENANT_ID")
  description = "Azure tenant ID"
  sensitive   = true
}

variable "resource_group" {
  type        = string
  default     = "packer-images"
  description = "Resource group for the image"
}

variable "location" {
  type        = string
  default     = "eastus"
  description = "Azure region"
}

variable "image_name" {
  type        = string
  default     = "java-maven-template"
  description = "Name of the managed image"
}

variable "vm_size" {
  type        = string
  default     = "Standard_B2s"
  description = "VM size for building"
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

source "azure-arm" "java-maven" {
  client_id       = var.client_id
  client_secret   = var.client_secret
  subscription_id = var.subscription_id
  tenant_id       = var.tenant_id

  managed_image_resource_group_name = var.resource_group
  managed_image_name                = "${var.image_name}-${formatdate("YYYYMMDD-HHmmss", timestamp())}"

  build_resource_group_name = "${var.resource_group}-build"

  location = var.location
  vm_size  = var.vm_size

  # Ubuntu 22.04 LTS source image
  image_publisher = "Canonical"
  image_offer     = "0001-com-ubuntu-server-jammy"
  image_sku       = "22_04-lts"
  image_version   = "latest"

  os_type       = "Linux"
  ssh_username  = "packer"

  # Tags
  azure_tags = {
    Name        = "java-maven-template"
    Project     = "java-maven-template"
    JavaVersion = var.java_version
    AppVersion  = var.app_version
    BuiltBy     = "packer"
  }
}

build {
  sources = ["source.azure-arm.java-maven"]

  # Update system
  provisioner "shell" {
    execute_command = "chmod +x {{ .Path }}; {{ .Vars }} sudo -E sh '{{ .Path }}'"
    inline = [
      "apt-get update",
      "apt-get upgrade -y",
    ]
  }

  # Install Java
  provisioner "shell" {
    execute_command = "chmod +x {{ .Path }}; {{ .Vars }} sudo -E sh '{{ .Path }}'"
    inline = [
      "apt-get install -y openjdk-${var.java_version}-jre-headless",
      "java -version",
    ]
  }

  # Create application directory
  provisioner "shell" {
    execute_command = "chmod +x {{ .Path }}; {{ .Vars }} sudo -E sh '{{ .Path }}'"
    inline = [
      "mkdir -p /opt/java-maven-app",
      "chmod 755 /opt/java-maven-app",
    ]
  }

  # Copy application JAR
  provisioner "file" {
    source      = "../../../target/java-maven-template-${var.app_version}.jar"
    destination = "/tmp/app.jar"
    direction   = "upload"
  }

  # Move JAR and create service
  provisioner "shell" {
    execute_command = "chmod +x {{ .Path }}; {{ .Vars }} sudo -E sh '{{ .Path }}'"
    inline = [
      "mv /tmp/app.jar /opt/java-maven-app/app.jar",
      "chmod 644 /opt/java-maven-app/app.jar",
    ]
  }

  # Create systemd service
  provisioner "shell" {
    execute_command = "chmod +x {{ .Path }}; {{ .Vars }} sudo -E sh '{{ .Path }}'"
    inline = [
      "tee /etc/systemd/system/java-maven-app.service > /dev/null <<'EOF'",
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
      "systemctl daemon-reload",
      "systemctl enable java-maven-app",
    ]
  }

  # Cleanup
  provisioner "shell" {
    execute_command = "chmod +x {{ .Path }}; {{ .Vars }} sudo -E sh '{{ .Path }}'"
    inline = [
      "apt-get clean",
      "rm -rf /var/lib/apt/lists/*",
    ]
  }

  # Generalize for Azure
  provisioner "shell" {
    execute_command = "chmod +x {{ .Path }}; {{ .Vars }} sudo -E sh '{{ .Path }}'"
    inline = [
      "rm -f /var/log/waagent.log",
      "waagent -force -deprovision+user",
    ]
  }

  # Post-processor: Create manifest
  post-processor "manifest" {
    output     = "manifest.json"
    strip_path = true
    custom_data = {
      java_version = var.java_version
      app_version  = var.app_version
      location     = var.location
    }
  }
}
