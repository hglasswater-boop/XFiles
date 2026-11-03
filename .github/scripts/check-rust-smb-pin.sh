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

EXPECTED_STATUS_LINE=$(printf 'smb-io-rs revision: `%s`' "$PIN")
grep -Fq "$EXPECTED_STATUS_LINE" "$STATUS_DOC" || fail "status document does not match the shared pin"

# Until the XFiles operation entry points are actually migrated, document the JNI gap and
# do not mistake merged Rust engine functionality for an app-side migration completion.
grep -Fq 'XFiles runtime scope: migration is not complete' "$STATUS_DOC" ||
    fail "status document does not describe the outstanding app-side migration"
grep -Fq 'real NAS and WireGuard integration remain pending' "$STATUS_DOC" ||
    fail "Rust SMB status must preserve NAS/VPN validation as pending"
grep -Fq '| `mkdir` | Rust JNI v3 in `rust` and Rust-first `auto`;' "$STATUS_DOC" ||
    fail "status document must describe progressive Rust-first mkdir"
grep -Fq '| `rename` / `delete` / `moveWithinShare` | SMBJ |' "$STATUS_DOC" ||
    fail "status document must not claim other filesystem mutations are Rust-backed"


# One version and one engine SHA must advance together; all packaged ABI checks include mkdir.
grep -Fq 'private const val EXPECTED_API_VERSION = 3' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/rust/RustSmbNative.kt" ||
    fail "Kotlin JNI API is not v3"
grep -Fq 'nativeMkdir' "$BUILD_SCRIPT" || fail "Android build script does not verify mkdir JNI"
grep -Fq 'nativeMkdir' "$PREVIEW_WORKFLOW" || fail "Rust preview does not verify mkdir JNI"
grep -Fq 'catch (_: RustSmbUnavailableException)' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbMkdirBackend.kt" ||
    fail "auto mkdir may fall back only before mutation when native is unavailable"
grep -Fq 'wireGuard?.prepare(target.connection)' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbFileSystem.kt" ||
    fail "Rust mkdir must prepare VPN/LAN before mutation"

echo "Rust SMB pin contract OK: $PIN"
