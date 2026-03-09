# Manage Secrets for Cloud Deployments

This guide shows you how to securely manage secrets and credentials for your multi-cloud deployments.

## Prerequisites

- Cloud provider accounts
- Understanding of security best practices

## Security Principles

1. **Never commit secrets** to version control
2. **Use environment variables** for configuration
3. **Rotate credentials** regularly
4. **Use least privilege** for service accounts
5. **Audit access** to secrets

## AWS Secrets Management

### AWS Secrets Manager

```bash
# Create secret
aws secretsmanager create-secret \
  --name java-maven-app/db-password \
  --secret-string "your-secure-password"

# Retrieve secret
aws secretsmanager get-secret-value \
  --secret-id java-maven-app/db-password \
  --query SecretString --output text
```

### Terraform Integration

```hcl
data "aws_secretsmanager_secret_version" "db_password" {
  secret_id = "java-maven-app/db-password"
}

resource "aws_db_instance" "app" {
  # ...
  password = data.aws_secretsmanager_secret_version.db_password.secret_string
}
```

### AWS Parameter Store

```bash
# Store parameter
aws ssm put-parameter \
  --name "/java-maven-app/db-password" \
  --value "your-secure-password" \
  --type SecureString

# Retrieve parameter
aws ssm get-parameter \
  --name "/java-maven-app/db-password" \
  --with-decryption \
  --query Parameter.Value --output text
```

## Azure Key Vault

### Create Key Vault

```bash
# Create Key Vault
az keyvault create \
  --name java-maven-kv \
  --resource-group java-maven-rg \
  --location eastus

# Store secret
az keyvault secret set \
  --vault-name java-maven-kv \
  --name db-password \
  --value "your-secure-password"

# Retrieve secret
az keyvault secret show \
  --vault-name java-maven-kv \
  --name db-password \
  --query value --output tsv
```

### Terraform Integration

```hcl
data "azurerm_key_vault_secret" "db_password" {
  name         = "db-password"
  key_vault_id = azurerm_key_vault.kv.id
}

resource "azurerm_mssql_database" "app" {
  # ...
}
```

## GCP Secret Manager

### Create Secret

```bash
# Create secret
echo -n "your-secure-password" | gcloud secrets create db-password --data-file=-

# Access secret
gcloud secrets versions access latest --secret="db-password"
```

### Terraform Integration

```hcl
data "google_secret_manager_secret_version" "db_password" {
  secret  = "db-password"
  version = "latest"
}

resource "google_sql_database_instance" "app" {
  # ...
}
```

## OCI Vault

### Create Vault

```bash
# Create vault
oci vault create \
  --compartment-id $COMPARTMENT_ID \
  --display-name java-maven-vault

# Create secret
oci vault secret create-base64 \
  --vault-id $VAULT_ID \
  --secret-name db-password \
  --secret-content-content $(echo -n "password" | base64)
```

## HashiCorp Vault

### Setup Vault

```bash
# Start dev server (for testing only)
vault server -dev

# Set environment
export VAULT_ADDR='http://127.0.0.1:8200'
export VAULT_TOKEN='dev-only-token'

# Store secret
vault kv put secret/java-maven-app db_password="your-password"

# Retrieve secret
vault kv get -field=db_password secret/java-maven-app
```

### Terraform Integration

```hcl
provider "vault" {
  address = "http://127.0.0.1:8200"
}

data "vault_generic_secret" "db_creds" {
  path = "secret/java-maven-app"
}

resource "aws_db_instance" "app" {
  password = data.vault_generic_secret.db_creds.data["db_password"]
}
```

## Terraform Variable Files

### Using .tfvars Files (Not Committed)

Create `secrets.tfvars`:

```hcl
db_password    = "your-secure-password"
api_key        = "your-api-key"
ssh_private_key = "-----BEGIN RSA PRIVATE KEY-----..."
```

Add to `.gitignore`:

```
*.tfvars
!example.tfvars
```

Apply:

```bash
terraform apply -var-file=secrets.tfvars
```

### Using Environment Variables

```bash
# Set environment variables
export TF_VAR_db_password="your-secure-password"
export TF_VAR_api_key="your-api-key"

# Apply
terraform apply
```

## CI/CD Secrets Management

### GitHub Actions

```yaml
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Deploy
        env:
          AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          DB_PASSWORD: ${{ secrets.DB_PASSWORD }}
        run: terraform apply -auto-approve
```

### Using OIDC (Recommended)

GitHub OIDC with AWS:

```yaml
jobs:
  deploy:
    runs-on: ubuntu-latest
    permissions:
      id-token: write
      contents: read
    steps:
      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/GitHubActionsRole
          aws-region: us-east-1
```

## Secret Rotation

### Automated Rotation (AWS)

```bash
# Enable rotation
aws secretsmanager rotate-secret \
  --secret-id java-maven-app/db-password \
  --rotation-lambda-arn arn:aws:lambda:us-east-1:123456789012:function:rotate-secret \
  --rotation-rules AutomaticallyAfterDays=30
```

### Manual Rotation Schedule

| Secret Type | Rotation Frequency |
|-------------|-------------------|
| Database passwords | Every 30-90 days |
| API keys | Every 90 days |
| SSH keys | Every 180 days |
| Service account keys | Every 90 days |

## Best Practices

### 1. Use Separate Credentials Per Environment

```
prod/db-password
staging/db-password
dev/db-password
```

### 2. Audit Secret Access

```bash
# AWS CloudTrail
aws cloudtrail lookup-events \
  --lookup-attributes AttributeKey=ResourceType,AttributeValue=AWS::SecretsManager::Secret

# Azure Activity Log
az monitor activity-log list \
  --resource-group java-maven-rg \
  --caller servicePrincipal
```

### 3. Encrypt Secrets at Rest

All cloud providers encrypt secrets by default. For additional security:

```hcl
# Use customer-managed keys
resource "aws_kms_key" "secrets" {
  description = "KMS key for secrets encryption"
}

resource "aws_secretsmanager_secret" "app" {
  name = "java-maven-app"
  kms_key_id = aws_kms_key.secrets.key_id
}
```

### 4. Limit Secret Access

```hcl
# IAM policy for secret access
resource "aws_iam_policy" "secrets_access" {
  name = "secrets-access-policy"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue"
        ]
        Resource = aws_secretsmanager_secret.app.arn
        Condition = {
          StringEquals = {
            "aws:PrincipalTag/Environment" = "production"
          }
        }
      }
    ]
  })
}
```

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `AccessDenied` | Check IAM permissions |
| `SecretNotFound` | Verify secret name and region |
| `InvalidParameter` | Check secret format |

## Next Steps

- [Configure CI/CD](configure-ci-cd.md) - Integrate secrets in pipelines
- [Security Best Practices](../explanation/security-best-practices.md) - Learn more about security

## Related Resources

- [AWS Secrets Manager](https://docs.aws.amazon.com/secretsmanager/)
- [Azure Key Vault](https://learn.microsoft.com/azure/key-vault/)
- [GCP Secret Manager](https://cloud.google.com/secret-manager)
- [HashiCorp Vault](https://www.vaultproject.io/)
