# Deploy to Azure

This guide shows you how to deploy your Java Maven Template application to Microsoft Azure using Terraform.

## Prerequisites

- Terraform >= 1.6.0 installed
- Azure subscription
- VM image built with Packer
- Azure service principal credentials

## Steps

### 1. Set Environment Variables

```bash
export ARM_CLIENT_ID="<app-id>"
export ARM_CLIENT_SECRET="<password>"
export ARM_SUBSCRIPTION_ID="<subscription-id>"
export ARM_TENANT_ID="<tenant>"
```

### 2. Navigate to Terraform Directory

```bash
cd docs/infrastructure/terraform/azure
```

### 3. Initialize Terraform

```bash
terraform init
```

### 4. Configure Variables

Create `terraform.tfvars`:

```hcl
location            = "eastus"
resource_group_name = "java-maven-rg"
image_id            = "/subscriptions/xxx/resourceGroups/packer-images/providers/Microsoft.Compute/images/jotp-xxxxx"
vm_size             = "Standard_B2s"
admin_username      = "azureuser"
environment         = "production"
app_name            = "jotp"
}
```

### 5. Review Deployment Plan

```bash
terraform plan
```

Review resources to be created:
- Resource group
- Virtual network and subnet
- Network security group
- Public IP and network interface
- Virtual machine

### 6. Apply Configuration

```bash
terraform apply
```

Type `yes` to confirm.

### 7. Verify Deployment

```bash
# Get VM public IP
terraform output public_ip_address

# SSH into VM
ssh azureuser@$(terraform output -raw public_ip_address)

# Check application
curl http://localhost:8080/health
```

## Production Configuration

### Add Application Gateway

```hcl
resource "azurerm_application_gateway" "app" {
  name                = "${var.app_name}-agw"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location

  sku {
    name     = "Standard_v2"
    tier     = "Standard_v2"
    capacity = 2
  }

  gateway_ip_configuration {
    name      = "app-gateway-ip"
    subnet_id = azurerm_subnet.agw.id
  }

  frontend_port {
    name = "http-port"
    port = 80
  }

  backend_address_pool {
    name         = "backend-pool"
    ip_addresses = [azurerm_network_interface.app.private_ip_address]
  }
}
```

### Add Azure SQL Database

```hcl
resource "azurerm_mssql_server" "app" {
  name                         = "${var.app_name}-sql-server"
  resource_group_name          = azurerm_resource_group.main.name
  location                     = azurerm_resource_group.main.location
  version                      = "12.0"
  administrator_login          = var.db_admin
  administrator_login_password = var.db_password
}

resource "azurerm_mssql_database" "app" {
  name      = "appdb"
  server_id = azurerm_mssql_server.app.id
  sku_name  = "S1"
}
```

### Add Virtual Machine Scale Set

```hcl
resource "azurerm_linux_virtual_machine_scale_set" "app" {
  name                = "${var.app_name}-vmss"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "Standard_B2s"
  instances           = 2

  source_image_id = var.image_id

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Premium_LRS"
  }

  network_interface {
    name    = "nic"
    primary = true

    ip_configuration {
      name      = "internal"
      primary   = true
      subnet_id = azurerm_subnet.app.id
    }
  }
}
```

## Clean Up

```bash
terraform destroy
```

## Troubleshooting

| Error | Solution |
|-------|----------|
| `AuthorizationFailed` | Verify service principal has Contributor role |
| `ImageNotFound` | Check image ID and resource group |
| `VmSizeNotAvailable` | Try different VM size for region |

## Next Steps

- [Simulate Azure Locally](simulate-azure-locally.md) - Test with Azurite
- [Configure CI/CD](configure-ci-cd.md) - Automate deployments

## Related Resources

- [Terraform AzureRM Provider](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs)
- [Azure Architecture Center](https://learn.microsoft.com/azure/architecture/)
- [Terraform Azure Tutorial](https://developer.hashicorp.com/terraform/tutorials/azure-get-started)
