#!/usr/bin/env bash
# ==========================================================================
# simple-guards.sh — Hook Wrapper for Guard System
# ==========================================================================
# Thin wrapper that invokes the Rust-based guard system for file validation.
# This hook runs after Edit/Write operations on Java files.
#
# Exit codes:
#   0 - GREEN (no violations)
#   2 - RED (violations found - blocks the operation)
# ==========================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
GUARD_SYSTEM="${REPO_ROOT}/guard-system/target/release/dx-guard"

# ── Ensure guard system is built ───────────────────────────────────────────────
ensure_guard_binary() {
    if [[ ! -x "$GUARD_SYSTEM" ]]; then
        # Build silently in background
        (
            cd "${REPO_ROOT}/guard-system"
            cargo build --release -q 2>/dev/null
        ) || return 1
    fi
    [[ -x "$GUARD_SYSTEM" ]]
}

# ── Hook mode: read JSON from stdin ────────────────────────────────────────────
if [[ -t 0 ]]; then
    # No stdin - check if file argument provided
    if [[ "${1:-}" == "--file" && -n "${2:-}" ]]; then
        FILE="$2"
    else
        # Nothing to validate
        exit 0
    fi
else
    # Read JSON from stdin (Claude Code hook framework)
    INPUT=$(cat)
    FILE=$(echo "$INPUT" | python3 -c "import sys, json; d=json.load(sys.stdin); print(d.get('tool_input',{}).get('file_path','') or d.get('tool_input',{}).get('path',''))" 2>/dev/null || true)
fi

# ── Only validate Java source files ─────────────────────────────────────────────
if [[ -z "$FILE" ]] || [[ ! "$FILE" =~ \.java$ ]]; then
    exit 0
fi

if [[ ! -f "$FILE" ]]; then
    exit 0
fi

# ── Skip test files (default exclusion) ─────────────────────────────────────────
if [[ "$FILE" =~ /test/ ]] || [[ "$FILE" =~ Test\.java$ ]] || [[ "$FILE" =~ IT\.java$ ]]; then
    exit 0
fi

# ── Run guard validation ────────────────────────────────────────────────────────
if ensure_guard_binary; then
    exec "$GUARD_SYSTEM" hook --file "$FILE"
else
    # Guard system not available - allow through with warning
    echo "[WARN] Guard system not built - skipping validation" >&2
    exit 0
fi
