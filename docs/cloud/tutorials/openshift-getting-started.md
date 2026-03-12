# OpenShift Getting Started Tutorial

This tutorial guides you through deploying your Java Maven Template application to Red Hat OpenShift using Terraform and containerized deployment.

**Time required**: 60-90 minutes

**Prerequisites**:
- OpenShift cluster (OCP 4.x or OKD)
- Terraform >= 1.6.0
- OpenShift CLI (`oc`)
- Docker or Podman

## Learning Objectives

By completing this tutorial, you will:

1. Set up OpenShift CLI and configure authentication
2. Containerize your Java application
3. Deploy OpenShift resources with Terraform
4. Verify your application is running correctly
5. Clean up resources

## Step 1: OpenShift Environment Setup

### Options for OpenShift Access

Choose one of the following:

| Option | Description | Cost |
|--------|-------------|------|
| Red Hat Developer Sandbox | Free 30-day sandbox | Free |
| OpenShift Dedicated | Managed OpenShift on AWS/GCP | Pay-as-you-go |
| Self-managed OCP | Install on your infrastructure | Subscription |
| OKD | Community version | Free |
| CodeReady Containers | Local OpenShift for development | Free |

### Red Hat Developer Sandbox (Recommended for Learning)

1. Navigate to [Red Hat Developer Sandbox](https://developers.redhat.com/developer-sandbox)
2. Click "Start your sandbox for free"
3. Login with your Red Hat account
4. Wait for sandbox to be ready (5-10 minutes)

### Install OpenShift CLI

```bash
# macOS
brew install openshift-cli

# Or download from OpenShift Console
# Console → ? → Command Line Tools → oc

# Verify installation
oc version
```

### Login to OpenShift

```bash
# From Developer Sandbox or Console
# Copy login command from Console → user menu → Copy login command

oc login --token=<token> --server=https://api.<cluster-domain>:6443

# Verify login
oc whoami
oc get projects
```

## Step 2: Build and Containerize Your Application

### Build Your Application

```bash
# Navigate to project root
cd /path/to/jotp

# Build the fat JAR
./mvnw package -Dshade

# Verify the JAR exists
ls -la target/*.jar
```

### Create Containerfile

Create `Containerfile` in the project root:

```dockerfile
FROM registry.access.redhat.com/ubi9/openjdk-21:latest

COPY target/jotp-*-SNAPSHOT.jar /deployments/app.jar

EXPOSE 8080

USER 185
ENTRYPOINT ["java", "-jar", "/deployments/app.jar"]
```

### Build Container Image

```bash
# Using Docker
docker build -t jotp:latest .

# Using Podman
podman build -t jotp:latest .
```

### Push to Registry

For Developer Sandbox:

```bash
# Login to internal registry
oc registry login

# Tag and push
docker tag jotp:latest image-registry.openshift-image-registry.svc:5000/$(oc project -q)/jotp:latest
docker push image-registry.openshift-image-registry.svc:5000/$(oc project -q)/jotp:latest
```

For external registry (Quay.io, Docker Hub):

```bash
docker tag jotp:latest quay.io/<username>/jotp:latest
docker push quay.io/<username>/jotp:latest
```

## Step 3: Deploy with Terraform

### Configure OpenShift Provider

```bash
cd docs/infrastructure/terraform/openshift
```

Create `terraform.tfvars`:

```hcl
openshift_server_url = "https://api.<cluster-domain>:6443"
openshift_token      = "<your-oc-token>"
namespace            = "java-maven-app"
app_name             = "jotp"
image                = "image-registry.openshift-image-registry.svc:5000/java-maven-app/jotp:latest"
replicas             = 1
```

### Initialize Terraform

```bash
terraform init
```

### Review Deployment Plan

```bash
terraform plan
```

Review the resources that will be created:
- 1 Project/Namespace
- 1 DeploymentConfig or Deployment
- 1 Service
- 1 Route
- 1 ConfigMap (optional)
- 1 Secret (optional)

### Apply Configuration

```bash
terraform apply
```

Type `yes` when prompted.

## Step 4: Alternative - Deploy with OpenShift CLI

If not using Terraform:

```bash
# Create new project
oc new-project java-maven-app

# Deploy application
oc new-app --name jotp \
  --docker-image=image-registry.openshift-image-registry.svc:5000/java-maven-app/jotp:latest

# Create route (external access)
oc expose svc/jotp

# Get route URL
oc get route jotp
```

## Step 5: Verify Deployment

### Check Deployment Status

```bash
# Get pods
oc get pods -n java-maven-app

# Check deployment
oc rollout status deployment/jotp

# View logs
oc logs -f deployment/jotp
```

### Get Application Route

```bash
# Get route URL
oc get route jotp -o jsonpath='{.spec.host}'

# Test application
curl http://jotp-java-maven-app.<cluster-domain>/health
```

### View in OpenShift Console

1. Navigate to OpenShift Web Console
2. Switch to Developer perspective
3. Click "Topology"
4. Find your application

## Step 6: Clean Up Resources

### Using Terraform

```bash
terraform destroy
```

### Using OpenShift CLI

```bash
# Delete all resources in project
oc delete all -l app=jotp -n java-maven-app

# Delete project
oc delete project java-maven-app
```

## Local Development with CodeReady Containers

For local OpenShift development:

```bash
# Install CRC
brew install crc

# Setup CRC
crc setup

# Start CRC
crc start

# Login
eval $(crc oc-env)
oc login -u developer -p developer https://api.crc.testing:6443
```

## Troubleshooting

### Common Issues

| Error | Solution |
|-------|----------|
| `Unauthorized` | Verify token with `oc whoami -t` |
| `ImagePullBackOff` | Check image exists and registry access |
| `CrashLoopBackOff` | Check logs with `oc logs` |
| `Route not accessible` | Verify route and service selector |

### Useful Commands

```bash
# Get cluster info
oc cluster-info

# Describe resource
oc describe deployment jotp

# View events
oc get events --sort-by='.lastTimestamp'

# Port forward for local testing
oc port-forward svc/jotp 8080:8080

# Execute command in pod
oc exec -it <pod-name> -- /bin/bash
```

## OpenShift-Specific Features

- **Source-to-Image (S2I)**: Build from source automatically
- **DeploymentConfig**: Advanced deployment strategies
- **Routes**: HTTP/HTTPS routing with TLS
- **ImageStreams**: Track image updates
- **BuildConfig**: Automated builds

## Next Steps

- [Deploy to OpenShift How-to Guide](../how-to/deploy-to-openshift.md) - Production deployment strategies
- [Configure CI/CD](../how-to/configure-ci-cd.md) - Automate deployments

## Additional Resources

- [OpenShift Documentation](https://docs.openshift.com/)
- [Terraform OpenShift Provider](https://registry.terraform.io/providers/openshift/openshift/latest/docs)
- [Red Hat Developer Sandbox](https://developers.redhat.com/developer-sandbox)
- [OpenShift Terraform Install](https://docs.openshift.com/container-platform/4.15/installing/installing_aws/installing-aws-terraform.html)
- [CodeReady Containers](https://developers.redhat.com/products/codeready-containers)
