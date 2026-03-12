# AWS Getting Started Tutorial

This tutorial guides you through deploying your Java Maven Template application to Amazon Web Services (AWS) using Packer and Terraform.

**Time required**: 45-60 minutes

**Prerequisites**:
- AWS account (free tier eligible)
- Packer >= 1.9.0
- Terraform >= 1.6.0

## Learning Objectives

By completing this tutorial, you will:

1. Set up AWS CLI and configure credentials
2. Build a custom AMI with Packer containing your application
3. Deploy EC2 infrastructure with Terraform
4. Verify your application is running correctly
5. Clean up all resources

## Step 1: AWS Account Setup

### Create an AWS Account

1. Navigate to [AWS Console](https://console.aws.amazon.com/)
2. Click "Create an AWS account"
3. Follow the signup process (free tier is available for 12 months)

### Install AWS CLI

```bash
# macOS
brew install awscli

# Linux
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install

# Verify installation
aws --version
```

### Configure AWS Credentials

```bash
aws configure
# AWS Access Key ID: <your-access-key>
# AWS Secret Access Key: <your-secret-key>
# Default region name: us-east-1
# Default output format: json
```

To obtain credentials:
1. Go to [IAM Console](https://console.aws.amazon.com/iam/)
2. Navigate to Users → Create user
3. Attach `AmazonEC2FullAccess` and `AmazonVPCFullAccess` policies
4. Create access keys under Security credentials tab

## Step 2: Build Your Application

```bash
# Navigate to project root
cd /path/to/jotp

# Build the fat JAR
./mvnw package -Dshade

# Verify the JAR exists
ls -la target/*.jar
```

## Step 3: Build AMI with Packer

### Initialize Packer

```bash
cd docs/infrastructure/packer/aws
packer init .
```

### Validate Configuration

```bash
packer validate java-maven-ami.pkr.hcl
```

### Build the AMI

```bash
packer build java-maven-ami.pkr.hcl
```

Expected output:
```
amazon-ebs: output will be in this color.
==> amazon-ebs: Prevalidating any provided VPC resources
==> amazon-ebs: Creating temporary keypair: packer_xxxxxxxx
==> amazon-ebs: Launching instance...
==> amazon-ebs: Waiting for instance to become ready...
==> amazon-ebs: Provisioning with shell script...
==> amazon-ebs: Stopping the source instance...
==> amazon-ebs: Creating AMI...
==> amazon-ebs: AMI: ami-xxxxxxxxxxxxxxxxx
==> amazon-ebs: Waiting for AMI to become ready...
Build 'amazon-ebs' finished.
```

**Note the AMI ID** (e.g., `ami-xxxxxxxxxxxxxxxxx`) for the next step.

## Step 4: Deploy with Terraform

### Initialize Terraform

```bash
cd ../../terraform/aws
terraform init
```

### Create terraform.tfvars

```hcl
aws_region  = "us-east-1"
ami_id      = "ami-xxxxxxxxxxxxxxxxx"  # From Packer build
instance_type = "t3.medium"
key_name    = "your-ssh-key-pair"
```

### Review Deployment Plan

```bash
terraform plan
```

Review the resources that will be created:
- 1 VPC
- 1 Subnet
- 1 Security Group
- 1 EC2 Instance
- 1 Elastic IP

### Apply Configuration

```bash
terraform apply
```

Type `yes` when prompted.

## Step 5: Verify Deployment

### Get Instance IP

```bash
terraform output public_ip
```

### Check Application Health

```bash
# SSH into instance (if SSH is enabled)
ssh -i ~/.ssh/your-key.pem ec2-user@$(terraform output -raw public_ip)

# Check if application is running
curl http://$(terraform output -raw public_ip):8080/health
```

### View in AWS Console

1. Navigate to [EC2 Console](https://console.aws.amazon.com/ec2/)
2. Click "Instances" in the sidebar
3. Find your instance named "jotp"

## Step 6: Clean Up Resources

### Destroy Terraform Infrastructure

```bash
terraform destroy
```

Type `yes` when prompted.

### Deregister AMI (Optional)

```bash
# List your AMIs
aws ec2 describe-images --owners self --query 'Images[*].ImageId'

# Deregister the AMI
aws ec2 deregister-image --image-id ami-xxxxxxxxxxxxxxxxx

# Delete associated snapshots
aws ec2 describe-snapshots --owner-ids self --query 'Snapshots[*].SnapshotId'
```

## Troubleshooting

### Common Issues

| Error | Solution |
|-------|----------|
| `UnauthorizedOperation` | Verify IAM permissions include EC2 access |
| `InvalidAMIID.NotFound` | Wait for AMI to become available, or use correct region |
| `KeyPair.DoesNotExist` | Create key pair in EC2 console or specify correct name |
| `VcpuLimitExceeded` | Request limit increase or use smaller instance type |

### Useful Commands

```bash
# Check AWS credentials
aws sts get-caller-identity

# List instances
aws ec2 describe-instances --query 'Reservations[*].Instances[*].InstanceId'

# View instance logs
aws ec2 get-console-output --instance-id i-xxxxxxxx
```

## Next Steps

- [Deploy to AWS How-to Guide](../how-to/deploy-to-aws.md) - Production deployment strategies
- [Simulate AWS Locally](../how-to/simulate-aws-locally.md) - Test without cloud costs
- [Configure CI/CD](../how-to/configure-ci-cd.md) - Automate deployments

## Additional Resources

- [AWS Documentation](https://docs.aws.amazon.com/)
- [Terraform AWS Provider](https://registry.terraform.io/providers/hashicorp/aws/latest/docs)
- [Packer AWS Builder](https://developer.hashicorp.com/packer/plugins/builders/amazon)
- [AWS Free Tier](https://aws.amazon.com/free/)
