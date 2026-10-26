#!/usr/bin/env bash
# Prints the GitHub Release page text for a tag: the summary from
# release-notes/<tag>.md, then a changelog of every commit since the previous
# release (or every commit, for the first one).
set -euo pipefail
TAG="$1"
REPO="${GITHUB_REPOSITORY:-jawsh207/Weft}"
PREV=$(git describe --tags --abbrev=0 --match 'v*' "${TAG}^" 2>/dev/null || true)

if [ -f "release-notes/$TAG.md" ]; then
  cat "release-notes/$TAG.md"
  echo
fi
echo "## Changelog"
echo
if [ -n "$PREV" ]; then RANGE="$PREV..$TAG"; else RANGE="$TAG"; fi
git log --no-merges --format='- %s' "$RANGE"
echo
if [ -n "$PREV" ]; then
  echo "**Full changelog:** https://github.com/$REPO/compare/$PREV...$TAG"
else
  echo "**Full changelog:** https://github.com/$REPO/commits/$TAG"
fi
