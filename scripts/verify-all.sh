#!/usr/bin/env bash
set -euo pipefail
echo "=== Full Verification ==="
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
"$SCRIPT_DIR/scripts/verify-contract.sh"
"$SCRIPT_DIR/scripts/verify-backend.sh"
"$SCRIPT_DIR/scripts/verify-android.sh"
echo "=== All checks complete ==="
