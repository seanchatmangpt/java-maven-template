# Simulate AWS Locally

This guide shows you how to simulate AWS services locally using LocalStack for testing your Terraform configurations and Packer builds without cloud costs.

## Prerequisites

- Docker installed
- Docker Compose installed
- Terraform >= 1.6.0

## What is LocalStack?

[LocalStack](https://www.localstack.cloud/) is a fully functional local AWS cloud stack that emulates 100+ AWS services locally, including:

- EC2, Lambda, ECS, EKS
- S3, DynamoDB, SQS, SNS
- API Gateway, CloudFormation
- IAM, KMS, Secrets Manager
- And many more

## Quick Start

### 1. Start LocalStack

```bash
cd docs/infrastructure/simulation/localstack
docker-compose up -d
```

### 2. Verify LocalStack is Running

```bash
# Check health
curl http://localhost:4566/_localstack/health

# List services
curl http://localhost:4566/_localstack/health | jq '.services | keys'
```

### 3. Configure AWS CLI for LocalStack

```bash
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
```

Create AWS profile:

```bash
aws configure --profile localstack
# AWS Access Key ID: test
# AWS Secret Access Key: test
# Default region: us-east-1
# Default output format: json
```

### 4. Test AWS Commands

```bash
# Create S3 bucket
aws --endpoint-url=http://localhost:4566 s3 mb s3://test-bucket

# List buckets
aws --endpoint-url=http://localhost:4566 s3 ls

# Create EC2 key pair
aws --endpoint-url=http://localhost:4566 ec2 create-key-pair --key-name test-key
```

## Using Terraform with LocalStack

### Configure Provider

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
    ec2            = "http://localhost:4566"
    iam            = "http://localhost:4566"
    dynamodb       = "http://localhost:4566"
    lambda         = "http://localhost:4566"
    apigateway     = "http://localhost:4566"
    sqs            = "http://localhost:4566"
    sns            = "http://localhost:4566"
    secretsmanager = "http://localhost:4566"
  }
}
```

### Example Terraform Configuration

```hcl
# Create S3 bucket
resource "aws_s3_bucket" "app" {
  bucket = "java-maven-app-bucket"
}

# Create DynamoDB table
resource "aws_dynamodb_table" "app" {
  name         = "java-maven-table"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }
}

# Create SQS queue
resource "aws_sqs_queue" "app" {
  name = "java-maven-queue"
}
```

### Run Terraform

```bash
# Initialize
terraform init

# Plan
terraform plan

# Apply
terraform apply
```

## Using Packer with LocalStack

### Configure Packer for LocalStack

```hcl
source "amazon-ebs" "java-maven" {
  access_key      = "test"
  secret_key      = "test"
  region          = "us-east-1"
  skip_validate_api_ssl_tls = true
  skip_metadata_api_check   = true

  # LocalStack doesn't fully support EC2 image building
  # Use mock mode for testing
  ami_name = "test-ami"
}
```

**Note**: LocalStack has limited EC2 support. For full AMI building tests, consider using AWS or a mock setup.

## Docker Compose Configuration

The `docker-compose.yml` file:

```yaml
version: '3.8'

services:
  localstack:
    image: localstack/localstack:latest
    container_name: localstack
    ports:
      - "4566:4566"           # LocalStack Gateway
      - "4510-4559:4510-4559" # External services
    environment:
      - SERVICES=s3,ec2,iam,dynamodb,lambda,apigateway,sqs,sns,secretsmanager,kms,cloudformation
      - DEBUG=1
      - PERSISTENCE=/tmp/localstack/data
      - AWS_DEFAULT_REGION=us-east-1
    volumes:
      - "./volume:/var/lib/localstack"
      - "/var/run/docker.sock:/var/run/docker.sock"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:4566/_localstack/health"]
      interval: 10s
      timeout: 5s
      retries: 5
```

## Testing Scenarios

### Test S3 File Upload

```bash
# Create bucket
aws --endpoint-url=http://localhost:4566 s3 mb s3://app-bucket

# Upload file
aws --endpoint-url=http://localhost:4566 s3 cp target/app.jar s3://app-bucket/

# Verify upload
aws --endpoint-url=http://localhost:4566 s3 ls s3://app-bucket/
```

### Test DynamoDB

```bash
# Create table
aws --endpoint-url=http://localhost:4566 dynamodb create-table \
  --table-name TestTable \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST

# Put item
aws --endpoint-url=http://localhost:4566 dynamodb put-item \
  --table-name TestTable \
  --item '{"id":{"S":"test"},"data":{"S":"hello"}}'

# Get item
aws --endpoint-url=http://localhost:4566 dynamodb get-item \
  --table-name TestTable \
  --key '{"id":{"S":"test"}}'
```

### Test Lambda

```bash
# Create function
aws --endpoint-url=http://localhost:4566 lambda create-function \
  --function-name test-function \
  --runtime python3.11 \
  --handler index.handler \
  --zip-file fileb://function.zip \
  --role arn:aws:iam::000000000000:role/lambda-role

# Invoke function
aws --endpoint-url=http://localhost:4566 lambda invoke \
  --function-name test-function output.json
```

## CI/CD Integration

Use LocalStack in CI pipelines:

```yaml
# GitHub Actions
services:
  localstack:
    image: localstack/localstack:latest
    ports:
      - 4566:4566
    env:
      SERVICES: s3,dynamodb,sqs
```

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `Connection refused` | Ensure LocalStack container is running |
| `Service not available` | Check SERVICES environment variable |
| `Slow responses` | Increase Docker resources |

## Clean Up

```bash
docker-compose down -v
```

## Next Steps

- [Deploy to AWS](deploy-to-aws.md) - Real AWS deployment
- [Configure CI/CD](configure-ci-cd.md) - Use LocalStack in CI

## Related Resources

- [LocalStack Documentation](https://docs.localstack.cloud/)
- [LocalStack GitHub](https://github.com/localstack/localstack)
- [AWS CloudFormation Docs](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/Welcome.html)
