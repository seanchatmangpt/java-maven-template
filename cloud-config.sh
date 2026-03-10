#!/usr/bin/env bash
# ==========================================================================
# cloud-config.sh — Cloud Provider Configuration
# ==========================================================================
# Default: Oracle Cloud Infrastructure (OCI) Always Free
#
# This file configures the default cloud provider for all infrastructure
# scripts in this template. Source this file before running deployment commands.
#
# Usage:
#   source cloud-config.sh
#   ./deploy.sh  # Will use OCI by default
#
# Override:
#   export CLOUD_PROVIDER=aws
#   source cloud-config.sh
# ==========================================================================

set -euo pipefail

# ── Default Cloud Provider ───────────────────────────────────────────────────

# Default to Oracle Cloud Infrastructure (Always Free tier)
export CLOUD_PROVIDER="${CLOUD_PROVIDER:-oci}"

# ── Provider-Specific Defaults ───────────────────────────────────────────────

case "$CLOUD_PROVIDER" in
    oci|oracle)
        export CLOUD_PROVIDER="oci"
        export CLOUD_PROVIDER_NAME="Oracle Cloud Infrastructure"
        export CLOUD_FREE_TIER="Always Free"

        # Default region for OCI (Phoenix is a common choice for Always Free)
        export OCI_REGION="${OCI_REGION:-us-phoenix-1}"

        # Always Free tier shape (Ampere A1 - ARM instances)
        export OCI_SHAPE="${OCI_SHAPE:-VM.Standard.A1.Flex}"
        export OCI_OCPUS="${OCI_OCPUS:-4}"
        export OCI_MEMORY="${OCI_MEMORY:-24}"

        # Default paths
        export OCI_CONFIG_PATH="${OCI_CONFIG_PATH:-$HOME/.oci/config}"
        export OCI_KEY_PATH="${OCI_KEY_PATH:-$HOME/.oci/oci_api_key.pem}"

        # Infrastructure paths
        export PACKER_DIR="${PACKER_DIR:-docs/infrastructure/packer/oci}"
        export TERRAFORM_DIR="${TERRAFORM_DIR:-docs/infrastructure/terraform/oci}"

        # Always Free tier resources (as of 2024)
        # - 2 AMD Compute VMs (1/8 OCPU, 1GB RAM each)
        # - 4 Arm Ampere A1 cores + 24GB RAM
        # - 200GB block volume storage
        # - 10GB object storage
        # - 1 load balancer
        # - 10TB/month outbound data transfer
        ;;

    aws)
        export CLOUD_PROVIDER_NAME="Amazon Web Services"
        export CLOUD_FREE_TIER="Free Tier (12 months)"

        export AWS_REGION="${AWS_REGION:-us-east-1}"
        export PACKER_DIR="${PACKER_DIR:-docs/infrastructure/packer/aws}"
        export TERRAFORM_DIR="${TERRAFORM_DIR:-docs/infrastructure/terraform/aws}"
        ;;

    azure)
        export CLOUD_PROVIDER_NAME="Microsoft Azure"
        export CLOUD_FREE_TIER="Free Services (12 months)"

        export AZURE_REGION="${AZURE_REGION:-eastus}"
        export PACKER_DIR="${PACKER_DIR:-docs/infrastructure/packer/azure}"
        export TERRAFORM_DIR="${TERRAFORM_DIR:-docs/infrastructure/terraform/azure}"
        ;;

    gcp)
        export CLOUD_PROVIDER_NAME="Google Cloud Platform"
        export CLOUD_FREE_TIER="Free Tier (e2-micro)"

        export GCP_REGION="${GCP_REGION:-us-central1}"
        export PACKER_DIR="${PACKER_DIR:-docs/infrastructure/packer/gcp}"
        export TERRAFORM_DIR="${TERRAFORM_DIR:-docs/infrastructure/terraform/gcp}"
        ;;

    ibm)
        export CLOUD_PROVIDER_NAME="IBM Cloud"
        export CLOUD_FREE_TIER="Lite Plan"

        export IBM_REGION="${IBM_REGION:-us-south}"
        export PACKER_DIR="${PACKER_DIR:-docs/infrastructure/packer/ibm}"
        export TERRAFORM_DIR="${TERRAFORM_DIR:-docs/infrastructure/terraform/ibm}"
        ;;

    openshift)
        export CLOUD_PROVIDER_NAME="Red Hat OpenShift"
        export CLOUD_FREE_TIER="Developer Sandbox"

        export OPENSHIFT_REGION="${OPENSHIFT_REGION:-us-east-1}"
        export TERRAFORM_DIR="${TERRAFORM_DIR:-docs/infrastructure/terraform/openshift}"
        ;;

    *)
        echo "ERROR: Unknown CLOUD_PROVIDER: $CLOUD_PROVIDER" >&2
        echo "Supported providers: oci, aws, azure, gcp, ibm, openshift" >&2
        return 1 2>/dev/null || exit 1
        ;;
