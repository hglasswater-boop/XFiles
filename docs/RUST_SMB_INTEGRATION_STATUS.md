# Rust SMB Integration Status

## Current engine baseline

XFiles integrates the standalone Rust SMB engine from `hglasswater-boop/smb-io-rs` through the Android JNI boundary.

The currently integrated XFiles engine (Phase 9 + paginated Android JNI v5) is:

- smb-io-rs revision: `f76b8274f27815bd6bc39d2201c7bca0dd23917d`
- engine roadmap state: Phase 9 rename/delete/mkdir merged in smb-io-rs PR #32; Android JNI v3 nativeMkdir merged in PR #34 and JNI v4 rename/deleteFile merged in PR #36; Phase 10 reconnect/durable-handle work remains outstanding
- Phase 9 validation at the merged PR head: Rust CI, Phase 9 File Management Integration, Samba Integration, Phase 6 Query Integration, Phase 7 Parallel READ, Phase 8 WRITE Integration, Broker Reconnect, Durable Reconnect, and Android JNI all succeeded
- XFiles PR #241 Android Debug CI (Mobile/TV, unit, lint, emulator) and Rust SMB Preview were successful; **real NAS and WireGuard integration remain pending**. The CI green result is not full Rust filesystem parity.

## XFiles runtime scope: migration is not complete

The original `RustSmbNative` JNI API (version 2) was video-oriented: `nativeOpenVideo`, `nativeLen`, `nativeReadAt`, `nativePrefetch`, `nativeSeek`, and `nativeClose`. The pinned-for-this-PR Rust engine exposes JNI v5 with mkdir, rename, file-only delete and paginated directory list. XFiles #242 promotes only **mkdir** to Rust-first in `auto` while retaining the explicit `rust` and `smbj` switches. An unavailable/incompatible native library may be handled by SMBJ **before any mutation**; an actual Rust SMB error must never trigger a second mkdir through SMBJ. NAS/VPN verification is still required and is **not** a claim of full Rust filesystem parity.

