# Simulate Azure Locally

This guide shows you how to simulate Azure services locally using Azurite for testing your Terraform configurations and Packer builds without cloud costs.

## Prerequisites

- Docker installed
- Node.js (optional, for npm installation)
- Terraform >= 1.6.0

## What is Azurite?

[Azurite](https://learn.microsoft.com/azure/storage/common/storage-use-azurite) is an open-source Azure Storage emulator that provides local emulation for:

- Blob Storage
- Queue Storage
- Table Storage

## Quick Start

### 1. Start Azurite with Docker

```bash
cd docs/infrastructure/simulation/azurite
docker-compose up -d
```

### 2. Verify Azurite is Running

```bash
# Check logs
docker logs azurite

# Should see:
# Azurite Blob service is starting at http://0.0.0.0:10000
# Azurite Queue service is starting at http://0.0.0.0:10001
# Azurite Table service is starting at http://0.0.0.0:10002
```

### 3. Configure Azure CLI for Azurite

Azurite uses a default connection string:

```bash
export AZURE_STORAGE_CONNECTION_STRING="DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://localhost:10000/devstoreaccount1;QueueEndpoint=http://localhost:10001/devstoreaccount1;TableEndpoint=http://localhost:10002/devstoreaccount1;"
```

### 4. Test Azure CLI Commands

```bash
# Create container
az storage container create --name test-container

# List containers
az storage container list

# Upload file
az storage blob upload --container-name test-container --name app.jar --file target/app.jar

# List blobs
az storage blob list --container-name test-container
```

## Using Terraform with Azurite

### Configure Provider

```hcl
provider "azurerm" {
  features {}

  # For full Azure emulation, consider using Azure emulator
  # Azurite only supports storage services
}

# For Azurite, use the azapi or local provider
provider "azurerm" {
  features {}

  skip_provider_registration = true
}
```

### Storage Account Example

```hcl
resource "azurerm_resource_group" "example" {
  name     = "example-rg"
  location = "eastus"
}

resource "azurerm_storage_account" "example" {
  name                     = "examplestorageacc"
  resource_group_name      = azurerm_resource_group.example.name
  location                 = azurerm_resource_group.example.location
  account_tier             = "Standard"
  account_replication_type = "LRS"
}

resource "azurerm_storage_container" "example" {
  name                  = "app-container"
  storage_account_name  = azurerm_storage_account.example.name
  container_access_type = "private"
}
```

**Note**: Azurite only emulates storage services. For full Azure emulation, consider:

- [Azure Stack HCI](https://azure.microsoft.com/products/azure-stack/hci/)
- Using actual Azure with free tier credits

## Docker Compose Configuration

The `docker-compose.yml` file:

```yaml
version: '3.8'

services:
  azurite:
    image: mcr.microsoft.com/azure-storage/azurite
    container_name: azurite
    ports:
      - "10000:10000"  # Blob
      - "10001:10001"  # Queue
      - "10002:10002"  # Table
    environment:
      - AZURITE_ACCOUNTS=devstoreaccount1:Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==
    volumes:
      - "./data:/data"
    command: "azurite --blobHost 0.0.0.0 --queueHost 0.0.0.0 --tableHost 0.0.0.0 --location /data --debug /data/debug.log"
```

## Testing Scenarios

### Test Blob Upload

```bash
# Create container
az storage container create --name app-blobs

# Upload blob
az storage blob upload \
  --container-name app-blobs \
  --name application.jar \
  --file target/jotp-1.0.0-SNAPSHOT.jar

# Download blob
az storage blob download \
  --container-name app-blobs \
  --name application.jar \
  --file /tmp/downloaded.jar

# Verify
diff target/jotp-1.0.0-SNAPSHOT.jar /tmp/downloaded.jar
```

### Test Queue Messages

```bash
# Create queue
az storage queue create --name app-queue

# Insert message
az storage message put --queue-name app-queue --content "Hello from Azurite"

# Peek message
az storage message peek --queue-name app-queue

# Dequeue message
az storage message get --queue-name app-queue
```

### Test Table Storage

```bash
# Create table
az storage table create --name app-table

# Insert entity (using Azure Storage SDK)
# Note: Azure CLI table support is limited
```

## Using with Azure SDKs

### Java Example

```java
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

String connectionString = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://localhost:10000/devstoreaccount1;";

BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
    .connectionString(connectionString)
    .buildClient();

// Create container
blobServiceClient.createBlobContainer("app-container");

// Upload blob
var containerClient = blobServiceClient.getBlobContainerClient("app-container");
var blobClient = containerClient.getBlobClient("app.jar");
blobClient.uploadFromFile("target/app.jar");
```

## Visual Studio Code Integration

Install the Azurite extension for VS Code:

1. Install "Azurite" extension
2. Start Azurite from command palette: "Azurite: Start"
3. View in "Azure" extension panel

## CI/CD Integration

```yaml
# GitHub Actions
services:
  azurite:
    image: mcr.microsoft.com/azure-storage/azurite
    ports:
      - 10000:10000
      - 10001:10001
      - 10002:10002
```

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `Connection refused` | Ensure Azurite container is running |
| `AuthenticationFailed` | Use correct connection string |
| `ContainerNotFound` | Create container before operations |

## Clean Up

```bash
docker-compose down -v
```

## Limitations

Azurite has some limitations:

- Only emulates storage services (Blob, Queue, Table)
- No compute emulation (VM, Functions, etc.)
- Some advanced features not supported
- Performance differs from actual Azure

For full Azure testing, consider:
- Azure free tier ($200 credit for 30 days)
- Azure dev/test subscriptions

## Next Steps

- [Deploy to Azure](deploy-to-azure.md) - Real Azure deployment
- [Configure CI/CD](configure-ci-cd.md) - Use Azurite in CI

## Related Resources

- [Azurite Documentation](https://learn.microsoft.com/azure/storage/common/storage-use-azurite)
- [Azure Storage Emulator](https://learn.microsoft.com/azure/storage/common/storage-use-emulator)
- [Azure SDK for Java](https://learn.microsoft.com/java/api/overview/azure/)
