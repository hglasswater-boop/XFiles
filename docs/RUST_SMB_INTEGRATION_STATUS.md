# Rust SMB Integration Status

## Current engine baseline

XFiles integrates the standalone Rust SMB engine from `hglasswater-boop/smb-io-rs` through the Android JNI boundary.

The XFiles #245 diagnostic existing-file edit integration candidate (Android JNI v8) is:

- smb-io-rs revision: `2a079c8fb3d1fc37237cbde96717c4a11a7440a6`
- engine roadmap state: Phase 9 rename/delete/mkdir merged in smb-io-rs PR #32; Android JNI v3 nativeMkdir merged in PR #34 and JNI v4 rename/deleteFile merged in PR #36; PR #44 signed FLUSH and PR #45 writable JNI v7 are merged; Phase 10 reconnect/durable-handle work remains outstanding
- Phase 9 validation at the merged PR head: Rust CI, Phase 9 File Management Integration, Samba Integration, Phase 6 Query Integration, Phase 7 Parallel READ, Phase 8 WRITE Integration, Broker Reconnect, Durable Reconnect, and Android JNI all succeeded
- XFiles PR #249 Debug CI (Mobile/TV, unit, lint, emulator), Rust SMB Preview and pin contract succeeded for JNI v6 exclusive createFile; **real NAS and WireGuard integration remain pending**. This v7 diagnostic branch is not yet validated by XFiles CI and does not represent full filesystem parity.

## XFiles runtime scope: migration is not complete

The original `RustSmbNative` JNI API (version 2) was video-oriented: `nativeOpenVideo`, `nativeLen`, `nativeReadAt`, `nativePrefetch`, `nativeSeek`, and `nativeClose`. The JNI v8 integration candidate retains the v7 video/list/mutations and writable stream API, and adds existing-file, non-truncating signed random-access read/write/setLength/flush/close. XFiles main already promotes **mkdir, rename, list/stat and exclusive createFile** to Rust-first in `auto`; this branch adds `openOut` in explicit `rust` diagnostic mode only. An unavailable/incompatible native library may be handled by SMBJ **before any mutation**; an actual Rust SMB error must never trigger a second mkdir through SMBJ. NAS/VPN verification is still required and is **not** a claim of full Rust filesystem parity.

