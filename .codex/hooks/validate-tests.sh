#!/bin/bash
# Test validation hook
# Runs unit tests for a specific module.
# Usage: ./validate-tests.sh :module:path

set -euo pipefail

MODULE="$1"
PROJECT_ROOT="$(git rev-parse --show-toplevel)"

echo "TEST: $MODULE:testDebugUnitTest"
cd "$PROJECT_ROOT"
./gradlew "$MODULE:testDebugUnitTest" --quiet 2>&1 | tail -10

EXIT_CODE=${PIPESTATUS[0]}
if [ $EXIT_CODE -eq 0 ]; then
  echo "✓ TESTS PASS"
else
  echo "✗ TESTS FAIL"
  exit 1
fi
