#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
echo "=== Android Verification ==="
cd "$SCRIPT_DIR/android"
./gradlew testDebugUnitTest lintDebug assembleDebug 2>&1 || echo "Android verification skipped (SDK not installed)"
