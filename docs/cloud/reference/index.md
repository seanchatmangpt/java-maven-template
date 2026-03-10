# Reference Documentation

This section provides technical specifications, configuration schemas, and command references for multi-cloud deployment tools.

## Quick Reference

| Resource | Description |
|----------|-------------|
| [Packer Builders](packer-builders.md) | Machine image builder configurations |
| [Terraform Providers](terraform-providers.md) | Infrastructure provider settings |
| [Simulation Tools](simulation-tools.md) | Local development tools |
| [CLI Commands](cli-commands.md) | Command-line reference |
| [Configuration Schema](configuration-schema.md) | Configuration file formats |

## Packer Builders

| Builder | Provider | Documentation |
|---------|----------|---------------|
| amazon-ebs | AWS | [Reference](packer-builders.md#amazon-ebs) |
| azure-arm | Azure | [Reference](packer-builders.md#azure-arm) |
| googlecompute | GCP | [Reference](packer-builders.md#googlecompute) |
| oracle-oci | OCI | [Reference](packer-builders.md#oracle-oci) |

## Terraform Providers

| Provider | Version | Documentation |
|----------|---------|---------------|
| hashicorp/aws | ~> 5.0 | [Reference](terraform-providers.md#aws) |
| hashicorp/azurerm | ~> 3.0 | [Reference](terraform-providers.md#azurerm) |
| hashicorp/google | ~> 5.0 | [Reference](terraform-providers.md#google) |
| oracle/oci | ~> 5.0 | [Reference](terraform-providers.md#oci) |
| IBM-Cloud/ibm | ~> 1.0 | [Reference](terraform-providers.md#ibm) |

## CLI Commands

### Packer Commands

```bash
packer init .              # Initialize plugins
packer validate config.pkr.hcl  # Validate configuration
packer build config.pkr.hcl     # Build image
packer console            # Interactive console
```

### Terraform Commands

```bash
terraform init            # Initialize providers
terraform plan            # Preview changes
terraform apply           # Apply changes
terraform destroy         # Destroy resources
terraform output          # Show outputs
```

## Configuration File Locations

```
docs/infrastructure/
├── packer/
│   ├── aws/java-maven-ami.pkr.hcl
│   ├── azure/java-maven-image.pkr.hcl
│   ├── gcp/java-maven-image.pkr.hcl
│   └── oci/java-maven-image.pkr.hcl
├── terraform/
│   ├── aws/{main,variables,outputs}.tf
│   ├── azure/{main,variables,outputs}.tf
│   ├── gcp/{main,variables,outputs}.tf
│   ├── oci/{main,variables,outputs}.tf
│   ├── ibm/{main,variables,outputs}.tf
│   └── openshift/{main,variables,outputs}.tf
└── simulation/
    ├── localstack/docker-compose.yml
    └── azurite/docker-compose.yml
```

## External Documentation Links

### HashiCorp
- [Terraform Documentation](https://developer.hashicorp.com/terraform/docs)
- [Packer Documentation](https://developer.hashicorp.com/packer/docs)
- [Terraform AWS Tutorial](https://developer.hashicorp.com/terraform/tutorials/aws-get-started)

### Cloud Providers
- [AWS Documentation](https://docs.aws.amazon.com/)
- [Azure Documentation](https://learn.microsoft.com/azure/)
- [GCP Documentation](https://cloud.google.com/docs)
- [OCI Documentation](https://docs.oracle.com/en-us/iaas/)
- [IBM Cloud Docs](https://cloud.ibm.com/docs)
- [OpenShift Docs](https://docs.openshift.com/)

### Terraform Registry
- [AWS Provider](https://registry.terraform.io/providers/hashicorp/aws/latest/docs)
- [Azure Provider](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs)
- [GCP Provider](https://registry.terraform.io/providers/hashicorp/google/latest/docs)
- [OCI Provider](https://registry.terraform.io/providers/oracle/oci/latest/docs)
- [IBM Provider](https://registry.terraform.io/providers/IBM-Cloud/ibm/latest/docs)

## Related Documentation

- **[Tutorials](../tutorials/index.md)** - Learning guides
- **[How-to Guides](../how-to/index.md)** - Task solutions
- **[Explanation](../explanation/index.md)** - Conceptual guides
