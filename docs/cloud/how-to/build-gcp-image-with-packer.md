# Build GCP VM Image with Packer

This guide shows you how to create a VM image for Google Cloud Platform containing your Java Maven Template application using HashiCorp Packer.

## Prerequisites

- Packer >= 1.9.0 installed
- GCP project with Compute Engine API enabled
- Google Cloud SDK authenticated

## Steps

### 1. Authenticate with GCP

```bash
gcloud auth application-default login
```

### 2. Enable Compute Engine API

```bash
gcloud services enable compute.googleapis.com
```

### 3. Navigate to Packer Directory

```bash
cd docs/infrastructure/packer/gcp
```

### 4. Initialize Packer

```bash
packer init .
```

### 5. Configure Variables

Create `variables.pkrvars.hcl`:

```hcl
project_id   = "your-project-id"
region       = "us-central1"
zone         = "us-central1-a"
# Ubuntu 22.04 LTS
source_image_project = "ubuntu-os-cloud"
source_image_family  = "ubuntu-2204-lts"
image_name   = "jotp"
machine_type = "e2-medium"
}
```

### 6. Validate Configuration

```bash
packer validate -var-file=variables.pkrvars.hcl java-maven-image.pkr.hcl
```

### 7. Build Image

```bash
packer build -var-file=variables.pkrvars.hcl java-maven-image.pkr.hcl
```

### 8. Note the Image Name

Output will include:
```
==> googlecompute: Image: jotp-xxxxx
```

## Customization

### Add Custom Provisioners

Edit `java-maven-image.pkr.hcl`:

```hcl
build {
  sources = ["source.googlecompute.java-maven"]

  provisioner "shell" {
    inline = [
      "sudo apt-get update",
      "sudo apt-get install -y openjdk-21-jre-headless",
    ]
  }

  provisioner "file" {
    source      = "../../../target/jotp-1.0.0-SNAPSHOT.jar"
    destination = "/tmp/app.jar"
  }

  provisioner "shell" {
    inline = [
      "sudo mkdir -p /opt/app",
      "sudo mv /tmp/app.jar /opt/app/",
      "sudo chmod 644 /opt/app/app.jar",
    ]
  }
}
```

### Use Different Base Image

```hcl
source "googlecompute" "java-maven" {
  # Ubuntu 22.04
  source_image_project = "ubuntu-os-cloud"
  source_image_family  = "ubuntu-2204-lts"

  # Or Debian 12
  # source_image_project = "debian-cloud"
  # source_image_family  = "debian-12"

  # Or CentOS Stream 9
  # source_image_project = "centos-cloud"
  # source_image_family  = "centos-stream-9"
}
```

### Store Image in Custom Family

```hcl
source "googlecompute" "java-maven" {
  image_family        = "java-maven"
  image_name          = "jotp-${formatdate("YYYYMMDD", timestamp())}"
}
```

### Use Startup Script

```hcl
provisioner "shell" {
  inline = [
    "sudo tee /opt/app/startup.sh > /dev/null <<'EOF'",
    "#!/bin/bash",
    "cd /opt/app",
    "/usr/bin/java -jar app.jar",
    "EOF",
    "sudo chmod +x /opt/app/startup.sh",
  ]
}
```

## Troubleshooting

| Error | Solution |
|-------|----------|
| `PERMISSION_DENIED` | Verify Compute Engine API is enabled and IAM roles |
| `QUOTA_EXCEEDED` | Request quota increase in Cloud Console |
| `IMAGE_NOT_FOUND` | Verify source image family and project |

## Next Steps

- [Deploy to GCP](deploy-to-gcp.md) - Use your image in Terraform
- [Configure CI/CD](configure-ci-cd.md) - Automate builds

## Related Resources

- [Packer Google Compute Builder](https://developer.hashicorp.com/packer/plugins/builders/googlecompute)
- [GCP VM Images](https://cloud.google.com/compute/docs/images)
- [GCP Terraform Guide](https://cloud.google.com/docs/terraform)
