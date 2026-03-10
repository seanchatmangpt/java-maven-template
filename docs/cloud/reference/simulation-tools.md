# Simulation Tools Reference

This document provides detailed reference for local simulation tools used in multi-cloud development.

## LocalStack

LocalStack provides a fully functional local AWS cloud stack.

### Features

- Emulates 100+ AWS services
- REST API compatible with AWS SDK
- Docker-based deployment
- Supports CloudFormation and Terraform

### Docker Compose Configuration

```yaml
version: '3.8'

services:
  localstack:
    image: localstack/localstack:latest
    container_name: localstack
    ports:
      - "4566:4566"           # Gateway
      - "4510-4559:4510-4559" # External services
    environment:
      - SERVICES=s3,ec2,iam,dynamodb,lambda,apigateway,sqs,sns,secretsmanager,kms,cloudformation
      - DEBUG=1
      - PERSISTENCE=/tmp/localstack/data
      - AWS_DEFAULT_REGION=us-east-1
    volumes:
      - "./volume:/var/lib/localstack"
      - "/var/run/docker.sock:/var/run/docker.sock"
```

### Supported Services

| Service | Status | Notes |
|---------|--------|-------|
| S3 | Full | Complete implementation |
| DynamoDB | Full | Including streams |
| SQS | Full | Standard and FIFO queues |
| SNS | Full | Including subscriptions |
| Lambda | Full | Including layers |
| API Gateway | Full | REST and HTTP APIs |
| EC2 | Partial | Limited functionality |
| IAM | Partial | Basic users and roles |
| KMS | Partial | Basic key operations |
| Secrets Manager | Full | Complete implementation |
| CloudFormation | Full | Template processing |

### Configuration

```bash
# Start LocalStack
docker-compose up -d

# Health check
curl http://localhost:4566/_localstack/health

# Configure AWS CLI
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1

# Use with AWS CLI
aws --endpoint-url=http://localhost:4566 s3 ls
```

### Terraform Integration

```hcl
provider "aws" {
  access_key                  = "test"
  secret_key                  = "test"
  region                      = "us-east-1"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  endpoints {
    s3             = "http://localhost:4566"
    dynamodb       = "http://localhost:4566"
    sqs            = "http://localhost:4566"
    sns            = "http://localhost:4566"
    lambda         = "http://localhost:4566"
    apigateway     = "http://localhost:4566"
    secretsmanager = "http://localhost:4566"
    kms            = "http://localhost:4566"
    iam            = "http://localhost:4566"
  }
}
```

**Reference**: [LocalStack Documentation](https://docs.localstack.cloud/)

---

## Azurite

Azurite provides local Azure Storage emulation.

### Features

- Blob Storage emulation
- Queue Storage emulation
- Table Storage emulation
- Compatible with Azure SDK

### Docker Compose Configuration

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
    command: "azurite --blobHost 0.0.0.0 --queueHost 0.0.0.0 --tableHost 0.0.0.0 --location /data"
```

### Connection String

```
DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://localhost:10000/devstoreaccount1;QueueEndpoint=http://localhost:10001/devstoreaccount1;TableEndpoint=http://localhost:10002/devstoreaccount1;
```

### Supported Operations

| Service | Status | Notes |
|---------|--------|-------|
| Blob Storage | Full | Block, append, page blobs |
| Queue Storage | Full | Message queues |
| Table Storage | Full | NoSQL tables |

### Configuration

```bash
# Start Azurite
docker-compose up -d

# Set connection string
export AZURE_STORAGE_CONNECTION_STRING="DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://localhost:10000/devstoreaccount1;"

# Use with Azure CLI
az storage container create --name test-container
az storage blob upload --container-name test-container --name file.txt --file local.txt
```

**Reference**: [Azurite Documentation](https://learn.microsoft.com/azure/storage/common/storage-use-azurite)

---

## MinIO

MinIO provides S3-compatible object storage.

### Docker Compose Configuration

```yaml
version: '3.8'

services:
  minio:
    image: minio/minio:latest
    container_name: minio
    ports:
      - "9000:9000"   # API
      - "9001:9001"   # Console
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    volumes:
      - "./data:/data"
    command: server /data --console-address ":9001"
```

### Configuration

```bash
# Configure AWS CLI for MinIO
aws configure set aws_access_key_id minioadmin --profile minio
aws configure set aws_secret_access_key minioadmin --profile minio
aws configure set region us-east-1 --profile minio

# Use with S3 commands
aws --endpoint-url http://localhost:9000 s3 mb s3://test-bucket --profile minio
```

---

## CodeReady Containers (CRC)

Local OpenShift cluster for development.

### Installation

```bash
# macOS
brew install crc

# Setup
crc setup

# Start cluster
crc start

# Configure oc
eval $(crc oc-env)

# Login
oc login -u developer -p developer https://api.crc.testing:6443
```

### Configuration

```bash
# Check status
crc status

# Stop cluster
crc stop

# Delete cluster
crc delete
```

### Resource Requirements

| Resource | Minimum |
|----------|---------|
| Memory | 9 GB |
| CPUs | 4 |
| Disk | 35 GB |

**Reference**: [CodeReady Containers](https://developers.redhat.com/products/codeready-containers)

---

## Feature Comparison

| Feature | LocalStack | Azurite | MinIO |
|---------|------------|---------|-------|
| Provider | AWS | Azure | S3-compatible |
| Storage | Multiple | Blob/Queue/Table | Object only |
| Compute | Limited | None | None |
| Database | DynamoDB | Table | None |
| Messaging | SQS/SNS | Queue | None |
| SDK Support | Full | Full | S3 compatible |

## CI/CD Integration

### GitHub Actions

```yaml
services:
  localstack:
    image: localstack/localstack:latest
    ports:
      - 4566:4566
    env:
      SERVICES: s3,dynamodb,sqs
```

### GitLab CI

```yaml
services:
  - name: localstack/localstack:latest
    alias: localstack
```

## Related Resources

- [Simulate AWS Locally](../how-to/simulate-aws-locally.md)
- [Simulate Azure Locally](../how-to/simulate-azure-locally.md)
- [LocalStack](https://www.localstack.cloud/)
- [Azurite](https://learn.microsoft.com/azure/storage/common/storage-use-azurite)
