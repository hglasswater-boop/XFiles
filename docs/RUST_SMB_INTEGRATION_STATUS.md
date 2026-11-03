# Rust SMB Integration Status

## Current engine baseline

XFiles integrates the standalone Rust SMB engine from `hglasswater-boop/smb-io-rs` through the Android JNI boundary.

The Phase 9 engine candidate is:

- smb-io-rs revision: `513677ecc9f919670f9624d542dff977712a40b6`
- engine roadmap state: Phase 9 rename/delete/mkdir merged in smb-io-rs PR #32; Phase 10 reconnect/durable-handle work remains outstanding
- Phase 9 validation at the merged PR head: Rust CI, Phase 9 File Management Integration, Samba Integration, Phase 6 Query Integration, Phase 7 Parallel READ, Phase 8 WRITE Integration, Broker Reconnect, Durable Reconnect, and Android JNI all succeeded
- XFiles Android integration and device/NAS validation for this new pin: **pending**. A successful Rust-side workflow is not evidence that XFiles has migrated file-management calls.

## XFiles runtime scope: migration is not complete

The currently deployed `RustSmbNative` JNI API (version 2) is video-oriented: `nativeOpenVideo`, `nativeLen`, `nativeReadAt`, `nativePrefetch`, `nativeSeek`, and `nativeClose`. The Rust SMB engine implements filesystem mutations, but current XFiles Kotlin filesystem calls remain SMBJ. This branch introduces the **diagnostic-only** mkdir experiment described below; it is not production parity.

| XFiles operation | Current runtime implementation | Rust migration gate |
| --- | --- | --- |
| Seekable reads for playback, thumbnails, storyboards | Rust via `SmbRandomAccessBackends` in auto/rust mode, with SMBJ fallback for missing/incompatible JNI in auto mode | Preserve playback, seek and cancellation behavior |
| `SmbFileSystem.list` / `stat` / `storageSpace` / `directorySize` | SMBJ | Add filesystem JNI DTOs, test path and metadata parity |
| `openIn` / `openOut` / `createFile` | SMBJ | Add streaming/writable Rust handles, validate resource lifetime and truncation/create semantics |
| `SmbRandomAccessOutputFile` | SMBJ | Add Rust `read_at`, `write_at`, truncate, flush and close boundary with durability/error tests |
| `mkdir` | Diagnostic-only `rust`: planned JNI Phase 9 path; `auto` and `smbj`: SMBJ | Test native API v3, errors, WireGuard and NAS before normal rollout |
| `rename` / `delete` / `moveWithinShare` | SMBJ | Route via Rust later, retaining recursive directory and collision semantics |
| Cross-share copy/move | Android-side streaming fallback | Keep source on failure, clean partial destination; optimize separately under #237 |

**Safety requirements for the next patches**

1. Keep the backend selection inside an internal adapter; do not scatter Rust/SMBJ checks across UI callers.
2. Do not silently retry a write, rename or delete after an ambiguous disconnect; never report success before the server operation is verified.
3. Preserve `XFileSystem.delete` semantics for directories (recursive at the application API) versus the Rust Phase 9 primitive, which deletes only an **empty** directory. A direct one-for-one swap would be incorrect.
4. Preserve saved-share `basePath`, path validation, authentication/anonymous semantics, and the WireGuard connection decision.
5. A same-server cross-share move is not automatically an atomic rename. Keep streaming fallback and source-protection semantics until #237 is separately implemented and tested.
6. Keep SMBJ available as a rollback path until Rust filesystem, writing, reconnect, security, and device/NAS quality gates pass. The native library's current version gate (`EXPECTED_API_VERSION = 2`) proves only the read-oriented JNI contract.

## Diagnostic mkdir migration — planned and not production-ready

- Upstream: smb-io-rs #33 / PR #34 provides a one-operation `nativeMkdir` JNI method. That Rust code is not yet approved by XFiles until Rust CI and Android JNI checks pass.
- App adapter: centralize the backend switch so only explicit `rust` diagnostic builds call `nativeMkdir`. `auto` and `smbj` keep SMBJ and are not allowed to retry a failed Rust mutation.
- Callers retain share-relative paths including saved `basePath`, Unicode names, and anonymous/domain credentials. The Rust request must validate unsafe paths before connecting.
- Do not call existing `SmbWireGuardRetry.connect` with mkdir as its callback: retrying after an ambiguous result could create duplicate side effects.
- `stat` after mkdir still uses SMBJ until separately migrated. Success of this slice does not remove SMBJ dependencies.
- Native API must move from v2 to v3 in the same PR that advances the Rust engine pin, otherwise normal auto-mode Rust reads would regress to SMBJ due to version mismatch.
- Before switching `auto` to Rust: implement safe VPN connection preparation, real NAS tests, shared session reuse, and correctness/throughput comparisons. **Keep #239 open**.

## Open tracking

- XFiles #239: overarching Rust migration; **must remain open until all quality gates and user verification**.
- XFiles #196: SMB delete failure and `STATUS_CANNOT_DELETE` behavior.
- XFiles #237: same-server cross-share transfers.
- smb-io-rs #22: Phase 9 tracking remains open despite merged implementation.
- smb-io-rs #33: initial Android JNI mkdir rollout remains open.
- smb-io-rs #23 / #28: Phase 10 and whole-engine roadmap.

## Revision ownership

The Rust SMB engine revision is repository configuration and must have exactly one source of truth:

```text
.github/rust-smb-ref
```

Rules:

1. The file contains exactly one lowercase 40-character Git commit SHA plus a trailing newline.
2. `.github/scripts/build-rust-smb-android.sh` reads this file when no explicit `RUST_SMB_REF` override is supplied.
3. `.github/workflows/rust-smb-preview.yml` reads the same file before checking out `smb-io-rs`.
4. Workflow files and build scripts must not carry an independent fallback SHA.
5. An explicit `RUST_SMB_REF` environment override remains a build-input override for diagnostics; it is not another repository default.

## Promotion rule

The engine revision can be promoted only after its required Rust-side phase gates are green. XFiles must then validate the pinned revision through Android JNI build/symbol verification, Mobile/TV build, unit tests, lint, emulator smoke tests, and Rust SMB Preview as applicable.

No file-management operation may be described as Rust-backed in XFiles until its JNI boundary, Kotlin routing, and app-side tests are delivered. Do not remove SMBJ merely because engine PR #32 has merged. This document and #239 remain the record of unchecked quality gates.
