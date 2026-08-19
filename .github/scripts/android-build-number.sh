#!/usr/bin/env sh
set -eu

# Android versionCode must not go backwards across independently numbered GitHub workflows.
# Derive it from the checked-out commit's timestamp instead of github.run_number, which is
# scoped per workflow. Epoch minutes stay well below Android's versionCode limit for centuries
# and give every workflow the same build number for the same commit.
COMMIT_EPOCH_SECONDS=$(git show -s --format=%ct HEAD)
BUILD_NUMBER=$((COMMIT_EPOCH_SECONDS / 60))

# Guard against accidentally falling back to the old small per-workflow run-number scheme.
if [ "$BUILD_NUMBER" -lt 1000000 ] || [ "$BUILD_NUMBER" -gt 2100000000 ]; then
  echo "Invalid Android build number: $BUILD_NUMBER" >&2
  exit 1
fi

printf '%s\n' "$BUILD_NUMBER"
