# CLI Commands Reference

This document provides a comprehensive reference for command-line tools used in multi-cloud deployments.

## Packer Commands

### Core Commands

| Command | Description |
|---------|-------------|
| `packer init` | Initialize Packer configuration and install plugins |
| `packer validate` | Validate configuration syntax |
| `packer build` | Build machine images |
| `packer console` | Interactive console for variable testing |
| `packer fmt` | Format HCL files |
| `packer inspect` | Inspect Packer configuration |

### Init

```bash
# Initialize with plugin installation
packer init .

# Initialize specific file
packer init config.pkr.hcl

# Upgrade plugins
packer init -upgrade .
```

### Validate

```bash
# Validate configuration
packer validate .

# Validate with variables
packer validate -var-file=variables.pkrvars.hcl config.pkr.hcl

# Validate with only syntax check
packer validate -syntax-only config.pkr.hcl
```

### Build

```bash
# Build all sources
packer build .

# Build specific file
packer build config.pkr.hcl

# Build with variables
packer build -var "image_name=custom" config.pkr.hcl

# Build with variable file
packer build -var-file=vars.pkrvars.hcl config.pkr.hcl

# Force build (overwrite existing)
packer build -force config.pkr.hcl

# Debug mode
packer build -debug config.pkr.hcl

# Machine-readable output
packer build -machine-readable config.pkr.hcl
```

### Format

```bash
# Check formatting
packer fmt -check .

# Format files
packer fmt .

# Format with diff
packer fmt -diff .
```

---

## Terraform Commands

### Core Commands

| Command | Description |
|---------|-------------|
| `terraform init` | Initialize working directory |
| `terraform plan` | Create execution plan |
| `terraform apply` | Apply changes |
| `terraform destroy` | Destroy infrastructure |
| `terraform validate` | Validate configuration |
| `terraform fmt` | Format configuration files |

### Workspace Commands

| Command | Description |
|---------|-------------|
| `terraform workspace list` | List workspaces |
| `terraform workspace select` | Select workspace |
| `terraform workspace new` | Create new workspace |
| `terraform workspace delete` | Delete workspace |

### State Commands

| Command | Description |
|---------|-------------|
| `terraform state list` | List resources |
| `terraform state show` | Show resource details |
| `terraform state mv` | Move resource in state |
| `terraform state rm` | Remove from state |
| `terraform import` | Import existing resource |
| `terraform taint` | Mark resource for recreation |

### Init

```bash
# Initialize directory
terraform init

# Initialize with backend reconfigure
terraform init -reconfigure

# Initialize with plugin cache
terraform init -plugin-dir=/path/to/plugins

# Upgrade providers
terraform init -upgrade
```

### Plan

```bash
# Create plan
terraform plan

# Plan with variables
terraform plan -var="region=us-east-1"

# Plan with variable file
terraform plan -var-file=prod.tfvars

# Save plan to file
terraform plan -out=tfplan

# Plan for destroy
terraform plan -destroy
```

### Apply

```bash
# Apply configuration
terraform apply

# Apply with auto-approve
terraform apply -auto-approve

# Apply saved plan
terraform apply tfplan

# Apply with variables
terraform apply -var-file=prod.tfvars

# Apply with target
terraform apply -target=aws_instance.app
```

### Destroy

```bash
# Destroy all resources
terraform destroy

# Destroy with auto-approve
terraform destroy -auto-approve

# Destroy specific resource
terraform destroy -target=aws_instance.app
```

### Output

```bash
# Show all outputs
terraform output

# Show specific output
terraform output public_ip

# Get raw value (no quotes)
terraform output -raw public_ip

# JSON format
terraform output -json
```

---

## AWS CLI Commands

### Authentication

```bash
# Configure credentials
aws configure

# Configure named profile
aws configure --profile production

# Verify credentials
aws sts get-caller-identity
```

### EC2 Commands

```bash
# List instances
aws ec2 describe-instances

# List AMIs
aws ec2 describe-images --owners self

# Create key pair
aws ec2 create-key-pair --key-name my-key

# Describe instance types
aws ec2 describe-instance-types --instance-types t3.medium
```

### S3 Commands

