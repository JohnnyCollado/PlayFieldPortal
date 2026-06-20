#!/bin/bash
# Finalization hook — outputs git state and summary context
# Run at the end of the pipeline to produce final status.
# Usage: ./finalize-summary.sh

set -euo pipefail

PROJECT_ROOT="$(git rev-parse --show-toplevel)"
cd "$PROJECT_ROOT"

BRANCH=$(git branch --show-current)
BASE="main"

echo "═══════════════════════════════════════════"
echo " FINALIZATION SUMMARY"
echo "═══════════════════════════════════════════"
echo ""
echo "Branch: $BRANCH"
echo "Base:   $BASE"
echo ""

echo "── Recent commits (this branch vs $BASE) ──"
git log "$BASE".."$BRANCH" --oneline 2>/dev/null || git log --oneline -5
echo ""

echo "── Files changed (vs $BASE) ──"
git diff --stat "$BASE".."$BRANCH" 2>/dev/null || git diff --stat HEAD~1
echo ""

echo "── Working tree status ──"
git status --short
echo ""

echo "── Uncommitted changes ──"
UNCOMMITTED=$(git status --porcelain | wc -l | tr -d ' ')
if [ "$UNCOMMITTED" -gt 0 ]; then
  echo "⚠ $UNCOMMITTED uncommitted file(s)"
  git status --porcelain
else
  echo "✓ Working tree clean"
fi
echo ""
echo "═══════════════════════════════════════════"
