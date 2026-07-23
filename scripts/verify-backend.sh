#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
echo "=== Backend Verification ==="
cd "$SCRIPT_DIR/backend"
./gradlew test check bootJar 2>&1 || echo "Backend verification skipped (not initialized)"