esac

# ── Helper Functions ─────────────────────────────────────────────────────────

# Print current cloud configuration
cloud-config-show() {
    echo "Cloud Configuration:"
    echo "  Provider:      $CLOUD_PROVIDER_NAME ($CLOUD_PROVIDER)"
    echo "  Free Tier:     $CLOUD_FREE_TIER"
    echo "  Packer Dir:    $PACKER_DIR"
    echo "  Terraform Dir: $TERRAFORM_DIR"

    case "$CLOUD_PROVIDER" in
        oci)
            echo "  Region:        ${OCI_REGION}"
            echo "  Shape:         ${OCI_SHAPE}"
            echo "  OCPUs:         ${OCI_OCPUS}"
            echo "  Memory (GB):   ${OCI_MEMORY}"
            ;;
        aws)
            echo "  Region:        ${AWS_REGION}"
            ;;
        azure)
            echo "  Region:        ${AZURE_REGION}"
            ;;
        gcp)
            echo "  Region:        ${GCP_REGION}"
            ;;
    esac
}

# Build image with Packer
cloud-build-image() {
    local packer_dir="${PACKER_DIR:-docs/infrastructure/packer/${CLOUD_PROVIDER}}"

    if [[ ! -d "$packer_dir" ]]; then
        echo "ERROR: Packer directory not found: $packer_dir" >&2
        return 1
    fi

    echo "Building image for $CLOUD_PROVIDER_NAME in $packer_dir"
    cd "$packer_dir"

    case "$CLOUD_PROVIDER" in
        oci)
            packer init .
            packer build java-maven-image.pkr.hcl
            ;;
        aws)
            packer init .
            packer build java-maven-ami.pkr.hcl
            ;;
        azure)
            packer init .
            packer build java-maven-image.pkr.hcl
            ;;
        gcp)
            packer init .
            packer build java-maven-image.pkr.hcl
            ;;
        *)
            echo "Packer not configured for $CLOUD_PROVIDER" >&2
            return 1
            ;;
    esac
}

# Deploy with Terraform
cloud-deploy() {
    local tf_dir="${TERRAFORM_DIR:-docs/infrastructure/terraform/${CLOUD_PROVIDER}}"

    if [[ ! -d "$tf_dir" ]]; then
        echo "ERROR: Terraform directory not found: $tf_dir" >&2
        return 1
    fi

    echo "Deploying to $CLOUD_PROVIDER_NAME with Terraform"
    cd "$tf_dir"
    terraform init
    terraform plan
    terraform apply
}

# Destroy infrastructure
cloud-destroy() {
    local tf_dir="${TERRAFORM_DIR:-docs/infrastructure/terraform/${CLOUD_PROVIDER}}"

    if [[ ! -d "$tf_dir" ]]; then
        echo "ERROR: Terraform directory not found: $tf_dir" >&2
        return 1
    fi

    echo "Destroying $CLOUD_PROVIDER_NAME infrastructure"
    cd "$tf_dir"
    terraform destroy
}

# ── Initialization Message ───────────────────────────────────────────────────

echo "Cloud provider set to: $CLOUD_PROVIDER_NAME ($CLOUD_PROVIDER)"
echo "Free tier: $CLOUD_FREE_TIER"
echo "Run 'cloud-config-show' for full configuration details"
