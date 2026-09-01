#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# 高德 Web/Android SDK key 形态（32 位十六进制）与彩云 token 形态
PATTERNS=(
  'amap'
  'caiyun'
  'x-cy-token'
  'x-cy-signature'
  'x-cy-timestamp'
  'POSTGRES_PASSWORD'
  'REDIS_PASSWORD'
)
BLOCKERS=(
  '[0-9a-fA-F]{32}'
  'AKIA[0-9A-Z]{16}'
)

exclude=(
  --exclude-dir=.git
  --exclude-dir=build
  --exclude-dir=.gradle
  --exclude-dir=.idea
  --exclude-dir=node_modules
)

status=0
for p in "${BLOCKERS[@]}"; do
  if grep -rnE "$p" "${exclude[@]}" --include="*.kt" --include="*.kts" --include="*.md" --include="*.xml" --include="*.toml" --include="*.json" --include="*.sh" --include="*.yml" --include="*.yaml" . 2>/dev/null \
      | grep -v '/schemas/' \
      | grep -v 'V1ToV2MigrationTest.kt' \
      | grep -v '/qa/README.md'; then
    echo "ERROR: possible secret pattern matched: $p" >&2
    status=1
  fi
done

for p in "${PATTERNS[@]}"; do
  if grep -rniE "$p" "${exclude[@]}" --include="*.kt" --include="*.kts" --include="*.md" --include="*.xml" --include="*.toml" --include="*.json" --include="*.sh" --include="*.yml" --include="*.yaml" . 2>/dev/null | grep -v 'SPEC.md' | grep -v 'IMPLEMENTATION_TASKS.md' | grep -v 'README.md' | grep -v 'SECURITY.md' | grep -v 'docs/configuration.md'; then
    echo "WARNING: keyword matched outside docs (review): $p" >&2
  fi
done

exit "$status"
