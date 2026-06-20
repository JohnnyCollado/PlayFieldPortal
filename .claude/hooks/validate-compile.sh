#!/bin/bash
# Post-edit validation hook
# Runs focused compilation on the module containing the edited file.
# Usage: ./validate-compile.sh <filepath>

set -euo pipefail

FILE="$1"
PROJECT_ROOT="$(git rev-parse --show-toplevel)"

# Extract module from file path
# Adapt the patterns below to match your project's module structure.
# e.g., feature/chat/src/... → :feature:chat
# e.g., domain/src/... → :domain

REL_PATH="${FILE#$PROJECT_ROOT/}"
MODULE=""

case "$REL_PATH" in
  feature/*/src/*) MODULE=":feature:$(echo "$REL_PATH" | cut -d/ -f2)" ;;
  library/*/src/*) MODULE=":library:$(echo "$REL_PATH" | cut -d/ -f2)" ;;
  domain/src/*) MODULE=":domain" ;;
  data/src/*) MODULE=":data" ;;
  core/*/src/*) MODULE=":core:$(echo "$REL_PATH" | cut -d/ -f2)" ;;
  shared/src/*) MODULE=":shared" ;;
  ui/*/src/*) MODULE=":ui:$(echo "$REL_PATH" | cut -d/ -f2)" ;;
  *) echo "SKIP: no module detected for $REL_PATH"; exit 0 ;;
esac

echo "COMPILE: $MODULE:compileCommonMainKotlinMetadata"
cd "$PROJECT_ROOT"
./gradlew "$MODULE:compileCommonMainKotlinMetadata" --quiet 2>&1 | tail -5

EXIT_CODE=${PIPESTATUS[0]}
if [ $EXIT_CODE -eq 0 ]; then
  echo "✓ PASS"
else
  echo "✗ FAIL"
  exit 1
fi
