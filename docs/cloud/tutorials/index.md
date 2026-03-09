# Tutorials - Getting Started

These tutorials provide step-by-step guides for deploying your Java Maven Template application to each cloud provider. Each tutorial assumes no prior experience with the target cloud platform.

## Learning Path

We recommend completing tutorials in this order based on your target platform:

### Beginner Path

1. **[AWS Getting Started](aws-getting-started.md)** - Most extensive documentation and community support
2. **[Azure Getting Started](azure-getting-started.md)** - Strong enterprise integration features
3. **[GCP Getting Started](gcp-getting-started.md)** - Excellent container and Kubernetes support

### Advanced Path

4. **[OCI Getting Started](oci-getting-started.md)** - Oracle Cloud with competitive pricing
5. **[IBM Cloud Getting Started](ibm-cloud-getting-started.md)** - Enterprise Watson AI integration
6. **[OpenShift Getting Started](openshift-getting-started.md)** - Kubernetes-based container platform

## What You'll Learn

Each tutorial covers:

| Topic | Description |
|-------|-------------|
| Account Setup | Creating and configuring cloud accounts |
| CLI Installation | Installing command-line tools |
| Authentication | Setting up credentials and access |
| Packer Build | Creating custom machine images |
| Terraform Deploy | Provisioning infrastructure |
| Verification | Confirming successful deployment |
| Cleanup | Removing resources to avoid charges |

## Prerequisites

Before starting any tutorial:

```bash
# Install Packer
brew install packer  # macOS
# or download from https://developer.hashicorp.com/packer/docs/install

# Install Terraform
brew install terraform  # macOS
# or download from https://developer.hashicorp.com/terraform/install

# Build your application
./mvnw package -Dshade
```

## Time Estimates

| Tutorial | Estimated Time |
|----------|---------------|
| AWS | 45-60 minutes |
| Azure | 45-60 minutes |
| GCP | 40-55 minutes |
| OCI | 50-70 minutes |
| IBM Cloud | 45-60 minutes |
| OpenShift | 60-90 minutes |

## Getting Help

- **AWS**: [AWS Documentation](https://docs.aws.amazon.com/) | [Terraform AWS Tutorial](https://developer.hashicorp.com/terraform/tutorials/aws-get-started)
- **Azure**: [Azure Documentation](https://learn.microsoft.com/azure/) | [Terraform Azure Tutorial](https://developer.hashicorp.com/terraform/tutorials/azure-get-started)
- **GCP**: [GCP Documentation](https://cloud.google.com/docs) | [Terraform GCP Tutorial](https://developer.hashicorp.com/terraform/tutorials/gcp-get-started)
- **OCI**: [OCI Documentation](https://docs.oracle.com/en-us/iaas/) | [OCI Terraform Guide](https://docs.oracle.com/en-us/iaas/Content/dev/terraform/home.htm)
- **IBM Cloud**: [IBM Cloud Docs](https://cloud.ibm.com/docs) | [Schematics Guide](https://cloud.ibm.com/docs/schematics?topic=schematics-getting-started)
- **OpenShift**: [OpenShift Docs](https://docs.openshift.com/) | [Terraform Install](https://docs.openshift.com/container-platform/4.15/installing/installing_aws/installing-aws-terraform.html)

## Next Steps

After completing a tutorial:

1. Review [How-to Guides](../how-to/index.md) for specific tasks
2. Consult [Reference](../reference/index.md) for detailed configurations
3. Read [Explanation](../explanation/index.md) for deeper understanding
