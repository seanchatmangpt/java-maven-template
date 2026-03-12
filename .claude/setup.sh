#!/usr/bin/env bash
# .claude/setup.sh — Auto-install GraalVM CE 25.0.2 + mvnd 2.0.0-rc-3
# Called by SessionStart hook so every Claude Code session has the right tools.
set -euo pipefail

GRAALVM_HOME="/usr/lib/jvm/graalvm-community-openjdk-25.0.2+10.1"
GRAALVM_URL="https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-25.0.2/graalvm-community-jdk-25.0.2_linux-x64_bin.tar.gz"

MVND_VERSION="2.0.0-rc-3"
MVND_HOME="/root/.mvnd/mvnd-${MVND_VERSION}"
MVND_URL="https://github.com/apache/maven-mvnd/releases/download/${MVND_VERSION}/maven-mvnd-${MVND_VERSION}-linux-amd64.tar.gz"
MVND_BIN="${MVND_HOME}/bin/mvnd"

# ── GraalVM CE 25.0.2 ──────────────────────────────────────────────────────
if [ ! -x "${GRAALVM_HOME}/bin/java" ]; then
  echo "⬇  Installing GraalVM CE 25.0.2..."
  TMP=$(mktemp -d)
  curl -fsSL "${GRAALVM_URL}" -o "${TMP}/graalvm.tar.gz"
  tar -xzf "${TMP}/graalvm.tar.gz" -C "${TMP}"
  # The archive extracts to a directory like graalvm-community-openjdk-25.0.2+10.1
  EXTRACTED=$(ls -d "${TMP}"/graalvm-community-openjdk-* 2>/dev/null | head -1)
  if [ -z "$EXTRACTED" ]; then
    echo "ERROR: Could not find extracted GraalVM directory in ${TMP}" >&2
    ls "${TMP}" >&2
    rm -rf "${TMP}"
    exit 1
  fi
  mkdir -p /usr/lib/jvm
  mv "${EXTRACTED}" "${GRAALVM_HOME}"
  rm -rf "${TMP}"
  echo "✓  GraalVM CE 25.0.2 installed at ${GRAALVM_HOME}"
else
  echo "✓  GraalVM CE 25.0.2 already at ${GRAALVM_HOME}"
fi

# ── mvnd 2.0.0-rc-3 ───────────────────────────────────────────────────────
if [ ! -x "${MVND_BIN}" ]; then
  echo "⬇  Installing mvnd ${MVND_VERSION}..."
  TMP=$(mktemp -d)
  curl -fsSL "${MVND_URL}" -o "${TMP}/mvnd.tar.gz"
  tar -xzf "${TMP}/mvnd.tar.gz" -C "${TMP}"
  EXTRACTED=$(ls -d "${TMP}"/maven-mvnd-* 2>/dev/null | head -1)
  if [ -z "$EXTRACTED" ]; then
    echo "ERROR: Could not find extracted mvnd directory in ${TMP}" >&2
    rm -rf "${TMP}"
    exit 1
  fi
  mkdir -p "$(dirname "${MVND_HOME}")"
  mv "${EXTRACTED}" "${MVND_HOME}"
  rm -rf "${TMP}"
  echo "✓  mvnd ${MVND_VERSION} installed at ${MVND_HOME}"
else
  echo "✓  mvnd ${MVND_VERSION} already at ${MVND_BIN}"
fi

# ── Symlink mvnd to /usr/local/bin ────────────────────────────────────────
if [ ! -L /usr/local/bin/mvnd ] || [ "$(readlink /usr/local/bin/mvnd)" != "${MVND_BIN}" ]; then
  ln -sf "${MVND_BIN}" /usr/local/bin/mvnd
  echo "✓  /usr/local/bin/mvnd → ${MVND_BIN}"
fi

# ── Maven Proxy (conditional) ──────────────────────────────────────────────────
# Start local auth proxy if https_proxy/http_proxy is set (required for authenticated repos)
if [ -n "${https_proxy:-}${HTTPS_PROXY:-}${http_proxy:-}${HTTP_PROXY:-}" ]; then
  PROXY_SCRIPT="${BASH_SOURCE%/*}/../maven-proxy-v2.py"
  if [ -f "${PROXY_SCRIPT}" ] && ! pgrep -f "python3.*maven-proxy" >/dev/null 2>&1; then
    echo "⬆  Starting Maven proxy (127.0.0.1:3128)..."
    nohup python3 "${PROXY_SCRIPT}" >/dev/null 2>&1 &
    sleep 1
    if pgrep -f "python3.*maven-proxy" >/dev/null 2>&1; then
      echo "✓  Maven proxy started"
    else
      echo "⚠  Maven proxy failed to start (continuing anyway)"
    fi
  fi
fi
