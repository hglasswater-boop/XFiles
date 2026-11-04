# Rust SMB Integration Status

## Current engine baseline

XFiles integrates the standalone Rust SMB engine from `hglasswater-boop/smb-io-rs` through the Android JNI boundary.

The current pinned Rust engine and historical XFiles JNI migration milestones are:

- smb-io-rs revision: `2b9e0cee184596ae221157a0fb640119f57124c5`
- engine roadmap state: Phase 9 rename/delete/mkdir merged in smb-io-rs PR #32; Android JNI v3 nativeMkdir merged in PR #34 and JNI v4 rename/deleteFile merged in PR #36; PR #44 signed FLUSH and PR #45 writable JNI v7 are merged; Phase 10 reconnect/durable-handle work remains outstanding
- Phase 9 validation at the merged PR head: Rust CI, Phase 9 File Management Integration, Samba Integration, Phase 6 Query Integration, Phase 7 Parallel READ, Phase 8 WRITE Integration, Broker Reconnect, Durable Reconnect, and Android JNI all succeeded
- XFiles PR #249 Debug CI (Mobile/TV, unit, lint, emulator), Rust SMB Preview and pin contract succeeded for JNI v6 exclusive createFile; **real NAS and WireGuard integration remain pending**. XFiles PR #251 Debug CI (Mobile/TV APK, unit tests, release lint, emulator), Rust SMB Preview and Rust Pin Contract succeeded at head `b7db7a795a002cc03d48c2936a7fa2a7e5114a22`; #251 merged into main as `e610a6f97541d906c86070150feb47561ceb9530`. XFiles PR #253 (`SmbFileSystem.openIn` Rust-first streaming) passed Debug CI (Mobile/TV APKs, unit tests, release lint, emulator), Rust SMB Preview, and Rust Pin Contract at head `4baadd35863b46be874156fdbdba8fcf47b81332`; merged into main as `f482b9759f5c1fb1b4e6908ce90110428a6caebc`. Physical NAS and WireGuard performance/correctness are still unverified; this is not full filesystem parity.

## XFiles runtime scope: migration is not complete

The original `RustSmbNative` JNI API (version 2) was video-oriented: `nativeOpenVideo`, `nativeLen`, `nativeReadAt`, `nativePrefetch`, `nativeSeek`, and `nativeClose`. JNI v8 retained the v7 video/list/mutations and writable stream API and added existing-file, non-truncating signed random-access read/write/setLength/flush/close. JNI v9 adds signed filesystem capacity and recursive directory-size queries, with app routing in PR #257. XFiles progressively promotes **mkdir, rename, list/stat, exclusive createFile, sequential openIn and capacity queries** to Rust-first in `auto`; `openOut` and non-truncating `SmbRandomAccessOutputFile` are diagnostic-only in explicit `rust` mode. An unavailable/incompatible native library may be handled by SMBJ **before any mutation**; an actual Rust SMB error must never trigger a second mkdir through SMBJ. NAS/VPN verification is still required and is **not** a claim of full Rust filesystem parity.

