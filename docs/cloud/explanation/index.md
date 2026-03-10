# Explanation - Understanding Multi-Cloud

These guides provide conceptual understanding of multi-cloud deployment strategies, tools, and best practices.

## Topics

| Guide | Description |
|-------|-------------|
| [Architecture Overview](architecture-overview.md) | System architecture and components |
| [Packer vs Terraform](packer-vs-terraform.md) | When to use each tool |
| [Multi-Cloud Strategy](multi-cloud-strategy.md) | Approaches to multi-cloud deployment |
| [Security Best Practices](security-best-practices.md) | Security considerations |

## Key Concepts

### Infrastructure as Code (IaC)

Infrastructure as Code is the practice of managing and provisioning infrastructure through machine-readable configuration files rather than manual processes.

**Benefits**:
- Version control for infrastructure
- Reproducible deployments
- Documentation as code
- Automated testing

### Immutable Infrastructure

Immutable infrastructure means servers are never modified after deployment. Instead, new servers are created with updated configurations.

**Benefits**:
- Consistent environments
- Easier rollbacks
- Reduced configuration drift
- Simplified debugging

### The Diátaxis Framework

This documentation follows the [Diátaxis framework](https://docs.diataxis.fr/), which organizes documentation into four types:

| Type | Purpose | User Need |
|------|---------|-----------|
| Tutorials | Learning-oriented | I want to learn |
| How-to Guides | Problem-oriented | I want to accomplish X |
| Reference | Information-oriented | I need information |
| Explanation | Understanding-oriented | I want to understand |

## Cloud Provider Comparison

| Feature | AWS | Azure | GCP | OCI | IBM Cloud |
|---------|-----|-------|-----|-----|-----------|
| Compute | EC2 | VM | Compute Engine | Compute | VPC |
| Storage | S3 | Blob | Cloud Storage | Object Storage | Object Storage |
| Database | RDS | SQL | Cloud SQL | Autonomous DB | Databases |
| Container | EKS | AKS | GKE | OKE | IKS |
| Serverless | Lambda | Functions | Cloud Functions | Functions | Functions |

## Related Documentation

- **[Tutorials](../tutorials/index.md)** - Get started with each cloud
- **[How-to Guides](../how-to/index.md)** - Solve specific problems
- **[Reference](../reference/index.md)** - Technical specifications
