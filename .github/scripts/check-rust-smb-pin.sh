#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../.." && pwd)
PIN_FILE="$ROOT_DIR/.github/rust-smb-ref"
BUILD_SCRIPT="$ROOT_DIR/.github/scripts/build-rust-smb-android.sh"
PREVIEW_WORKFLOW="$ROOT_DIR/.github/workflows/rust-smb-preview.yml"
STATUS_DOC="$ROOT_DIR/docs/RUST_SMB_INTEGRATION_STATUS.md"

fail() {
    echo "Rust SMB pin contract failed: $*" >&2
    exit 1
}

[[ -f "$PIN_FILE" ]] || fail "missing .github/rust-smb-ref"
[[ $(wc -l < "$PIN_FILE") -eq 1 ]] || fail "pin file must contain exactly one newline-terminated line"
PIN=$(tr -d '\n' < "$PIN_FILE")
[[ "$PIN" =~ ^[0-9a-f]{40}$ ]] || fail "pin must be one lowercase 40-character Git SHA"

grep -Fq '.github/rust-smb-ref' "$BUILD_SCRIPT" || fail "build script does not read the shared pin"
grep -Fq '.github/rust-smb-ref' "$PREVIEW_WORKFLOW" || fail "preview workflow does not read the shared pin"

if grep -Eq '[0-9a-f]{40}' "$BUILD_SCRIPT"; then
    fail "build script contains an independent 40-character SHA"
fi
if grep -Eq '[0-9a-f]{40}' "$PREVIEW_WORKFLOW"; then
    fail "preview workflow contains an independent 40-character SHA"
fi

grep -Fq "smb-io-rs revision: \`$PIN\`" "$STATUS_DOC" || fail "status document does not match the shared pin"

echo "Rust SMB pin contract OK: $PIN"