| XFiles operation | Current runtime implementation | Rust migration gate |
| --- | --- | --- |
| Seekable reads for playback, thumbnails, storyboards | Rust via `SmbRandomAccessBackends` in auto/rust mode, with SMBJ fallback for missing/incompatible JNI in auto mode | Preserve playback, seek and cancellation behavior |
| `SmbFileSystem.list` / `stat` | Rust-first `auto` using paged JNI v8; `smbj` retains SMBJ; strict `rust` never falls back | Validate full XEntry metadata, many-page scans and NAS/WireGuard parity (Issue #244) |
| `storageSpace` / `directorySize` | **PR #257:** Rust-first `auto` using signed JNI v9 `FileFsFullSizeInformation` and recursive paged size; explicit `smbj` or JNI-unavailable auto retain SMBJ | Rust engine/signed Samba CI passed; XFiles CI tracked in PR #257. Physical NAS correctness, quotas, WireGuard and throughput pending (#256) |
| `openIn` | **Merged PR #253:** Rust-first `auto`/strict `rust` through bounded positional `InputStream`; explicit `smbj` and JNI-unavailable auto fallback retain SMBJ | Debug CI, Rust Preview and pin checks passed; **real NAS + WireGuard correctness, cancellation and throughput remain pending** (#252) |
| `openOut` | SMBJ in `auto`/`smbj`; native JNI v8 retains explicit diagnostic `rust` | Preserve OVERWRITE_IF, explicit FLUSH/CLOSE, no retries; real NAS speed vs SMBJ pipeline and WireGuard gate before auto |
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

## JNI export verification and pipefail safety

XFiles must verify each required JNI symbol on all three Android ABI libraries against the same pinned Rust commit. A direct `llvm-nm | grep -q` pipeline under shell `set -o pipefail` is unsafe: `grep -q` may exit as soon as it finds a symbol, causing the still-writing `llvm-nm` to receive SIGPIPE and the pipeline to report failure despite an exported symbol. Read the complete `llvm-nm -D` output once **per ABI**, then perform the fixed-string required symbol checks on the captured text. Apply the same rule to both CI APK builds and the Rust SMB Preview; absence of any single required JNI entry point must still fail the build.

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

## Next operation slice: `openIn` Rust read-only streaming (XFiles #252)

Design and acceptance contract (documented before implementation). PR #253 is merged and the listed CI gates passed. The **real-device parity gates remain unverified**:

- On `auto`, prefer the already pinned read-only Rust `nativeOpenVideo` / `nativeReadAt` / `nativeClose` handle for `XFileSystem.openIn`. `rust` must be strict. Explicit `smbj` preserves the established SMBJ input stream; no Rust preflight or secret transfer. A missing/incompatible native library can select SMBJ in `auto` only **before opening Rust**. Authentication, transport, or SMB protocol errors after Rust is selected propagate without SMBJ replay.
- Reuse one authenticated Rust SMB handle for sequential reads. Convert positional read responses to `InputStream` semantics: `-1` only at EOF, `0` for zero-length valid requests, positive byte counts for data, and progress the logical offset by exactly the bytes returned. Neither a short read nor EOF is a reason to repeat/restart the open or copy.
- Bound individual native reads to 1 MiB. Provide buffering for single-byte reads without buffering a whole file; large transfers should avoid allocating buffers proportional to file size. Validate `read(byte[], off, len)`, `skip`, `available`, closed-state and offset overflow. `close()` must be idempotent and release the native handle even on read failure; never convert a protocol exception to a fake EOF.
- Preserve saved `basePath` and anonymous/NTLM handling, and perform `SmbWireGuardRetry.prepare` before creating a Rust network session (not after any read). SMBJ's existing `wireGuard.connect` behavior remains unchanged. Close on transfer cancellation through the existing `InputStream` lifecycle; do not add automatic retries after a non-replayable read failure.
- Unit/TDD: sequential/multi-chunk/single-byte reads, short read/EOF, zero-length and invalid range, `skip` and `available`, idempotent close, error propagation, strict/auto/smbj routing and VPN preflight. Mobile+TV, lint/unit/emulator, and Rust Preview/Pin Contract CI must pass. Real NAS copies (large and tiny files), SMB signing, WireGuard switching, cancellation/disconnect and SMBJ-vs-Rust throughput must be checked before claiming parity.
- **Issue #252 and overarching #239 stay OPEN** until all CI and device acceptance gates are met. Rust `openIn` alone does not complete `openOut`, random edit auto, deletion, storage-space queries or cross-share moves.

## Capacity migration integration: `storageSpace` and `directorySize` (XFiles #256 / smb-io-rs #49)

Rust engine PR #50 merged into `smb-io-rs/main` as `2b9e0cee184596ae221157a0fb640119f57124c5`. Its signed Samba query + recursive size integration, Rust fmt/test/clippy, and Android JNI build all passed. XFiles PR #257 pins this commit and integrates JNI v9 with `SmbCapacityBackend`. App CI and merge are separate review gates; **physical NAS, quotas, WireGuard and throughput remain unverified even after merge.**

**Original design gate, documented before tests/code:** JNI v8 had no filesystem capacity or recursive size query. This gate is addressed by the pinned JNI v9 engine and PR #257 routing; physical NAS parity remains pending. Explicit `smbj` or JNI-unavailable `auto` retains the original SMBJ path.

- **`storageSpace(entry)`:** synthetic SMB root `smb://` returns null without connecting. Every saved connection and nested entry asks the *underlying share* (using the configured basePath directory), not only that nested folder, for total capacity and **caller-available** free bytes (respect quotas), matching `DiskShare.shareInformation`. Return `StorageSpace(totalBytes,freeBytes)`; no guessed/free-zero value. SMB2 query must be signed, use `FileFsFullSizeInformation` (info type FILESYSTEM, class 7), reject malformed wire data and clamp unrepresentable >Long.MAX_VALUE to Long.MAX_VALUE. Preserve anonymous/NTLM, share and WireGuard preflight. In normal `auto` prefer Rust; fallback to SMBJ **only if JNI missing/incompatible before opening the SMB session**. Strict `rust` never falls back; explicit `smbj` avoids Rust.
- **`directorySize(entry)`:** non-directory entries and the synthetic root return null. Traverse the selected saved-share-relative directory and descendants without leaving configured `basePath`. Add **file EOF lengths** (not allocated bytes), skip `.`/`..` and **reparse-point directories** (Windows symlinks/junctions), saturate at Long.MAX_VALUE. Rust iteration uses one authenticated session, signed bounded directory pages, closes each child handle and the root handle on failure, and propagates transport/auth/permission errors instead of presenting a partial value. Android-side cancellation does not imply a native in-flight operation is interrupted; real-device test cancellation/timeout separately.
- JNI v9 adds `nativeDirectoryCapacity(directoryHandle): ByteArray` and `nativeDirectorySize(directoryHandle): Long` while retaining the JNI v8 API and handle namespace. Byte response must be exactly `XSS1` + 2x signed little-endian long (20 bytes). Kotlin decoder rejects wrong header/size/negative numbers. Both JNI calls share the existing `nativeOpenDirectory` and `nativeCloseDirectory` lifecycle and close with `finally`.
- **TDD and safety:** test the strict capacity decoder, route preflight/fallback/no replay, directory invalid/root behavior, large/empty tree, symlink skip/overflow and cursor close, Rust API/symbol pin. Never assume an enormous directory is an error because it exceeds a fixed total-size threshold. Only promote to normal auto after Rust CI, XFiles Mobile/TV tests/lint/emulator and JNI preview checks pass. NAS data, huge trees, quotas, WireGuard and throughput remain user-led physical acceptance gates; keep #256 / #244 / #239 open.

## PR #257 CI emulator disk budget (2026-11-04)

Debug CI run `41741138072` on head `900175b02d35c2c714a86823c9923adc2025d35c` passed signed Mobile/TV APK builds, Mobile/TV unit tests, release lint, Rust JNI pin; separate Rust SMB Preview `41740417525` passed. The Android emulator job failed **before executing tests**: AVD creation required 7,372.80 MB while the runner had 6,120.36 MB available, and the emulator reported `Not enough space to create userdata partition` then `emulator-5554 not found`. This is an emulator runner disk budget failure, **not** evidence of an Android app test failure or pass.

Minimal CI repair: set a **2,048 MB** test-only emulator data partition via the documented Android SDK `-partition-size 2048` launch option. This affects only the temporary CI AVD, not XFiles storage semantics or Android devices. Retain KVM/Android API 35 and run the identical instrumentation suite; require all original PR #257 CI checks green at one head before merge. If the emulator still fails, inspect logs rather than weaken/remove the test. Physical NAS acceptance and Issue #256 remain open.

## Release for user-led real NAS validation (XFiles #254)

The user will validate normal signed Mobile/TV binaries on a real NAS after release. Version `1.4.10-smb` is the new stable version; retain the existing `v1.4.9-smb` tag. That release workflow originally packaged JNI v8 for all three ABIs and signed the normal `auto` package; the current PR #257 engine generation is JNI v9. A successful published Release/APK is a packaging milestone, **not** successful NAS validation. Record any NAS, signing, transfer, WireGuard, cancel, disconnect or throughput regressions against #252/#239 and keep those Issues open. Normal `openOut` and random editing are still SMBJ; do not claim full Rust conversion.

## Settings diagnostics: actually executed capacity backend (Issue #258)

The user must be able to verify which engine **actually completed** capacity requests. A build-time `auto` choice or presence of a JNI library does **not** prove which engine ran.

- A dedicated **SMB backend** card in Settings displays the last successful operation for **share capacity** (`storageSpace`) and **recursive folder size** (`directorySize`), separately. Each row includes the actual Rust or SMBJ engine and the user-defined SMB connection name. Other filesystem operations remain uninstrumented and must never be implied to use that engine.
- Both rows start as **Not run** after a process restart, regardless of configured `auto`/`rust`/`smbj`. The card uses an in-memory `StateFlow` so new successful operations appear without reopening Settings. It does not persist stale information, access passwords or paths, or generate new network traffic merely by opening Settings.
- Report only **after** a successful native query *and* directory-handle close, or successful SMBJ result. Null return, protocol errors, WireGuard errors, invalid JNI, or failed close do not overwrite a previous successful backend. In `auto`, JNI unavailable before opening native SMB correctly records SMBJ **only if** SMBJ completes. Do not retry via SMBJ after Rust began.
- Multiple connections: show the **saved connection display name** for the latest successful operation, never assume that this applies to all SMB shares. Results for share capacity and folder size are independent. Values returned from UI caches do not create new execution records.
- Test-first: route callback after success, no callback on failed/nullable operations, fallback attribution, strict `rust` and `smbj`, independent operation rows, and StateFlow updates. Mobile/TV test, lint, emulator, release build and Rust pin/preview checks must pass before promoting to main. Real-device confirmation belongs to the user; keep Issues #258/#256/#239 open until verified.

## Open tracking

- XFiles #254: 1.4.10-smb release packaging and user-led NAS verification (open until gates are met).
- XFiles #252: merged Rust openIn streaming; NAS/WireGuard/cancellation validation remains.
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