| XFiles operation | Current runtime implementation | Rust migration gate |
| --- | --- | --- |
| Seekable reads for playback, thumbnails, storyboards | Rust via `SmbRandomAccessBackends` in auto/rust mode, with SMBJ fallback for missing/incompatible JNI in auto mode | Preserve playback, seek and cancellation behavior |
| `SmbFileSystem.list` / `stat` | Rust-first `auto` using paged JNI v8; `smbj` retains SMBJ; strict `rust` never falls back | Validate full XEntry metadata, many-page scans and NAS/WireGuard parity (Issue #244) |
| `storageSpace` / `directorySize` | SMBJ | Rust filesystem queries and recursive size handling are separate migration gates |
| `openIn` | SMBJ | Add streaming Rust read and validate read/cancel/error semantics |
| `openOut` | SMBJ in `auto`/`smbj`; native JNI v7 only for explicit diagnostic `rust` | Preserve OVERWRITE_IF, explicit FLUSH/CLOSE, no retries; real NAS speed vs SMBJ pipeline and WireGuard gate before auto |
| `createFile` | Rust-first `auto` using JNI v8; `smbj` retains SMBJ; strict `rust` never falls back | Exclusive FILE_CREATE and no-overwrite/no-replay verified on Rust Samba; XFiles CI/NAS pending (#245) |
| `SmbRandomAccessOutputFile` | SMBJ in `auto`/`smbj`; JNI v8 **only in explicit diagnostic `rust`** | FILE_OPEN no-truncate, EOF cache, 1 MiB chunks, ClipForge partial cleanup and error parity; NAS/WireGuard/throughput gate before auto |
| `mkdir` | Rust JNI v8 in `rust` and Rust-first `auto`; `smbj` retains SMBJ; `auto` falls back to SMBJ only before mutation when JNI is unavailable | Verify XFiles CI, errors, WireGuard and NAS after promotion; track #242 |
| `rename` / `moveWithinShare` | Rust-first `auto` with JNI v8; `smbj` retains SMBJ; strict `rust` never falls back | Preserve same-share checks, no-overwrite semantics, WireGuard preflight and non-replayed errors; real NAS tests pending |
| `delete` | SMBJ | Read-only `STATUS_CANNOT_DELETE` recovery and recursive directories must be preserved before auto Rust switch |
| Cross-share copy/move | Android-side streaming fallback | Keep source on failure, clean partial destination; optimize separately under #237 |

**Safety requirements for the next patches**

1. Keep the backend selection inside an internal adapter; do not scatter Rust/SMBJ checks across UI callers.
2. Do not silently retry a write, rename or delete after an ambiguous disconnect; never report success before the server operation is verified.
3. Preserve `XFileSystem.delete` semantics for directories (recursive at the application API) versus the Rust Phase 9 primitive, which deletes only an **empty** directory. A direct one-for-one swap would be incorrect.
4. Preserve saved-share `basePath`, path validation, authentication/anonymous semantics, and the WireGuard connection decision.
5. A same-server cross-share move is not automatically an atomic rename. Keep streaming fallback and source-protection semantics until #237 is separately implemented and tested.
6. Keep SMBJ available as a rollback path until Rust filesystem, writing, reconnect, security, and device/NAS quality gates pass. The v8 native library gate (`EXPECTED_API_VERSION = 8`) covers mutations, paginated listing, diagnostic output and non-truncating random-access JNI methods but does not prove filesystem interoperability.

## Progressive Rust promotion: mkdir (Issue #242)

- `auto` now prefers Rust for **mkdir**, matching the existing seekable-read policy. Explicit `rust` never falls back; explicit `smbj` stays on SMBJ.
- Before creating any directory, the Rust adapter confirms its JNI version is compatible. Only `RustSmbUnavailableException` raised during this check permits `auto` to select SMBJ. All protocol/authentication/transport errors and ambiguous mkdir failures from Rust propagate **without another mkdir attempt**.
- Saved `basePath` is included in the Rust share-relative path. A blank username uses anonymous authentication, avoiding an unnecessary password transfer to JNI.
- When a saved connection enables WireGuard, XFiles performs the existing non-mutating network selection/probe and optional tunnel UP/DOWN **before** issuing native mkdir. A temporary TCP probe is never the mkdir operation. An unsuccessful probe cannot be confused with a failed mutation or retried as one.
- The selected WireGuard policy is still bounded and may have its documented LAN/VPN cutover caveats; preserve the same selection used for SMBJ. `SmbWireGuardRetry.prepare` is a preflight-only adapter and does not run SMB authentication/mutation.
- After a successful native mkdir, `stat` uses the Rust-first paged directory listing (merged via XFiles PR #248). It can require a separate authenticated session/preflight but does not replay mkdir.
- `rename` and exclusive `createFile` have also been promoted Rust-first in `auto` (XFiles PRs #247 and #249). Recursive deletion, cross-share moves, `openIn`, storage-space queries and random-access writing remain SMBJ; `openOut` uses the new JNI v7 writer **only in explicit diagnostic `rust`**, while normal `auto` stays SMBJ until throughput/NAS validation.
- Promote in small PRs; CI gates precede main merge. User NAS and WireGuard findings are captured as follow-up fixes. **Issue #242 and overarching #239 remain open** pending real-device verification, parity and full migration quality gates.

## Next operation slice: rename and file-only delete JNI v4 (Issue #246)

- Rust engine PR #36 merged at `36f7aeff` with one-shot `nativeRename` and `nativeDeleteFile`. XFiles PR #247 merged Rust-first `rename` / `moveWithinShare` after green CI; current JNI v7 retains those methods. Real NAS/WireGuard parity remains pending.
- The first XFiles callers are `SmbFileSystem.rename` and same-share `moveWithinShare`, with **no overwrite**: both paths are in the same connected share and include the stored `basePath`; an SMB name collision propagates to the caller.
- `auto` may switch to SMBJ only on missing/incompatible JNI **before** the rename starts. Once Rust has been called, any network/protocol/status error is propagated, never replayed by SMBJ.
- `smbj` preserves existing behavior and does not run Rust preflight. `rust` uses native JNI without fallback. Rust rename calls preflight `wireGuard?.prepare(config)` before mutation.
- Existing `SmbFileSystem.delete` stays on SMBJ, even after Rust `nativeDeleteFile` exists: the app currently clears read-only attributes on `STATUS_CANNOT_DELETE`, and deletes directories **recursively**, whereas the upstream Phase 9 primitives do neither. Track compatibility under #196 and #246 before promoting deletion.
- JNI API version, library pin, symbol checks (all 3 ABIs), and Kotlin declaration must move together in one XFiles PR.
- The rename rollout is incremental, not a claim of complete Rust SMB parity. Rust-first list/stat and exclusive file creation are now in main, while `openIn`, production `openOut`, random-access write/truncate, storage space, recursive delete and cross-share transfers remain separately tracked under #244, #245, #237 and #239.
- Rust CI/Samba, XFiles Mobile/TV, unit/lint/emulator/Preview and NAS/WireGuard real-device tests are required. **Issue #246 and parent #239 stay Open** through real-NAS validation.

## Rust read-only directory listing: paged JNI (Issue #244)

Rust `smb-io-rs` #39 / PR #40 is **merged** at `f76b8274`, after all Rust/Samba/Android JNI CI passed. XFiles PR #248 merged API v5 and Rust-first `list/stat` into main after Debug CI, Mobile/TV, unit, lint, emulator, and Preview success. Real NAS/WireGuard parity remains pending.

- Kotlin calls `nativeOpenDirectory` exactly once, then `nativeReadDirectoryPage` until an XFL1 page with zero entries signals EOF, and always calls `nativeCloseDirectory` in `finally`.
- Rust keeps the same authenticated share connection and remote QUERY_DIRECTORY cursor open across pages. It returns *one* server page each time; 16 MiB is an upper bound **per page, not per folder**. No artificial total-file count or total-folder-byte limit.
- Page header `XFL1` and count are little-endian; each record uses a UTF-8 byte-length prefix followed by the exact name, raw attributes, u64 length and u64 creation/last-write FILETIME. Reject truncation, unknown version, trailing bytes, malformed UTF-8, Java Long overflow or impossible time conversion. Do **not** map invalid data to fake timestamps.
- An SMB error mid-scan is a failure, never mistaken for EOF; show the user an error instead of claiming a partial list is complete. In auto mode, fallback to SMBJ only if JNI is missing/incompatible **before** opening Rust; explicit Rust never masks failures.
- Filter `.`/`..`; preserve `hidden` (SMB attribute or dotfile), `isDir` (0x10), `size` (directory -1), `mtime` / `creationTime`, file MIME, `basePath`, stored anonymous/NTLM credentials, WireGuard preflight and correct XId children.
- Current `XFileSystem.list` returns a full `List<XEntry>` and therefore still allocates memory proportional to the actual folder size, just like the existing SMBJ path. The native JNI response itself no longer has a fixed whole-folder ceiling. Progressive UI virtualization is a separate performance optimization if real large NAS directories require it.
- Rust and XFiles CI (including signing-required Samba 1,200-entry multi-page test, Mobile/TV/unit/emulator) passed before the existing auto migration. Physical NAS/WireGuard and large-directory UI behavior remain pending; keep #244 / parent #239 **Open**.

## Exclusive new-file creation: next Rust JNI v6 slice (XFiles #245, Rust #41)

Rust PR #42 merged the exclusive JNI v6 `nativeCreateFile` at `2d860724` after Rust/Samba/Android CI. XFiles PR #249 promoted **only** `createFile` to Rust-first `auto` after successful Mobile/TV, unit/lint/emulator and Preview checks. The current JNI v7 continues supporting it; physical NAS/WireGuard parity is still pending.

- `createFile` uses SMB2 `FILE_CREATE`, creating **one empty regular file only if its name does not exist**. It must never use `OPEN_IF` or `OVERWRITE_IF`, and a collision must not overwrite existing data. Parent directories must already exist. The app retains safe-name validation and the configured share-relative `basePath`.
- Backend selection matches existing mkdir/rename mutation policy: `auto` uses Rust first, with SMBJ fallback solely on `RustSmbUnavailableException` from checking the loaded native version **before** calling the remote operation; strict `rust` has no fallback, explicit `smbj` does not touch Rust.
- WireGuard LAN/tunnel route preparation occurs before the native SMB session. If Rust CREATE was invoked and a network error occurs, its success is ambiguous: never retry it through SMBJ or report creation success. A successful create may use the already migrated Rust `stat` to build its `XEntry`.
- `openOut` differs: it uses `FILE_OVERWRITE_IF` and streams bytes. Random-access write also requires independent write-at/seek/truncate/flush/close semantics and is **not** covered by this createFile slice. Track them in #245; avoid implying complete write migration.
- The upstream CREATE collision/no-overwrite and JNI ABI gates and XFiles Kotlin/Mobile/TV/emulator/Preview gates succeeded for PR #249. The remaining CREATE gate is physical NAS/WireGuard and regression feedback; separate `openOut` diagnostic work is tracked below.
- Keep #245 and parent #239 Open through device parity and full WRITE migration; do not mark the issue done when one PR merges.

## Existing-file random editing diagnostic: JNI v8 (XFiles #245 / Rust #46)

Rust PR #47 merged signed SMB2 FILE_END_OF_FILE_INFORMATION at `96c3f63`; Rust PR #48 merged JNI v8 (`2a079c8fb3d1fc37237cbde96717c4a11a7440a6`) after Rust CI, mandatory-signing Samba same-handle shrink/extend/readback, Phase6/7/8/9 and Android JNI all 3 ABIs succeeded.

- The existing `SmbRandomAccessOutputFile.open` is different from `openOut`: it opens only **existing** regular files using `FILE_OPEN`, must **not** truncate at open and must reject missing files. In this slice only explicit `SMB_RANDOM_ACCESS_BACKEND=rust` selects JNI v8; production `auto` and explicit `smbj` continue the original SMBJ random-access editor. Playback/thumbnail Rust-read routing is unchanged.
- JNI v8 `nativeOpenRandom/nativeReadRandomAt/nativeWriteRandomAt/nativeSetRandomLength/nativeFlushRandom/nativeCloseRandom` uses one authenticated session per handle and 1MiB per operation. The Kotlin handle splits larger caller buffers without retrying an ambiguous WRITE, and treats short writes as errors. READ returns -1 at EOF or partial positive count; validation and negative position behavior should preserve the app's public facade.
- After successful write beyond EOF or `setLength`, Rust updates the known file EOF; failed/ambiguous mutation poisons the handle, and `close` always releases it, without further mutation. Never change a file's open disposition to `OVERWRITE_IF`.
- The current SMBJ implementation performs **best-effort deletion of stale ClipForge partials older than 24h** before opening the target. Preserve this cleanup in the diagnostic path; until it is Rust-native it may require a short-lived SMBJ cleanup connection. Do not claim full SMBJ removal.
- The existing public facade's methods, error behavior and close idempotency must remain compatible; test raw array offset/length, 0 and >1MiB writes, read EOF, error poisoning, close after failure, and explicit mode routing. Saved share `basePath`, anonymous/NTLM credentials, and WireGuard route decision must be checked before deployment.
- **Auto promotion blocked** pending comparable 8MiB workloads against SMBJ, real NAS/WireGuard reachability and ClipForge regression checks. `openIn`, `storageSpace`, recursive delete, cross-share moves and other unverified operations remain tracked. Keep #245, #239, Rust #46 Open.

## Next WRITE slice: native diagnostic openOut (XFiles #245 / Rust #43)

The Rust `smb-io-rs` PR #44 merged the mandatory-signed SMB2 FLUSH codec and client at `e87f8c633c4444db70901914630306516675ffc3`. PR #45 (JNI v7) merged at `b54949627e350b8e8648c6f2d4d88c37abab4a28`, exposing stateful `nativeOpenWritable/nativeWriteAt/nativeFlushWritable/nativeCloseWritable`, after Rust CI, signed Samba write/overwrite/readback and 3-ABI Android JNI passed. This XFiles branch is a **diagnostic integration only** until XFiles CI and real NAS/WireGuard and speed measurements pass.

- `SmbFileSystem.openOut` explicitly uses `FILE_OVERWRITE_IF`: it creates a new regular file or **truncates an existing file**, then returns a streaming `OutputStream`. It does **not** use createFile's exclusive `FILE_CREATE` disposition. A network disconnect during the open/truncate or WRITE can have an ambiguous result; do **not** retry it through SMBJ or repeat bytes automatically.
- In this slice **explicit `rust`** uses a signed persistent native writable handle. `auto` and explicit `smbj` continue using existing SMBJ pipelined async output; no premature speed regression in regular builds. Rust path prepares WireGuard LAN/tunnel route *before* opening/truncating a file. Saved `basePath` and credentials are share-relative and preserved.
- JNI writes are broken into at most 1MiB pieces, with exact buffer offset/length validation. Each Java write is positional; Kotlin increments the offset only after the native call confirms full chunk acceptance. Any failed or short write poisons the stream: subsequent write/flush is rejected, and close still releases the remote handle. The writer does not replay an ambiguous WRITE, and unconfirmed content must not be presented as successfully persisted.
- `flush()` is an explicit SMB2 FLUSH over the **same** open server handle. `close()` must flush if no previous failure, and must always attempt native CLOSE even if FLUSH fails; errors are surfaced, with a suppressed CLOSE error if necessary. Close must be idempotent on the Kotlin object and write-after-close must fail.
- Unit tests must cover valid/invalid ranges, zero-byte and multi-chunk writes, position, flush+close ordering, close-after-write-error, error suppression, explicit `rust` vs `auto`/`smbj`, route preflight and no fallback after Rust begins.
- Rust signed Samba test must verify `OVERWRITE_IF` truncates an existing larger file, multi-MiB writes, explicit flush, exact readback, invalid post-close handle. JNI symbol/version and all three ABI tests, Mobile/TV/lint/unit/emulator and Rust Preview must pass before merging diagnostic XFiles code.
- **Production auto promotion remains blocked** pending real NAS throughput vs the existing SMBJ pipeline (1MiB chunks, 8 writes in flight), WireGuard handoff and user-visible write/error parity. `SmbRandomAccessOutputFile` read/write/truncate and recursive deletion remain SMBJ. Parent #239 and write #245 remain **Open**.

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
