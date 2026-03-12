# IBM Cloud Getting Started Tutorial

This tutorial guides you through deploying your Java Maven Template application to IBM Cloud using Terraform and IBM Cloud Schematics.

**Time required**: 45-60 minutes

**Prerequisites**:
- IBM Cloud account
- Terraform >= 1.6.0
- IBM Cloud CLI

## Learning Objectives

By completing this tutorial, you will:

1. Set up IBM Cloud CLI and configure authentication
2. Configure IBM Cloud Terraform provider
3. Deploy VPC infrastructure with Terraform
4. Verify your application is running correctly
5. Clean up all resources

## Step 1: IBM Cloud Account Setup

### Create an IBM Cloud Account

1. Navigate to [IBM Cloud](https://cloud.ibm.com/registration)
2. Click "Create a free account"
3. Complete the signup process (free tier available with $200 credit)

### Install IBM Cloud CLI

```bash
# macOS
brew install ibm-cloud-cli

# Linux
curl -fsSL https://clis.cloud.ibm.com/install/linux | sh

# Verify installation
ibmcloud --version
```

### Install Required Plugins

```bash
ibmcloud plugin install vpc-infrastructure
ibmcloud plugin install schematics
ibmcloud plugin install container-service
```

### Authenticate with IBM Cloud

```bash
# Login
ibmcloud login

# Target your region and resource group
ibmcloud target -r us-south -g default
```

### Create API Key

```bash
# Create API key for Terraform
ibmcloud iam api-key-create terraform-key -d "Terraform API Key"

# Note the API Key value for Terraform
```

Alternatively, create via [IBM Cloud Console](https://cloud.ibm.com/iam/apikeys).

## Step 2: Build Your Application

```bash
# Navigate to project root
cd /path/to/jotp

# Build the fat JAR
./mvnw package -Dshade

# Verify the JAR exists
ls -la target/*.jar
```

## Step 3: Configure Terraform for IBM Cloud

### Set Environment Variables

```bash
export IAAS_CLASSIC_USERNAME="<your-ibm-cloud-username>"
export IAAS_CLASSIC_API_KEY="<your-classic-api-key>"
export IBMCLOUD_API_KEY="<your-api-key>"
```

### Initialize Terraform

```bash
cd docs/infrastructure/terraform/ibm
terraform init
```

### Create terraform.tfvars

```hcl
region           = "us-south"
resource_group   = "default"
image_name       = "ibm-ubuntu-22-04-2-minimal-amd64-1"
profile          = "bx2-2x8"
ssh_public_key   = "<your-ssh-public-key>"
vpc_name         = "java-maven-vpc"
subnet_name      = "java-maven-subnet"
instance_name    = "jotp"
```

### Review Deployment Plan

```bash
terraform plan
```

Review the resources that will be created:
- 1 VPC
- 1 Subnet
- 1 Public Gateway
- 1 Security Group
- 1 SSH Key
- 1 VSI (Virtual Server Instance)
- 1 Floating IP

### Apply Configuration

```bash
terraform apply
```

Type `yes` when prompted.

## Step 4: Deploy Application

### Upload JAR to Instance

```bash
# Get instance IP
INSTANCE_IP=$(terraform output -raw floating_ip)

# Copy JAR to instance
scp -i ~/.ssh/id_rsa target/jotp-*.jar root@$INSTANCE_IP:/opt/app/

# SSH into instance
ssh -i ~/.ssh/id_rsa root@$INSTANCE_IP
```

### Install Java and Run Application

```bash
# On the instance
apt-get update
apt-get install -y openjdk-21-jre-headless

# Create systemd service
cat > /etc/systemd/system/java-maven-app.service << 'EOF'
[Unit]
Description=Java Maven Template Application
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/app
ExecStart=/usr/bin/java -jar /opt/app/jotp-*.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Enable and start
systemctl daemon-reload
systemctl enable java-maven-app
systemctl start java-maven-app
```

## Step 5: Verify Deployment

### Get Instance IP

```bash
terraform output floating_ip
```

### Check Application Health

```bash
curl http://$(terraform output -raw floating_ip):8080/health
```

### View in IBM Cloud Console

1. Navigate to [VPC Infrastructure](https://cloud.ibm.com/vpc-ext/overview)
2. Click "Virtual server instances"
3. Find your instance named "jotp"

## Step 6: Clean Up Resources

### Destroy Terraform Infrastructure

```bash
terraform destroy
```

Type `yes` when prompted.

### Delete API Key (Optional)

```bash
ibmcloud iam api-key-delete terraform-key
```

## Troubleshooting

### Common Issues

| Error | Solution |
|-------|----------|
| `401 Unauthorized` | Verify API key is valid and has correct permissions |
| `QuotaExceeded` | Request quota increase or use smaller instance profile |
| `ImageNotFound` | Verify image name is correct for the region |
| `KeyAlreadyExists` | Use different SSH key name |

### Useful Commands

```bash
# List VPCs
ibmcloud is vpcs

# List instances
ibmcloud is instances

# List images
ibmcloud is images

# View instance details
ibmcloud is instance <instance-id>
```

## IBM Cloud Free Tier

IBM Cloud free tier includes:
- 256MB Cloud Foundry runtime
- 25GB Object Storage
- 5GB Content Delivery Network
- IBM Watson services

Note: VPC requires pay-as-you-go account.

## Next Steps

- [Deploy to IBM Cloud How-to Guide](../how-to/deploy-to-ibm-cloud.md) - Production deployment strategies
- [Configure CI/CD](../how-to/configure-ci-cd.md) - Automate deployments

## Additional Resources

- [IBM Cloud Documentation](https://cloud.ibm.com/docs)
- [Terraform IBM Provider](https://registry.terraform.io/providers/IBM-Cloud/ibm/latest/docs)
- [IBM Cloud Schematics](https://cloud.ibm.com/docs/schematics?topic=schematics-getting-started)
- [IBM Cloud Free Tier](https://www.ibm.com/cloud/free)
