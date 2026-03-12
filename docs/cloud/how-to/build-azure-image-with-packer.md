# Build Azure VM Image with Packer

This guide shows you how to create a VM image for Microsoft Azure containing your Java Maven Template application using HashiCorp Packer.

## Prerequisites

- Packer >= 1.9.0 installed
- Azure subscription
- Azure service principal credentials

## Steps

### 1. Create Service Principal

```bash
az ad sp create-for-rbac --role="Contributor" --scopes="/subscriptions/<subscription-id>"
```

Note the output for environment variables.

### 2. Set Environment Variables

```bash
export ARM_CLIENT_ID="<app-id>"
export ARM_CLIENT_SECRET="<password>"
export ARM_SUBSCRIPTION_ID="<subscription-id>"
export ARM_TENANT_ID="<tenant>"
```

### 3. Create Resource Group

```bash
az group create --name packer-images --location eastus
```

### 4. Navigate to Packer Directory

```bash
cd docs/infrastructure/packer/azure
```

### 5. Initialize Packer

```bash
packer init .
```

### 6. Configure Variables

Create `variables.pkrvars.hcl`:

```hcl
client_id       = "<app-id>"
client_secret   = "<password>"
subscription_id = "<subscription-id>"
tenant_id       = "<tenant>"
resource_group  = "packer-images"
location        = "eastus"
image_name      = "jotp"
```

### 7. Validate Configuration

```bash
packer validate -var-file=variables.pkrvars.hcl java-maven-image.pkr.hcl
```

### 8. Build Image

```bash
packer build -var-file=variables.pkrvars.hcl java-maven-image.pkr.hcl
```

### 9. Note the Image ID

Output will include:
```
==> azure-arm: Image ID: /subscriptions/xxx/resourceGroups/packer-images/providers/Microsoft.Compute/images/jotp-xxxxx
```

## Customization

### Add Custom Provisioners

Edit `java-maven-image.pkr.hcl`:

```hcl
build {
  sources = ["source.azure-arm.java-maven"]

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
    ]
  }
}
```

### Use Different Base Image

```hcl
source "azure-arm" "java-maven" {
  # Ubuntu 22.04
  image_publisher = "Canonical"
  image_offer     = "0001-com-ubuntu-server-jammy"
  image_sku       = "22_04-lts"
  image_version   = "latest"

  # Or RHEL 9
  # image_publisher = "RedHat"
  # image_offer     = "RHEL"
  # image_sku       = "9-lvm"
  # image_version   = "latest"
}
```

### Use Managed Image vs Shared Image Gallery

For Shared Image Gallery:

```hcl
source "azure-arm" "java-maven" {
  shared_image_gallery_destination {
    resource_group       = "packer-images"
    gallery_name         = "java_maven_gallery"
    image_name           = "jotp"
    image_version        = "1.0.0"
    replication_regions  = ["eastus", "westus2"]
  }
}
```

## Troubleshooting

| Error | Solution |
|-------|----------|
| `AuthorizationFailed` | Verify service principal has Contributor role |
| `ResourceGroupNotFound` | Create resource group first |
| `StorageAccountNotFound` | Check storage account configuration |

## Next Steps

- [Deploy to Azure](deploy-to-azure.md) - Use your image in Terraform
- [Simulate Azure Locally](simulate-azure-locally.md) - Test with Azurite

## Related Resources

- [Packer Azure Builder](https://developer.hashicorp.com/packer/plugins/builders/azure)
- [Azure VM Images](https://learn.microsoft.com/azure/virtual-machines/images)
