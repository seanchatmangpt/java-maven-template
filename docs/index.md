# Java Maven Template Documentation

Welcome to the Java Maven Template documentation. This template provides a production-ready Java 25 JPMS library structure with comprehensive build tooling, testing frameworks, and multi-cloud deployment capabilities.

## Documentation Sections

### Cloud Deployment

Multi-cloud deployment documentation following the [Diátaxis framework](https://docs.diataxis.fr/):

- **[Tutorials](cloud/tutorials/index.md)** - Learning-oriented guides for getting started with each cloud provider
- **[How-to Guides](cloud/how-to/index.md)** - Problem-oriented solutions for specific deployment tasks
- **[Reference](cloud/reference/index.md)** - Information-oriented technical specifications
- **[Explanation](cloud/explanation/index.md)** - Understanding-oriented conceptual guides

### Quick Links

| Cloud Provider | Tutorial | Deploy Guide |
|----------------|----------|--------------|
| AWS | [Getting Started](cloud/tutorials/aws-getting-started.md) | [Deploy](cloud/how-to/deploy-to-aws.md) |
| Azure | [Getting Started](cloud/tutorials/azure-getting-started.md) | [Deploy](cloud/how-to/deploy-to-azure.md) |
| GCP | [Getting Started](cloud/tutorials/gcp-getting-started.md) | [Deploy](cloud/how-to/deploy-to-gcp.md) |
| OCI | [Getting Started](cloud/tutorials/oci-getting-started.md) | [Deploy](cloud/how-to/deploy-to-oci.md) |
| IBM Cloud | [Getting Started](cloud/tutorials/ibm-cloud-getting-started.md) | [Deploy](cloud/how-to/deploy-to-ibm-cloud.md) |
| OpenShift | [Getting Started](cloud/tutorials/openshift-getting-started.md) | [Deploy](cloud/how-to/deploy-to-openshift.md) |

## Project Structure

```
java-maven-template/
├── src/main/java/           # Java source code
├── src/test/java/           # Unit tests (*Test.java)
├── src/test/java-it/        # Integration tests (*IT.java)
├── docs/                    # Documentation
│   ├── cloud/              # Multi-cloud deployment docs
│   └── infrastructure/     # IaC examples
└── pom.xml                 # Maven configuration
```

## Build Commands

```bash
./mvnw test              # Run unit tests
./mvnw verify            # Run all tests + quality checks
./mvnw spotless:apply    # Format code
./mvnw package -Dshade   # Build fat JAR
```

## Containerization

This project provides multiple Containerfiles for different use cases:

| Containerfile | Purpose | Base Image |
|--------------|---------|------------|
| `Containerfile` | Production multi-stage build | `maven:4.0.0-rc-5-eclipse-temurin-25` (build), `eclipse-temurin:25-jre-alpine` (runtime) |
| `Containerfile.full` | CI/CD with mvnd support | Maven 4 + Java 25 + mvnd |
| `Containerfile.dev` | Development environment | Maven 4 + Java 25 + mvnd |
| `Containerfile.minimal` | Pre-built JAR deployment | `eclipse-temurin:25-jre-alpine` |

### Build Container Images

```bash
# Production build (multi-stage)
docker build -f Containerfile -t java-maven-template:latest .

# With mvnd for faster CI builds
docker build -f Containerfile.full -t java-maven-template:ci .

# Minimal runtime (requires pre-built JAR)
./mvnw package -Dshade
docker build -f Containerfile.minimal -t java-maven-template:runtime .
```

### Container Standards

- **Java Version**: 25 (Eclipse Temurin)
- **Maven Version**: 4.0.0-rc-5
- **Build Acceleration**: mvnd (Maven Daemon) for faster builds
- **Security**: Non-root user, minimal runtime image
- **Platform**: Multi-arch (amd64, arm64)

## Supported Cloud Providers

| Provider | Packer Support | Terraform Provider | Local Simulation |
|----------|---------------|-------------------|------------------|
| AWS | amazon-ebs | hashicorp/aws | LocalStack |
| Azure | azure-arm | hashicorp/azurerm | Azurite |
| GCP | googlecompute | hashicorp/google | - |
| OCI | oracle-oci | oracle/oci | - |
| IBM Cloud | ibmcloud | IBM-Cloud/ibm | - |
| OpenShift | - | openshift/openshift | crc (CodeReady Containers) |
