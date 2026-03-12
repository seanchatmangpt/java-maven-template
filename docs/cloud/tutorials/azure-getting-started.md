# Azure Getting Started Tutorial

This tutorial guides you through deploying your Java Maven Template application to Microsoft Azure using Packer and Terraform.

**Time required**: 45-60 minutes

**Prerequisites**:
- Azure subscription
- Packer >= 1.9.0
- Terraform >= 1.6.0

## Learning Objectives

By completing this tutorial, you will:

1. Set up Azure CLI and configure authentication
2. Build a custom VM image with Packer containing your application
3. Deploy Azure infrastructure with Terraform
4. Verify your application is running correctly
5. Clean up all resources

## Step 1: Azure Account Setup

### Create an Azure Account

1. Navigate to [Azure Portal](https://portal.azure.com/)
2. Click "Start free" for a free account with $200 credit
3. Complete the signup process

### Install Azure CLI

```bash
# macOS
brew install azure-cli

# Linux (Ubuntu/Debian)
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

# Verify installation
az --version
```

### Authenticate with Azure

```bash
# Login interactively
az login

# List subscriptions
az account list --output table

# Set default subscription
az account set --subscription "<subscription-id>"
```

### Create Service Principal for Terraform

```bash
# Create service principal
az ad sp create-for-rbac --role="Contributor" --scopes="/subscriptions/<subscription-id>"

# Note the output:
# {
#   "appId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
#   "displayName": "azure-cli-xxxx",
#   "password": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
#   "tenant": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
# }
```

### Set Environment Variables

```bash
export ARM_CLIENT_ID="<app-id>"
export ARM_CLIENT_SECRET="<password>"
export ARM_SUBSCRIPTION_ID="<subscription-id>"
export ARM_TENANT_ID="<tenant>"
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

### Create Resource Group for Packer

```bash
az group create --name packer-images --location eastus
```

### Initialize Packer

```bash
cd docs/infrastructure/packer/azure
packer init .
```

### Set Required Variables

Create `variables.pkrvars.hcl`:

```hcl
client_id       = "<app-id>"
client_secret   = "<password>"
subscription_id = "<subscription-id>"
tenant_id       = "<tenant-id>"
resource_group  = "packer-images"
location        = "eastus"
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
azure-arm: output will be in this color.
==> azure-arm: Running builder ...
==> azure-arm: Getting source image...
==> azure-arm: Creating resource group...
==> azure-arm: -> ResourceGroupName : 'pkr-Resource-Group-xxxxx'
==> azure-arm: -> Location          : 'eastus'
==> azure-arm: Validating deployment template...
==> azure-arm: Creating deployment...
==> azure-arm: Running custom script...
==> azure-arm: Capturing image...
==> azure-arm: Image ID: /subscriptions/xxx/resourceGroups/packer-images/providers/Microsoft.Compute/images/jotp-xxxxx
Build 'azure-arm' finished.
```

**Note the Image ID** for the next step.

## Step 4: Deploy with Terraform

### Initialize Terraform

```bash
cd ../../terraform/azure
terraform init
```

### Create terraform.tfvars

```hcl
location            = "eastus"
resource_group_name = "java-maven-rg"
image_id            = "/subscriptions/xxx/resourceGroups/packer-images/providers/Microsoft.Compute/images/jotp-xxxxx"
vm_size             = "Standard_B2s"
admin_username      = "azureuser"
```

### Review Deployment Plan

```bash
terraform plan
```

Review the resources that will be created:
- 1 Resource Group
- 1 Virtual Network
- 1 Subnet
- 1 Network Security Group
- 1 Public IP
- 1 Network Interface
- 1 Virtual Machine

### Apply Configuration

```bash
terraform apply
```

Type `yes` when prompted.

## Step 5: Verify Deployment

### Get VM Public IP

```bash
terraform output public_ip_address
```

### Check Application Health

```bash
# SSH into VM
ssh azureuser@$(terraform output -raw public_ip_address)

# Check if application is running
curl http://localhost:8080/health
```

### View in Azure Portal

1. Navigate to [Azure Portal](https://portal.azure.com/)
2. Search for "Virtual machines"
3. Find your VM named "jotp"

## Step 6: Clean Up Resources

### Destroy Terraform Infrastructure

```bash
terraform destroy
```

Type `yes` when prompted.

### Delete Resource Groups

```bash
# Delete application resources
az group delete --name java-maven-rg --yes --no-wait

# Delete Packer images
az group delete --name packer-images --yes --no-wait
```

### Delete Service Principal (Optional)

```bash
az ad sp delete --id "<app-id>"
```

## Troubleshooting

### Common Issues

| Error | Solution |
|-------|----------|
| `AuthorizationFailed` | Verify service principal has Contributor role |
| `ResourceGroupNotFound` | Create resource group or check name |
| `ImageNotFound` | Verify image ID and resource group |
| `VmSizeNotAvailable` | Try different VM size for the region |

### Useful Commands

```bash
# Check current subscription
az account show

# List resource groups
az group list --output table

# List VM images
az image list --output table

# View VM run command results
az vm run-command invoke --command-id RunShellScript --scripts "cat /var/log/cloud-init-output.log"
```

## Next Steps

- [Deploy to Azure How-to Guide](../how-to/deploy-to-azure.md) - Production deployment strategies
- [Simulate Azure Locally](../how-to/simulate-azure-locally.md) - Test with Azurite
- [Configure CI/CD](../how-to/configure-ci-cd.md) - Automate deployments

## Additional Resources

- [Azure Documentation](https://learn.microsoft.com/azure/)
- [Terraform AzureRM Provider](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs)
- [Packer Azure Builder](https://developer.hashicorp.com/packer/plugins/builders/azure)
- [Azure Free Account](https://azure.microsoft.com/free/)
- [Azure Terraform Tutorial](https://developer.hashicorp.com/terraform/tutorials/azure-get-started)
