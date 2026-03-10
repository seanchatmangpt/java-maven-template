# Java Maven Template - AWS AMI Builder
# Requires: Packer >= 1.9.0, AWS credentials

packer {
  required_plugins {
    amazon = {
      version = ">= 1.0.0"
      source  = "github.com/hashicorp/amazon"
    }
  }
}

variable "aws_region" {
  type        = string
  default     = "us-east-1"
  description = "AWS region for building the AMI"
}

variable "instance_type" {
  type        = string
  default     = "t3.medium"
  description = "EC2 instance type for building"
}

variable "source_ami" {
  type        = string
  default     = "ami-0c7217cdde317cfec"  # Amazon Linux 2023 us-east-1
  description = "Source AMI ID"
}

variable "ssh_username" {
  type        = string
  default     = "ec2-user"
  description = "SSH username for the source AMI"
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

variable "vpc_id" {
  type        = string
  default     = ""
  description = "VPC ID (optional, uses default if empty)"
}

variable "subnet_id" {
  type        = string
  default     = ""
  description = "Subnet ID (optional)"
}

source "amazon-ebs" "java-maven" {
  region        = var.aws_region
  instance_type = var.instance_type
  source_ami    = var.source_ami
  ssh_username  = var.ssh_username
  ami_name      = "java-maven-template-${formatdate("YYYYMMDD-HHmmss", timestamp())}"
  ami_description = "Java Maven Template AMI with Java ${var.java_version}"

  # Optional VPC configuration
  vpc_id    = var.vpc_id != "" ? var.vpc_id : null
  subnet_id = var.subnet_id != "" ? var.subnet_id : null

  # Tags
  tags = {
    Name        = "java-maven-template"
    Project     = "java-maven-template"
    JavaVersion = var.java_version
    AppVersion  = var.app_version
    BuiltBy     = "packer"
  }

  # Run instance metadata options
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }
}

build {
  sources = ["source.amazon-ebs.java-maven"]

  # Wait for instance to be ready
  provisioner "shell" {
    inline = [
      "sudo dnf update -y",
    ]
  }

  # Install Java
  provisioner "shell" {
    inline = [
      "sudo dnf install -y java-${var.java_version}-amazon-corretto",
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
    ]
  }

  # Enable service
  provisioner "shell" {
    inline = [
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
      region       = var.aws_region
    }
  }
}
