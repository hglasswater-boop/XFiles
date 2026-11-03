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
grep -Fq '| `mkdir` | Rust JNI v8 in `rust` and Rust-first `auto`;' "$STATUS_DOC" ||
    fail "status document must describe progressive Rust-first mkdir"
grep -Fq '| `rename` / `moveWithinShare` | Rust-first `auto` with JNI v8' "$STATUS_DOC" ||
    fail "status document must describe JNI v4 rename migration"
grep -Fq '| `SmbFileSystem.list` / `stat` | Rust-first `auto` using paged JNI v8;' "$STATUS_DOC" ||
    fail "status document must describe paged Rust-first list/stat"
grep -Fq '| `delete` | SMBJ |' "$STATUS_DOC" ||
    fail "delete must remain SMBJ until recursive and read-only compatibility is verified"


# One version and one engine SHA must advance together; all packaged ABI checks include mkdir.
grep -Fq 'private const val EXPECTED_API_VERSION = 8' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/rust/RustSmbNative.kt" ||
    fail "Kotlin JNI API is not v8"
grep -Fq 'nativeMkdir' "$BUILD_SCRIPT" || fail "Android build script does not verify mkdir JNI"
grep -Fq 'nativeMkdir' "$PREVIEW_WORKFLOW" || fail "Rust preview does not verify mkdir JNI"
for METHOD in nativeRename nativeDeleteFile nativeOpenDirectory nativeReadDirectoryPage nativeCloseDirectory nativeCreateFile nativeOpenWritable nativeWriteAt nativeFlushWritable nativeCloseWritable nativeOpenRandom nativeReadRandomAt nativeWriteRandomAt nativeSetRandomLength nativeFlushRandom nativeCloseRandom; do
    grep -Fq "$METHOD" "$BUILD_SCRIPT" || fail "Android build script does not verify $METHOD"
    grep -Fq "$METHOD" "$PREVIEW_WORKFLOW" || fail "Rust preview does not verify $METHOD"
done
grep -Fq 'catch (_: RustSmbUnavailableException)' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbMutationRouting.kt" ||
    fail "auto mutation may fall back only before a Rust operation starts"
grep -Fq 'wireGuard?.prepare(target.connection)' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbFileSystem.kt" ||
    fail "Rust mkdir must prepare VPN/LAN before mutation"
grep -Fq 'prepareNetwork()' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbMkdirBackend.kt" ||
    fail "Rust mkdir route must complete the network preflight before issuing JNI mutation"
grep -Fq 'prepareNetwork()' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbRenameBackend.kt" ||
    fail "Rust rename must prepare VPN/LAN route before JNI mutation"

grep -Fq 'fun <T> collectPages(' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbDirectoryBackend.kt" ||
    fail "directory listing must use page-wise native reads"
grep -Fq 'return resource.use {' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbDirectoryBackend.kt" ||
    fail "directory cursor must close even after an error"
grep -Fq 'SmbDirectoryBackend.list(' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbFileSystem.kt" ||
    fail "SMB filesystem does not route list/stat to the Rust page adapter"

grep -Fq 'SmbCreateFileBackend.create(' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbFileSystem.kt" ||
    fail "SMB createFile must use the one-shot Rust mutation adapter"
grep -Fq 'nativeCreateFile' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/rust/RustSmbNative.kt" ||
    fail "Kotlin JNI v8 must preserve exclusive createFile"

grep -Fq '| `openOut` | SMBJ in `auto`/`smbj`; native JNI v8 retains explicit diagnostic `rust`' "$STATUS_DOC" ||
    fail "output streaming must remain SMBJ in production auto until throughput parity"
grep -Fq 'if (mode != "rust") return smbj()' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbWriteBackend.kt" ||
    fail "production auto must preserve the SMBJ pipelined output backend"
grep -Fq 'SmbWriteBackend.open(' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbFileSystem.kt" ||
    fail "openOut must use the mode-gated writable adapter"
grep -Fq 'flushNative(handle)' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/rust/RustSmbOutputStream.kt" ||
    fail "writable stream close must flush before releasing the handle"

grep -Fq '| `SmbRandomAccessOutputFile` | SMBJ in `auto`/`smbj`; JNI v8 **only in explicit diagnostic `rust`**' "$STATUS_DOC" ||
    fail "random edit must remain SMBJ in auto until NAS parity"
grep -Fq 'if (mode != "rust") return smbj()' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbRandomEditRouting.kt" ||
    fail "random edit backend does not retain SMBJ auto behavior"
grep -Fq 'SmbRandomEditRouting.route(' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbRandomAccessOutputFile.kt" ||
    fail "random editor facade is not routed through the isolated backend"
grep -Fq 'cleanupForRust(target, connections)' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/SmbRandomAccessOutputFile.kt" ||
    fail "Rust diagnostic random editor dropped ClipForge cleanup"
grep -Fq 'nativeSetRandomLength' \
    "$ROOT_DIR/app/src/main/java/app/local1st/files/core/fs/rust/RustSmbNative.kt" ||
    fail "JNI v8 random truncate declaration missing"

echo "Rust SMB pin contract OK: $PIN"
