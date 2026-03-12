# GCP Getting Started Tutorial

This tutorial guides you through deploying your Java Maven Template application to Google Cloud Platform (GCP) using Packer and Terraform.

**Time required**: 40-55 minutes

**Prerequisites**:
- Google Cloud account
- Packer >= 1.9.0
- Terraform >= 1.6.0

## Learning Objectives

By completing this tutorial, you will:

1. Set up Google Cloud SDK and configure authentication
2. Build a custom VM image with Packer containing your application
3. Deploy GCP infrastructure with Terraform
4. Verify your application is running correctly
5. Clean up all resources

## Step 1: Google Cloud Account Setup

### Create a Google Cloud Account

1. Navigate to [Google Cloud Console](https://console.cloud.google.com/)
2. Click "Get started for free" for $300 free credit
3. Complete the signup process

### Install Google Cloud SDK

```bash
# macOS
brew install google-cloud-sdk

# Linux
curl https://sdk.cloud.google.com | bash
exec -l $SHELL

# Verify installation
gcloud --version
```

### Initialize and Authenticate

```bash
# Initialize gcloud
gcloud init

# Authenticate application default credentials
gcloud auth application-default login
```

### Create a Project

```bash
# Create new project
gcloud projects create jotp --name="Java Maven Template"

# Set project as default
gcloud config set project jotp

# Enable Compute Engine API
gcloud services enable compute.googleapis.com
```

### Enable Required APIs

```bash
gcloud services enable compute.googleapis.com
gcloud services enable cloudresourcemanager.googleapis.com
gcloud services enable iam.googleapis.com
```

## Step 2: Build Your Application

```bash
# Navigate to project root
cd /path/to/jotp

# Build the fat JAR
./mvnw package -Dshade

# Verify the JAR exists
ls -la target/*.jar
```

## Step 3: Build VM Image with Packer

### Initialize Packer

```bash
cd docs/infrastructure/packer/gcp
packer init .
```

### Set Required Variables

Create `variables.pkrvars.hcl`:

```hcl
project_id   = "jotp"
region       = "us-central1"
zone         = "us-central1-a"
source_image = "ubuntu-2204-jammy-v20240101"
```

### Validate Configuration

```bash
packer validate -var-file=variables.pkrvars.hcl java-maven-image.pkr.hcl
```

### Build the Image

```bash
packer build -var-file=variables.pkrvars.hcl java-maven-image.pkr.hcl
```

Expected output:
```
googlecompute: output will be in this color.
==> googlecompute: Checking image does not exist...
==> googlecompute: Creating temporary SSH keypair...
==> googlecompute: Launching instance...
==> googlecompute: Waiting for instance to become ready...
==> googlecompute: Provisioning with shell script...
==> googlecompute: Stopping instance...
==> googlecompute: Creating image...
==> googlecompute: Image: jotp-xxxxx
Build 'googlecompute' finished.
```

**Note the image name** for the next step.

## Step 4: Deploy with Terraform

### Initialize Terraform

```bash
cd ../../terraform/gcp
terraform init
```

### Create terraform.tfvars

```hcl
project_id   = "jotp"
region       = "us-central1"
zone         = "us-central1-a"
image_name   = "jotp-xxxxx"
machine_type = "e2-medium"
```

### Review Deployment Plan

```bash
terraform plan
```

Review the resources that will be created:
- 1 VPC Network
- 1 Subnet
- 1 Firewall rule
- 1 Static IP
- 1 Compute Instance

### Apply Configuration

```bash
terraform apply
```

Type `yes` when prompted.

## Step 5: Verify Deployment

### Get Instance IP

```bash
terraform output instance_ip
```

### Check Application Health

```bash
# SSH into instance
gcloud compute ssh jotp --zone=us-central1-a

# Or using IP
ssh -i ~/.ssh/google_compute_engine user@$(terraform output -raw instance_ip)

# Check if application is running
curl http://localhost:8080/health
```

### View in Google Cloud Console

1. Navigate to [Compute Engine](https://console.cloud.google.com/compute)
2. Click "VM instances"
3. Find your instance named "jotp"

## Step 6: Clean Up Resources

### Destroy Terraform Infrastructure

```bash
terraform destroy
```

Type `yes` when prompted.

### Delete Custom Image

```bash
gcloud compute images delete jotp-xxxxx
```

### Delete Project (Optional)

```bash
gcloud projects delete jotp
```

## Troubleshooting

### Common Issues

| Error | Solution |
|-------|----------|
| `SERVICE_DISABLED` | Enable Compute Engine API |
| `QUOTA_EXCEEDED` | Request quota increase in Cloud Console |
| `IMAGE_NOT_FOUND` | Verify source image family and project |
| `PERMISSION_DENIED` | Check IAM roles (Compute Admin needed) |

### Useful Commands

```bash
# Check current project
gcloud config get-value project

# List instances
gcloud compute instances list

# List images
gcloud compute images list --filter="name:java-maven"

# View instance logs
gcloud compute instances get-serial-port-output jotp --zone=us-central1-a
```

## Next Steps

- [Deploy to GCP How-to Guide](../how-to/deploy-to-gcp.md) - Production deployment strategies
- [Configure CI/CD](../how-to/configure-ci-cd.md) - Automate deployments
- [Google Cloud Terraform Guide](https://cloud.google.com/docs/terraform) - Official GCP Terraform docs

## Additional Resources

- [GCP Documentation](https://cloud.google.com/docs)
- [Terraform Google Provider](https://registry.terraform.io/providers/hashicorp/google/latest/docs)
- [Packer Google Compute Builder](https://developer.hashicorp.com/packer/plugins/builders/googlecompute)
- [Google Cloud Free Tier](https://cloud.google.com/free)
- [Terraform GCP Tutorial](https://developer.hashicorp.com/terraform/tutorials/gcp-get-started)