| XFiles operation | Current runtime implementation | Rust migration gate |
| --- | --- | --- |
| Seekable reads for playback, thumbnails, storyboards | Rust via `SmbRandomAccessBackends` in auto/rust mode, with SMBJ fallback for missing/incompatible JNI in auto mode | Preserve playback, seek and cancellation behavior |
| `SmbFileSystem.list` / `stat` | Rust-first `auto` using paged JNI v5; `smbj` retains SMBJ; strict `rust` never falls back | Validate full XEntry metadata, many-page scans and NAS/WireGuard parity (Issue #244) |
| `storageSpace` / `directorySize` | SMBJ | Rust filesystem queries and recursive size handling are separate migration gates |
| `openIn` / `openOut` / `createFile` | SMBJ | Add streaming/writable Rust handles, validate resource lifetime and truncation/create semantics |
| `SmbRandomAccessOutputFile` | SMBJ | Add Rust `read_at`, `write_at`, truncate, flush and close boundary with durability/error tests |
| `mkdir` | Rust JNI v5 in `rust` and Rust-first `auto`; `smbj` retains SMBJ; `auto` falls back to SMBJ only before mutation when JNI is unavailable | Verify XFiles CI, errors, WireGuard and NAS after promotion; track #242 |
| `rename` / `moveWithinShare` | Rust-first `auto` with JNI v5; `smbj` retains SMBJ; strict `rust` never falls back | Preserve same-share checks, no-overwrite semantics, WireGuard preflight and non-replayed errors; real NAS tests pending |
| `delete` | SMBJ | Read-only `STATUS_CANNOT_DELETE` recovery and recursive directories must be preserved before auto Rust switch |
| Cross-share copy/move | Android-side streaming fallback | Keep source on failure, clean partial destination; optimize separately under #237 |

**Safety requirements for the next patches**

1. Keep the backend selection inside an internal adapter; do not scatter Rust/SMBJ checks across UI callers.
2. Do not silently retry a write, rename or delete after an ambiguous disconnect; never report success before the server operation is verified.
3. Preserve `XFileSystem.delete` semantics for directories (recursive at the application API) versus the Rust Phase 9 primitive, which deletes only an **empty** directory. A direct one-for-one swap would be incorrect.
4. Preserve saved-share `basePath`, path validation, authentication/anonymous semantics, and the WireGuard connection decision.
5. A same-server cross-share move is not automatically an atomic rename. Keep streaming fallback and source-protection semantics until #237 is separately implemented and tested.
6. Keep SMBJ available as a rollback path until Rust filesystem, writing, reconnect, security, and device/NAS quality gates pass. The v5 native library gate (`EXPECTED_API_VERSION = 5`) covers the mutation and paginated listing JNI methods but does not prove filesystem interoperability.

## Progressive Rust promotion: mkdir (Issue #242)

- `auto` now prefers Rust for **mkdir**, matching the existing seekable-read policy. Explicit `rust` never falls back; explicit `smbj` stays on SMBJ.
- Before creating any directory, the Rust adapter confirms its JNI version is compatible. Only `RustSmbUnavailableException` raised during this check permits `auto` to select SMBJ. All protocol/authentication/transport errors and ambiguous mkdir failures from Rust propagate **without another mkdir attempt**.
- Saved `basePath` is included in the Rust share-relative path. A blank username uses anonymous authentication, avoiding an unnecessary password transfer to JNI.
- When a saved connection enables WireGuard, XFiles performs the existing non-mutating network selection/probe and optional tunnel UP/DOWN **before** issuing native mkdir. A temporary TCP probe is never the mkdir operation. An unsuccessful probe cannot be confused with a failed mutation or retried as one.
- The selected WireGuard policy is still bounded and may have its documented LAN/VPN cutover caveats; preserve the same selection used for SMBJ. `SmbWireGuardRetry.prepare` is a preflight-only adapter and does not run SMB authentication/mutation.
- After a successful native mkdir, the existing `stat` still uses SMBJ. This can cause a second connection/preflight but must not redo the mutation. Later list/stat migration will remove the extra client session.
- `rename`, `delete`, `createFile`, `openOut`, recursive directory deletion, cross-share moves, and random-access writing stay on SMBJ until independently implemented and tested.
- Promote in small PRs; CI gates precede main merge. User NAS and WireGuard findings are captured as follow-up fixes. **Issue #242 and overarching #239 remain open** pending real-device verification, parity and full migration quality gates.

## Next operation slice: rename and file-only delete JNI v4 (Issue #246)

- Rust engine PR #36 was merged at `36f7aeff`; it adds one-shot `nativeRename` and `nativeDeleteFile` on top of Phase 9. Rust CI, Samba, Phase 9 and Android JNI (3 ABIs) passed. This XFiles branch promotes the pin and expected JNI version together; Android CI and real NAS tests remain pending.
- The first XFiles callers are `SmbFileSystem.rename` and same-share `moveWithinShare`, with **no overwrite**: both paths are in the same connected share and include the stored `basePath`; an SMB name collision propagates to the caller.
- `auto` may switch to SMBJ only on missing/incompatible JNI **before** the rename starts. Once Rust has been called, any network/protocol/status error is propagated, never replayed by SMBJ.
- `smbj` preserves existing behavior and does not run Rust preflight. `rust` uses native JNI without fallback. Rust rename calls preflight `wireGuard?.prepare(config)` before mutation.
- Existing `SmbFileSystem.delete` stays on SMBJ, even after Rust `nativeDeleteFile` exists: the app currently clears read-only attributes on `STATUS_CANNOT_DELETE`, and deletes directories **recursively**, whereas the upstream Phase 9 primitives do neither. Track compatibility under #196 and #246 before promoting deletion.
- JNI API version, library pin, symbol checks (all 3 ABIs), and Kotlin declaration must move together in one XFiles PR.
- The rename rollout is incremental, not a claim that complete Rust SMB operation parity has been reached. Read/list, create, write, recursive delete and cross-share transfers remain separately tracked under #244, #245, #237 and #239.
- Rust CI/Samba, XFiles Mobile/TV, unit/lint/emulator/Preview and NAS/WireGuard real-device tests are required. **Issue #246 and parent #239 stay Open** through real-NAS validation.

## Rust read-only directory listing: paged JNI (Issue #244)

Rust `smb-io-rs` #39 / PR #40 is **merged** at `f76b8274`, after all Rust/Samba/Android JNI CI passed. XFiles PR #248 merged API v5 and Rust-first `list/stat` into main after Debug CI, Mobile/TV, unit, lint, emulator, and Preview success. Real NAS/WireGuard parity remains pending.

- Kotlin calls `nativeOpenDirectory` exactly once, then `nativeReadDirectoryPage` until an XFL1 page with zero entries signals EOF, and always calls `nativeCloseDirectory` in `finally`.
- Rust keeps the same authenticated share connection and remote QUERY_DIRECTORY cursor open across pages. It returns *one* server page each time; 16 MiB is an upper bound **per page, not per folder**. No artificial total-file count or total-folder-byte limit.
- Page header `XFL1` and count are little-endian; each record uses a UTF-8 byte-length prefix followed by the exact name, raw attributes, u64 length and u64 creation/last-write FILETIME. Reject truncation, unknown version, trailing bytes, malformed UTF-8, Java Long overflow or impossible time conversion. Do **not** map invalid data to fake timestamps.
- An SMB error mid-scan is a failure, never mistaken for EOF; show the user an error instead of claiming a partial list is complete. In auto mode, fallback to SMBJ only if JNI is missing/incompatible **before** opening Rust; explicit Rust never masks failures.
- Filter `.`/`..`; preserve `hidden` (SMB attribute or dotfile), `isDir` (0x10), `size` (directory -1), `mtime` / `creationTime`, file MIME, `basePath`, stored anonymous/NTLM credentials, WireGuard preflight and correct XId children.
- Current `XFileSystem.list` returns a full `List<XEntry>` and therefore still allocates memory proportional to the actual folder size, just like the existing SMBJ path. The native JNI response itself no longer has a fixed whole-folder ceiling. Progressive UI virtualization is a separate performance optimization if real large NAS directories require it.
- Rust and Android CI, real signing-required Samba with 1,200 files over multiple pages, Mobile/TV/unit/emulator, NAS/WireGuard and error handling must pass before production auto migration. Keep #244 / parent #239 **Open** through real-device validation.

## Exclusive new-file creation: next Rust JNI v6 slice (XFiles #245, Rust #41)

The Rust engine PR #42 introduces a new one-shot `nativeCreateFile` method on JNI v6. It implements `SmbFileSystem.createFile` only. The engine is **not pinned/promoted by XFiles yet**; until Rust CI/Samba/Android JNI passes and XFiles commits the new pin/version together, normal builds use existing SMBJ for new-file creation.

- `createFile` uses SMB2 `FILE_CREATE`, creating **one empty regular file only if its name does not exist**. It must never use `OPEN_IF` or `OVERWRITE_IF`, and a collision must not overwrite existing data. Parent directories must already exist. The app retains safe-name validation and the configured share-relative `basePath`.
- Backend selection matches existing mkdir/rename mutation policy: `auto` uses Rust first, with SMBJ fallback solely on `RustSmbUnavailableException` from checking the loaded native version **before** calling the remote operation; strict `rust` has no fallback, explicit `smbj` does not touch Rust.
- WireGuard LAN/tunnel route preparation occurs before the native SMB session. If Rust CREATE was invoked and a network error occurs, its success is ambiguous: never retry it through SMBJ or report creation success. A successful create may use the already migrated Rust `stat` to build its `XEntry`.
- `openOut` differs: it uses `FILE_OVERWRITE_IF` and streams bytes. Random-access write also requires independent write-at/seek/truncate/flush/close semantics and is **not** covered by this createFile slice. Track them in #245; avoid implying complete write migration.
- Upstream gate: create-only Rust CI, signed Samba collision/no-overwrite verification, all Android JNI ABI symbol checks. App gate: Kotlin adapter and tests, correct pin/JNI version, Mobile/TV, emulator, Preview, and physical NAS/WireGuard.
- Keep #245 and parent #239 Open through device parity and full WRITE migration; do not mark the issue done when one PR merges.

## Open tracking

- XFiles #239: overarching Rust migration; **must remain open until all quality gates and user verification**.
- XFiles #196: SMB delete failure and `STATUS_CANNOT_DELETE` behavior.
- XFiles #237: same-server cross-share transfers.
- smb-io-rs #22: Phase 9 tracking remains open despite merged implementation.
- smb-io-rs #33: initial Android JNI mkdir rollout remains open.
- XFiles #242: production-auto mkdir promotion and real-device validation remain open.
- XFiles #244 / #245 / #246: metadata, write, and rename/delete migration remain open.
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
