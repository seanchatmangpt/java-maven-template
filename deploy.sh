#!/usr/bin/env bash
# ==========================================================================
# deploy.sh — Cloud Deployment Script
# ==========================================================================
# Deploys the Java Maven Template application to the configured cloud provider.
# Defaults to Oracle Cloud Infrastructure (OCI) Always Free tier.
#
# Usage:
#   ./deploy.sh              # Full deploy (build + image + infra)
#   ./deploy.sh --build      # Build application only
#   ./deploy.sh --image      # Build VM image only
#   ./deploy.sh --infra      # Deploy infrastructure only
#   ./deploy.sh --destroy    # Destroy infrastructure
#
# Environment:
#   CLOUD_PROVIDER=oci       # Override default provider
#   source cloud-config.sh   # Load defaults first
# ==========================================================================

set -euo pipefail

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Source cloud configuration
# shellcheck source=cloud-config.sh
source "${SCRIPT_DIR}/cloud-config.sh"

# ── Color Output ─────────────────────────────────────────────────────────────

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info()    { echo -e "${BLUE}[INFO]${NC} $*"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $*"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC} $*" >&2; }
log_error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ── Help ─────────────────────────────────────────────────────────────────────

show_help() {
    cat << 'EOF'
Java Maven Template - Cloud Deployment

Usage:
  ./deploy.sh [COMMAND]

Commands:
  (default)    Full deployment pipeline (build + image + infra)
  --build      Build application JAR only
  --image      Build VM image with Packer only
  --infra      Deploy infrastructure with Terraform only
  --destroy    Destroy all infrastructure
  --status     Show deployment status
  --config     Show current cloud configuration
  --help       Show this help message

Environment Variables:
  CLOUD_PROVIDER    Cloud provider (oci, aws, azure, gcp, ibm)
                   Default: oci

Examples:
  # Deploy to OCI (default)
  ./deploy.sh

  # Deploy to AWS
  CLOUD_PROVIDER=aws ./deploy.sh

  # Build application only
  ./deploy.sh --build

  # Destroy infrastructure
  ./deploy.sh --destroy

EOF
}

# ── Commands ─────────────────────────────────────────────────────────────────

build_app() {
    log_info "Building application..."

    if [[ ! -f "./mvnw" ]]; then
        log_error "mvnw not found. Run from project root."
        exit 1
    fi

    ./mvnw clean package -Dshade -q

    # Find the built JAR
    local jar_file
    jar_file=$(find target -name "*.jar" -not -name "*-sources.jar" -not -name "*-javadoc.jar" | head -1)

    if [[ -z "$jar_file" ]]; then
        log_error "No JAR file found in target/"
        exit 1
    fi

    log_success "Built: $jar_file"
}

build_image() {
    log_info "Building VM image for $CLOUD_PROVIDER_NAME..."
    log_info "Packer directory: $PACKER_DIR"

    if [[ ! -d "$PACKER_DIR" ]]; then
        log_error "Packer directory not found: $PACKER_DIR"
        exit 1
    fi

    # Check for packer
    if ! command -v packer &>/dev/null; then
        log_error "Packer not installed. See: https://developer.hashicorp.com/packer/docs/install"
        exit 1
    fi

    cd "$PACKER_DIR"

    # Initialize packer
    packer init .

    # Build image based on provider
    case "$CLOUD_PROVIDER" in
        oci)
            if [[ ! -f "variables.pkrvars.hcl" ]]; then
                log_error "Missing variables.pkrvars.hcl. Copy from example and configure."
                log_info "See: docs/cloud/tutorials/oci-getting-started.md"
                exit 1
            fi
            packer build -var-file=variables.pkrvars.hcl java-maven-image.pkr.hcl
            ;;
        aws)
            packer build java-maven-ami.pkr.hcl
            ;;
        azure|gcp)
            if [[ ! -f "variables.pkrvars.hcl" ]]; then
                log_error "Missing variables.pkrvars.hcl"
                exit 1
            fi
            packer build -var-file=variables.pkrvars.hcl java-maven-image.pkr.hcl
            ;;
        *)
            log_error "Packer build not configured for $CLOUD_PROVIDER"
            exit 1
            ;;
    esac

    cd "$SCRIPT_DIR"
    log_success "VM image built successfully"
}

deploy_infra() {
    log_info "Deploying infrastructure to $CLOUD_PROVIDER_NAME..."
    log_info "Terraform directory: $TERRAFORM_DIR"

    if [[ ! -d "$TERRAFORM_DIR" ]]; then
        log_error "Terraform directory not found: $TERRAFORM_DIR"
        exit 1
    fi

    # Check for terraform
    if ! command -v terraform &>/dev/null; then
        log_error "Terraform not installed. See: https://developer.hashicorp.com/terraform/install"
        exit 1
    fi

    cd "$TERRAFORM_DIR"

    # Initialize terraform
    terraform init

    # Plan
    log_info "Planning deployment..."
    terraform plan -out=tfplan

    # Apply
    log_info "Applying deployment..."
    terraform apply tfplan

    # Show outputs
    log_success "Infrastructure deployed successfully"
    terraform output

    cd "$SCRIPT_DIR"
}

destroy_infra() {
    log_warn "This will destroy all infrastructure in $TERRAFORM_DIR"
    read -p "Are you sure? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        log_info "Aborted"
        exit 0
    fi

    log_info "Destroying infrastructure..."

    cd "$TERRAFORM_DIR"
    terraform destroy
    cd "$SCRIPT_DIR"

    log_success "Infrastructure destroyed"
}

show_status() {
    log_info "Deployment Status for $CLOUD_PROVIDER_NAME"

    # Check application build
    if find target -name "*.jar" -not -name "*-sources.jar" 2>/dev/null | grep -q .; then
        log_success "Application: Built"
    else
        log_warn "Application: Not built (run --build)"
    fi

    # Check terraform state
    if [[ -f "${TERRAFORM_DIR}/terraform.tfstate" ]]; then
        log_info "Terraform state exists. Run 'terraform output' for details."
    else
        log_info "No infrastructure deployed"
    fi
}

show_config() {
    cloud-config-show
}

full_deploy() {
    log_info "Starting full deployment to $CLOUD_PROVIDER_NAME..."

    build_app
    build_image
    deploy_infra

    log_success "Full deployment complete!"
}

# ── Main ─────────────────────────────────────────────────────────────────────

case "${1:-}" in
    --build)
        build_app
        ;;
    --image)
        build_image
        ;;
    --infra)
        deploy_infra
        ;;
    --destroy)
        destroy_infra
        ;;
    --status)
        show_status
        ;;
    --config)
        show_config
        ;;
    --help|-h|help)
        show_help
        ;;
    "")
        full_deploy
        ;;
    *)
        log_error "Unknown option: $1"
        show_help
        exit 1
        ;;
esac
