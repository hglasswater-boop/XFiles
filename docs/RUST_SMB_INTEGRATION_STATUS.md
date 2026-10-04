# Rust SMB Integration Status

## Current engine baseline

XFiles integrates the standalone Rust SMB engine from `hglasswater-boop/smb-io-rs` through the Android JNI boundary.

The approved engine baseline is:

- smb-io-rs revision: `47de6020c11ba5614db28dc25ecb480b8d9a95bf`
- roadmap state: Phase 8 WRITE complete; Phase 9 rename/delete/mkdir next
- validation at the Phase 8 completion revision: Rust CI, Phase 8 WRITE Integration, Samba Integration, Phase 6 Query Integration, Phase 7 Parallel READ, Broker Reconnect, Durable Reconnect, and Android JNI all green

## XFiles runtime scope

Updating the engine revision does not by itself migrate every XFiles SMB operation to Rust.

The current XFiles production migration seam continues to use Rust for the existing seekable/random-access path used by local SMB media playback, thumbnails, storyboards, and related positional reads. SMBJ remains available for filesystem operations that have not yet crossed the migration gate.

Phase 8 WRITE capability in `smb-io-rs` is therefore a prerequisite for later XFiles write-path migration, not an instruction to switch `openOut`, copy, rename, delete, or mkdir in this revision-pin change.

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

This keeps normal Debug CI, emulator CI, release builds that use the shared build script, and Rust SMB Preview aligned to one committed engine revision.

## Promotion rule

A newer `smb-io-rs` revision may replace the pin only after the required Rust-side phase gates are green. XFiles then validates the pinned revision through its own Android JNI build, Mobile/TV build, unit tests, lint, emulator smoke tests, and Rust SMB Preview as applicable.

The pin must not be advanced merely because a Rust branch contains newer code.
