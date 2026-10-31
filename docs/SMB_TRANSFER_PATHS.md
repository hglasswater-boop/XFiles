# SMB transfer paths: same share vs. cross-share

Status: observed in XFiles main at 2026-10-31; implementation tracked by [#237](https://github.com/hglasswater-boop/XFiles/issues/237).

## Current behavior

XFiles uses SMBJ for normal filesystem operations. The Rust engine is currently used for selected positional/media reads; see [Rust SMB integration status](RUST_SMB_INTEGRATION_STATUS.md).

| Operation | Current route | What to expect |
| --- | --- | --- |
| Move within one SMB share, free destination name | `DefaultOperationEngine.tryFastRename` → `SmbFileSystem.moveWithinShare` → SMB rename | No file-body transfer; usually quick |
| Move between different SMB shares, even at the same host/port | `moveWithinShare` returns `false`; `copyTree` reads source with `openIn`, writes destination with `openOut`, and deletes source after a successful copy | File bytes pass through Android; time is proportional to size and transfer speed |
| Copy, including within one share | `copyTree` → `copyFile` streaming | File bytes pass through Android |
| Different servers | Streaming copy; move deletes source only after copy success | Network transfer required |

Saved SMB entries have both `share` and optional `basePath`. Different saved entries can still refer to one SMB share: `sameShare` compares host, port and share, not the displayed connection name. Conversely, identical reported capacities do **not** prove that different shares address the same filesystem/volume.

The XFiles UI showing `\\server\video_a` and `\\server\video` means two different share names. A rename cannot simply use a destination path in a different share. The present client does not use an alternate server-side copy path for this case.

## Safety invariants

- For a move, delete the source only **after** the destination copy completes successfully.
- On cancel or failure, remove an incomplete destination; never silently delete the source.
- Never infer a safe server-side rename from hostname alone or from equal storage-space statistics.
- Preserve conflict handling (skip, overwrite, rename), cancellation and progress reporting.
- A successful server-side **copy** is not necessarily instantaneous: the NAS may still need to copy blocks internally; no zero-cost clone is guaranteed.

## Follow-up implementation design (#237)

An optional SMB2/3 server-side copy path could avoid routing file data through Android by requesting a source resume key (`FSCTL_SRV_REQUEST_RESUME_KEY`) and copying data through the destination handle (`FSCTL_SRV_COPYCHUNK` or `FSCTL_SRV_COPYCHUNK_WRITE`). Treat this as capability-dependent, not universally supported.

The SMBJ 0.14.0 `File.remoteCopyTo` helper requires the same `DiskShare` **instance**, so it cannot be used directly across XFiles' separately mounted share entries. A future cross-share implementation must keep both handles in a compatible authenticated SMB session, verify protocol/server support, respect copy-chunk size limits, handle partial/unsupported responses, and fall back safely to the existing streaming path. It must not rely on undocumented reflection or assume that one share's server supports another share's operations.

Before enabling the fast path, add tests for same share, cross-share on one server, different server, mismatched credentials, unsupported IOCTL, partial copy, cancellation, conflict policies and source preservation. Benchmark against streaming on the same NAS; an Android build alone cannot verify that a particular NAS accepts cross-share COPYCHUNK.

## Manual reproduction

1. On the same NAS, add `\\192.168.0.130\video_a` and `\\192.168.0.130\video` as distinct connections.
2. Move a disposable test file within `video_a` and observe the quick rename path.
3. Move a disposable test file from `video_a` to `video`. In the current implementation, observe copy progress before the source disappears.
4. Repeat with a medium/large disposable file, noting source/destination paths, elapsed time, whether the Android screen was on, and any error. Do **not** test on the only copy of valuable data.

## References

- [MS-SMB2 server-side data copy](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/eaa129d1-fca4-46b1-8ccd-b91f3e1d2d80)
- [SMBJ issue #175](https://github.com/hierynomus/smbj/issues/175)
