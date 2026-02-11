#!/usr/bin/env bash
# Hook: PostToolUse (Write|Edit)
# Auto-lint .ts/.tsx files after edits
set -euo pipefail

FILE_PATH="${1:-}"

# Only lint TypeScript files
if [[ -z "$FILE_PATH" ]] || [[ ! "$FILE_PATH" =~ \.(ts|tsx)$ ]]; then
  exit 0
fi

# Resolve project root
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
APP_DIR="$PROJECT_DIR/apps/web"

# Only lint files inside apps/web/src
if [[ "$FILE_PATH" != "$APP_DIR/src/"* ]]; then
  exit 0
fi

# Run ESLint --fix silently; don't fail the hook if lint errors remain
cd "$APP_DIR"
npx eslint --fix "$FILE_PATH" 2>/dev/null || true
