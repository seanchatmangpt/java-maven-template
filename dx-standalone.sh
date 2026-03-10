#!/usr/bin/env bash
# ==========================================================================
# dx-standalone.sh — Standalone Build Script (No YAWL Submodule)
# ==========================================================================
# Simple Maven build script for when the yawl submodule is not available.
# Provides basic compile/test/validate commands.
#
# Usage:
#   ./dx-standalone.sh compile    # Compile all modules
#   ./dx-standalone.sh test       # Run unit tests
#   ./dx-standalone.sh all        # Full build (verify)
#   ./dx-standalone.sh validate   # Run simplified guards
# ==========================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ── Colors ─────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()    { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC} $*" >&2; }
log_error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ── Guard System Binary ────────────────────────────────────────────────────────
GUARD_SYSTEM="${SCRIPT_DIR}/guard-system/target/release/dx-guard"

ensure_guard_system() {
    if [[ ! -x "$GUARD_SYSTEM" ]]; then
        log_info "Building guard system..."
        cd "${SCRIPT_DIR}/guard-system"
        cargo build --release -q 2>/dev/null || {
            log_warn "Guard system build failed - skipping validation"
            return 1
        }
        cd "$SCRIPT_DIR"
    fi
    return 0
}

# ── Commands ───────────────────────────────────────────────────────────────────

cmd_compile() {
    log_info "Compiling..."
    ./mvnw compile -q
    log_info "Compile complete"
}

cmd_test() {
    log_info "Running tests..."
    ./mvnw test -q
    log_info "Tests complete"
}

cmd_all() {
    log_info "Full build (verify)..."
    ./mvnw verify -q

    # Run guards after successful build
    if ensure_guard_system; then
        log_info "Running validation..."
        "$GUARD_SYSTEM" validate src/ --format text
    fi

    log_info "Build complete"
}

cmd_validate() {
    if ! ensure_guard_system; then
        log_error "Guard system not available"
        exit 2
    fi

    local paths=("${@:-src/}")
    "$GUARD_SYSTEM" validate "${paths[@]}" --format text
}

cmd_clean() {
    log_info "Cleaning..."
    ./mvnw clean -q
    log_info "Clean complete"
}

# ── Help ───────────────────────────────────────────────────────────────────────

show_help() {
    cat << 'EOF'
dx-standalone.sh — Standalone Build Script

Usage:
  ./dx-standalone.sh <command> [options]

Commands:
  compile    Compile all modules
  test       Run unit tests
  all        Full build (verify) + validation
  validate   Run guard validation only
  clean      Clean build artifacts

Examples:
  ./dx-standalone.sh compile
  ./dx-standalone.sh all
  ./dx-standalone.sh validate src/main/java/

EOF
}

# ── Main ───────────────────────────────────────────────────────────────────────

case "${1:-}" in
    compile)
        cmd_compile
        ;;
    test)
        cmd_test
        ;;
    all|"")
        cmd_all
        ;;
    validate)
        shift
        cmd_validate "$@"
        ;;
    clean)
        cmd_clean
        ;;
    -h|--help|help)
        show_help
        ;;
    *)
        log_error "Unknown command: $1"
        show_help
        exit 1
        ;;
esac
