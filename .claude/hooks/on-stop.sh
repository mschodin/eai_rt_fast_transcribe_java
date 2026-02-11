#!/usr/bin/env bash
# Hook: Stop
# Block stop if TypeScript errors exist in staged/modified files
set -euo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
APP_DIR="$PROJECT_DIR/apps/web"

# Skip if apps/web doesn't exist (e.g. running from repo root without app)
if [[ ! -d "$APP_DIR" ]]; then
  exit 0
fi

cd "$APP_DIR"

# Run typecheck; if it fails, output a warning but allow stop (exit 0)
# Change exit 0 to exit 1 below to hard-block stop on type errors
if ! npx tsc --noEmit --pretty 2>&1 | head -20; then
  echo "WARNING: TypeScript errors detected. Consider fixing before ending session."
fi

exit 0
