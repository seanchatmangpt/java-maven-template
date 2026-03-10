#!/usr/bin/env bash
# ==========================================================================
# dx.sh — Template-Level Build Wrapper
# ==========================================================================
# Delegates to yawl submodule's dx.sh when available, otherwise falls back
# to dx-standalone.sh for basic Maven builds.
#
# Usage:
#   ./dx.sh compile          # Compile changed modules
#   ./dx.sh test             # Run tests
#   ./dx.sh all              # Full build + validation
#   ./dx.sh validate         # Run guard validation only
#   ./dx.sh deploy           # Deploy to cloud (OCI default)
#
# Environment:
#   CLOUD_PROVIDER=oci       # Override default provider (from cloud-config.sh)
# ==========================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ── Source cloud configuration (OCI default) ───────────────────────────────────
# shellcheck source=cloud-config.sh
source "${SCRIPT_DIR}/cloud-config.sh" 2>/dev/null || true

# ── Detect yawl submodule ──────────────────────────────────────────────────────
YAWL_DX="${SCRIPT_DIR}/yawl/scripts/dx.sh"
STANDALONE_DX="${SCRIPT_DIR}/dx-standalone.sh"
GUARD_SYSTEM="${SCRIPT_DIR}/guard-system/target/release/dx-guard"

# ── Handle deploy command ───────────────────────────────────────────────────────
if [[ "${1:-}" == "deploy" ]]; then
    shift
    exec "${SCRIPT_DIR}/deploy.sh" "$@"
fi

# ── Handle validate command ─────────────────────────────────────────────────────
if [[ "${1:-}" == "validate" ]]; then
    shift
    # Build guard system if needed
    if [[ ! -x "$GUARD_SYSTEM" ]]; then
        echo "[INFO] Building guard system..."
        cd "${SCRIPT_DIR}/guard-system"
        cargo build --release -q 2>/dev/null || {
            echo "[WARN] Guard system build failed, using basic validation"
            exec "$STANDALONE_DX" validate "$@"
        }
        cd "$SCRIPT_DIR"
    fi

    # Default to src/ if no paths provided
    PATHS=("${@:-src/}")
    exec "$GUARD_SYSTEM" validate "${PATHS[@]}" --format text
fi

# ── Delegate to yawl or standalone ──────────────────────────────────────────────
if [[ -x "$YAWL_DX" ]]; then
    # Yawl submodule available - delegate all commands
    exec "$YAWL_DX" "$@"
elif [[ -x "$STANDALONE_DX" ]]; then
    # Use standalone fallback
    exec "$STANDALONE_DX" "$@"
else
    # Direct Maven fallback (minimal)
    case "${1:-}" in
        compile)
            ./mvnw compile -q
            ;;
        test)
            ./mvnw test -q
            ;;
        all|"")
            ./mvnw verify -q
            ;;
        *)
            echo "Usage: $0 [compile|test|all|validate|deploy]" >&2
            exit 1
            ;;
    esac
fi