```bash
# List buckets
aws s3 ls

# Create bucket
aws s3 mb s3://bucket-name

# Upload file
aws s3 cp file.txt s3://bucket-name/

# Sync directory
aws s3 sync ./local s3://bucket-name/

# Download file
aws s3 cp s3://bucket-name/file.txt ./
```

### Secrets Manager

```bash
# Create secret
aws secretsmanager create-secret \
  --name my-secret \
  --secret-string "password123"

# Get secret
aws secretsmanager get-secret-value \
  --secret-id my-secret
```

---

## Azure CLI Commands

### Authentication

```bash
# Login
az login

# Login with service principal
az login --service-principal -u <app-id> -p <password> --tenant <tenant-id>

# Set subscription
az account set --subscription <subscription-id>
```

### Resource Groups

```bash
# List resource groups
az group list

# Create resource group
az group create --name my-rg --location eastus

# Delete resource group
az group delete --name my-rg
```

### VM Commands

```bash
# List VMs
az vm list

# List VM images
az vm image list --publisher Canonical

# Create VM
az vm create \
  --name my-vm \
  --resource-group my-rg \
  --image Ubuntu2204 \
  --admin-username azureuser
```

### Key Vault

```bash
# Create key vault
az keyvault create --name my-kv --resource-group my-rg

# Set secret
az keyvault secret set --vault-name my-kv --name my-secret --value "password123"

# Get secret
az keyvault secret show --vault-name my-kv --name my-secret
```

---

## Google Cloud CLI Commands

### Authentication

```bash
# Login
gcloud auth login

# Application default credentials
gcloud auth application-default login

# Set project
gcloud config set project my-project
```

### Compute Commands

```bash
# List instances
gcloud compute instances list

# List images
gcloud compute images list

# SSH to instance
gcloud compute ssh instance-name --zone=us-central1-a
```

### Container Commands

```bash
# Get credentials for GKE
gcloud container clusters get-credentials cluster-name --region=us-central1
```

---

## OCI CLI Commands

### Authentication

```bash
# Setup config
oci setup config

# Verify
oci iam user get --user-id ocid1.user.oc1..xxxxx
```

### Compute Commands

```bash
# List instances
oci compute instance list --compartment-id ocid1.compartment.oc1..xxxxx

# List images
oci compute image list --compartment-id ocid1.compartment.oc1..xxxxx
```

---

## OpenShift CLI (oc) Commands

### Authentication

```bash
# Login
oc login --token=<token> --server=https://api.cluster:6443

# Who am I
oc whoami
```

### Project Commands

```bash
# List projects
oc get projects

# Create project
oc new-project my-project

# Switch project
oc project my-project
```

### Application Commands

```bash
# Deploy app
oc new-app --name my-app --docker-image=my-image:latest

# List pods
oc get pods

# Get logs
oc logs deployment/my-app

# Expose service
oc expose svc/my-app

# Get route
oc get route my-app
```

---

## Environment Variables

### AWS

| Variable | Description |
|----------|-------------|
| `AWS_ACCESS_KEY_ID` | AWS access key |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key |
| `AWS_DEFAULT_REGION` | Default region |
| `AWS_PROFILE` | Profile name |

### Azure

| Variable | Description |
|----------|-------------|
| `ARM_CLIENT_ID` | Service principal client ID |
| `ARM_CLIENT_SECRET` | Service principal secret |
| `ARM_SUBSCRIPTION_ID` | Subscription ID |
| `ARM_TENANT_ID` | Tenant ID |

### GCP

| Variable | Description |
|----------|-------------|
| `GOOGLE_CREDENTIALS` | Service account JSON |
| `GOOGLE_PROJECT` | Project ID |
| `GOOGLE_REGION` | Default region |

### Terraform

| Variable | Description |
|----------|-------------|
| `TF_VAR_name` | Set variable value |
| `TF_LOG` | Enable logging |
| `TF_LOG_PATH` | Log file path |
| `TF_INPUT` | Disable interactive input |
| `TF_CLI_ARGS` | Additional CLI arguments |

## Related Resources

- [Terraform CLI Documentation](https://developer.hashicorp.com/terraform/cli)
- [Packer CLI Documentation](https://developer.hashicorp.com/packer/docs/commands)
- [AWS CLI Reference](https://docs.aws.amazon.com/cli/)
- [Azure CLI Reference](https://learn.microsoft.com/cli/azure/)
