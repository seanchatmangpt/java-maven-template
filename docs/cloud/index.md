# Multi-Cloud Deployment

This section provides comprehensive documentation for deploying Java Maven Template applications to multiple cloud providers using Packer and Terraform.

**Default Provider: Oracle Cloud Infrastructure (OCI) Always Free** — This template defaults to OCI Always Free tier, which provides generous free resources including 4 Arm Ampere A1 cores + 24GB RAM.

## Quick Start (OCI Always Free)

```bash
# 1. Configure cloud provider (defaults to OCI)
source cloud-config.sh

# 2. Copy and configure environment
cp .env.cloud.example .env.cloud
# Edit .env.cloud with your OCI credentials

# 3. Build your application
./mvnw package -Dshade

# 4. Build VM image with Packer
cloud-build-image

# 5. Deploy with Terraform
cloud-deploy
```

See the [OCI Getting Started Tutorial](tutorials/oci-getting-started.md) for detailed setup instructions.

## Documentation Structure

This documentation follows the [Diátaxis framework](https://docs.diataxis.fr/), organizing content into four distinct types:

### [Tutorials](tutorials/index.md) - Learning-Oriented

Step-by-step lessons for beginners. Start here if you're new to a cloud provider.

- **[OCI Getting Started](tutorials/oci-getting-started.md)** — **Recommended** (Always Free tier with 4 ARM cores + 24GB RAM)
- [AWS Getting Started](tutorials/aws-getting-started.md)
- [Azure Getting Started](tutorials/azure-getting-started.md)
- [GCP Getting Started](tutorials/gcp-getting-started.md)
- [IBM Cloud Getting Started](tutorials/ibm-cloud-getting-started.md)
- [OpenShift Getting Started](tutorials/openshift-getting-started.md)

### [How-to Guides](how-to/index.md) - Problem-Oriented

Practical guides for accomplishing specific tasks.

- Building machine images with Packer
- Deploying infrastructure with Terraform
- Local simulation and testing
- CI/CD integration
- Secrets management

### [Reference](reference/index.md) - Information-Oriented

Technical specifications and lookup tables.

- Packer builder configurations
- Terraform provider references
- CLI command reference
- Configuration schemas

### [Explanation](explanation/index.md) - Understanding-Oriented

Conceptual guides for deeper understanding.

- Architecture overview
- Packer vs Terraform comparison
- Multi-cloud strategy
- Security best practices

## Supported Providers

| Provider | Image Builder | IaC Tool | Free Tier | Documentation |
|----------|--------------|----------|-----------|---------------|
| **Oracle Cloud Infrastructure** | Packer oracle-oci | Terraform OCI | **Always Free** (4 ARM cores + 24GB RAM) | [OCI Docs](https://docs.oracle.com/en-us/iaas/) |
| Amazon Web Services | Packer amazon-ebs | Terraform AWS | 12 months | [AWS Docs](https://docs.aws.amazon.com/) |
| Microsoft Azure | Packer azure-arm | Terraform AzureRM | 12 months | [Azure Docs](https://learn.microsoft.com/azure/) |
| Google Cloud Platform | Packer googlecompute | Terraform Google | e2-micro | [GCP Docs](https://cloud.google.com/docs) |
| IBM Cloud | Packer ibmcloud | Terraform IBM | Lite Plan | [IBM Cloud Docs](https://cloud.ibm.com/docs) |
| Red Hat OpenShift | - | Terraform OpenShift | Developer Sandbox | [OpenShift Docs](https://docs.openshift.com/) |

**Why OCI Always Free?**
- 4 Arm Ampere A1 cores + 24GB RAM (generous for Java applications)
- 200GB block volume storage
- No time limit (truly "always free")
- Excellent for development and small production workloads

## Prerequisites

Before using this documentation, ensure you have:

1. **Tools Installed**
   - [Packer](https://developer.hashicorp.com/packer/docs/install) >= 1.9.0
   - [Terraform](https://developer.hashicorp.com/terraform/install) >= 1.6.0
   - [Docker](https://docs.docker.com/get-docker/) (for local simulation)

2. **Cloud Account Access**
   - Appropriate credentials for your target cloud provider
   - Sufficient permissions to create resources

3. **Project Built**
   ```bash
   ./mvnw package -Dshade
   ```

4. **Cloud Configuration**
   ```bash
   # Source the cloud configuration (defaults to OCI)
   source cloud-config.sh

   # Copy and configure environment variables
   cp .env.cloud.example .env.cloud
   # Edit .env.cloud with your credentials
   ```

## Infrastructure Code Location

All infrastructure code examples are located in `docs/infrastructure/`:

```
docs/infrastructure/
├── packer/          # Machine image templates
│   ├── aws/
│   ├── azure/
│   ├── gcp/
│   └── oci/
├── terraform/       # Infrastructure as code
│   ├── aws/
│   ├── azure/
│   ├── gcp/
│   ├── oci/
│   ├── ibm/
│   └── openshift/
└── simulation/      # Local development tools
    ├── localstack/
    └── azurite/
```
