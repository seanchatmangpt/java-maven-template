# OCI Getting Started Tutorial

This tutorial guides you through deploying your Java Maven Template application to Oracle Cloud Infrastructure (OCI) using Packer and Terraform.

**Time required**: 50-70 minutes

**Prerequisites**:
- Oracle Cloud account
- Packer >= 1.9.0
- Terraform >= 1.6.0

## Learning Objectives

By completing this tutorial, you will:

1. Set up OCI CLI and configure authentication
2. Build a custom VM image with Packer containing your application
3. Deploy OCI infrastructure with Terraform
4. Verify your application is running correctly
5. Clean up all resources

## Step 1: Oracle Cloud Account Setup

### Create an Oracle Cloud Account

1. Navigate to [Oracle Cloud](https://www.oracle.com/cloud/free/)
2. Click "Start for free" for Always Free tier
3. Complete the signup process (includes $300 credit for 30 days)

### Install OCI CLI

```bash
# macOS
brew install oci-cli

# Linux
bash -c "$(curl -L https://raw.githubusercontent.com/oracle/oci-cli/master/scripts/install/install.sh)"

# Verify installation
oci --version
```

### Configure OCI CLI

```bash
oci setup config
# Enter a location for your config [/Users/you/.oci/config]:
# Enter a user OCID: <your-user-ocid>
# Enter a tenancy OCID: <your-tenancy-ocid>
# Enter a region: us-phoenix-1
# Do you want to generate a new RSA key pair? Y
```

To obtain required OCIDs:
1. **Tenancy OCID**: Console → Administration → Tenancy Details
2. **User OCID**: Console → Identity → Users → [your user]
3. **Fingerprint**: Generated during key pair creation or view in User Details

### Upload API Public Key

1. In OCI Console, navigate to Identity → Users → [your user]
2. Click "API Keys" → "Add API Key"
3. Paste the contents of `~/.oci/oci_api_key_public.pem`
4. Note the fingerprint displayed

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

### Install OCI Packer Plugin

```bash
cd docs/infrastructure/packer/oci
packer init .
```

### Set Required Variables

Create `variables.pkrvars.hcl`:

```hcl
tenancy_ocid     = "ocid1.tenancy.oc1..xxxxx"
user_ocid        = "ocid1.user.oc1..xxxxx"
fingerprint      = "xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx"
private_key_path = "~/.oci/oci_api_key.pem"
region           = "us-phoenix-1"
compartment_ocid = "ocid1.compartment.oc1..xxxxx"
base_image_ocid  = "ocid1.image.oc1.phx.xxxxx"  # Oracle Linux 8
ssh_public_key   = "~/.ssh/id_rsa.pub"
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
oracle-oci: output will be in this color.
==> oracle-oci: Creating temporary instance...
==> oracle-oci: Using ssh communicator to connect...
==> oracle-oci: Waiting for SSH to become available...
==> oracle-oci: Provisioning with shell script...
==> oracle-oci: Stopping instance...
==> oracle-oci: Creating custom image...
==> oracle-oci: Image OCID: ocid1.image.oc1.phx.xxxxx
Build 'oracle-oci' finished.
```

**Note the Image OCID** for the next step.

## Step 4: Deploy with Terraform

### Initialize Terraform

```bash
cd ../../terraform/oci
terraform init
```

### Create terraform.tfvars

```hcl
tenancy_ocid     = "ocid1.tenancy.oc1..xxxxx"
user_ocid        = "ocid1.user.oc1..xxxxx"
fingerprint      = "xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx"
private_key_path = "~/.oci/oci_api_key.pem"
region           = "us-phoenix-1"
compartment_ocid = "ocid1.compartment.oc1..xxxxx"
image_ocid       = "ocid1.image.oc1.phx.xxxxx"
shape            = "VM.Standard.E4.Flex"
```

### Review Deployment Plan

```bash
terraform plan
```

Review the resources that will be created:
- 1 VCN (Virtual Cloud Network)
- 1 Subnet
- 1 Security List
- 1 Internet Gateway
- 1 Compute Instance

### Apply Configuration

```bash
terraform apply
```

Type `yes` when prompted.

## Step 5: Verify Deployment

### Get Instance Public IP

```bash
terraform output public_ip
```

### Check Application Health

```bash
# SSH into instance
ssh -i ~/.ssh/id_rsa opc@$(terraform output -raw public_ip)

# Check if application is running
curl http://localhost:8080/health
```

### View in OCI Console

1. Navigate to [Compute Console](https://cloud.oracle.com/compute/instances)
2. Click "Instances"
3. Find your instance named "jotp"

## Step 6: Clean Up Resources

### Destroy Terraform Infrastructure

```bash
terraform destroy
```

Type `yes` when prompted.

### Delete Custom Image

```bash
oci compute image delete --image-id ocid1.image.oc1.phx.xxxxx
```

## Troubleshooting

### Common Issues

| Error | Solution |
|-------|----------|
| `NotAuthenticated` | Verify API key and fingerprint |
| `CompartmentNotFound` | Check compartment OCID |
| `ImageNotFound` | Verify base image OCID for your region |
| `ServiceLimitExceeded` | Request limit increase or try different shape |

### Useful Commands

```bash
# List compartments
oci iam compartment list

# List available shapes
oci compute shape list --compartment-id <compartment-ocid>

# List images
oci compute image list --compartment-id <compartment-ocid>

# Get instance details
oci compute instance get --instance-id <instance-ocid>
```

## OCI Always Free Resources

OCI Always Free tier includes:
- 2 AMD Compute VMs (1/8 OCPU, 1GB RAM each)
- 4 Arm Ampere A1 cores + 24GB RAM
- 200GB block volume storage
- 10GB object storage

## Next Steps

- [Deploy to OCI How-to Guide](../how-to/deploy-to-oci.md) - Production deployment strategies
- [Configure CI/CD](../how-to/configure-ci-cd.md) - Automate deployments

## Additional Resources

- [OCI Documentation](https://docs.oracle.com/en-us/iaas/)
- [Terraform OCI Provider](https://registry.terraform.io/providers/oracle/oci/latest/docs)
- [OCI Terraform Guide](https://docs.oracle.com/en-us/iaas/Content/dev/terraform/home.htm)
- [OCI Free Tier](https://www.oracle.com/cloud/free/)
- [Packer OCI Builder](https://developer.hashicorp.com/packer/plugins/builders/oracle)
