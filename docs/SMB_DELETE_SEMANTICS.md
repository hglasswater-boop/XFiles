# SMB delete semantics

Status: product/implementation contract for SMB file and directory deletion (Issue #279)

## Scope

This document defines `XFileSystem.delete()` for regular files and directories on SMB shares. The current filesystem mutation path is SMBJ; directory listing may separately use Rust. The Pure Rust SMB backend must preserve these externally visible semantics when filesystem mutations migrate to Rust.

## Protocol fact behind `STATUS_CANNOT_DELETE`

XFiles deletes an SMB file by asking the server to mark the opened file for deletion (`FileDispositionInformation`). Under the Windows file-system protocol rules, when `DeletePending = TRUE` and `FILE_ATTRIBUTE_READONLY` is set, the server must fail that operation with `STATUS_CANNOT_DELETE (0xC0000121)`.

Therefore a `STATUS_CANNOT_DELETE` returned by the delete `SET_INFO` path is not treated as a generic permission error. XFiles may recover only after confirming that the target currently has the read-only attribute.

References:

- Microsoft [MS-FSA] `FileDispositionInformation` processing rules.
- Microsoft [MS-FSCC] `FileBasicInformation` and file-attribute rules.

## XFiles behavior

For a regular SMB file:

1. Attempt the ordinary delete first. Successful deletes incur no extra metadata round trips.
2. If the delete fails with anything other than `STATUS_CANNOT_DELETE`, propagate the failure unchanged.
3. On `STATUS_CANNOT_DELETE`, query `FileBasicInformation`.
4. If `FILE_ATTRIBUTE_READONLY` is not set, propagate the original delete failure unchanged. Do not guess at another cause and do not mutate attributes.
5. If `FILE_ATTRIBUTE_READONLY` is set, clear only that bit and preserve all other settable attribute bits.
   - Use `FILE_ATTRIBUTE_NORMAL` when clearing read-only would otherwise produce an attribute value of zero, because zero means "do not change file attributes" in `FileBasicInformation`.
   - Do not change creation/access/write/change timestamps while clearing the attribute.
6. Retry the delete exactly once.
7. If that retry fails, best-effort restore the original file attributes before surfacing the retry failure. Restoration failure may be attached as secondary diagnostic information but must not replace the delete failure.

This is a narrow recovery path, not a general retry loop. Delete remains a non-idempotent mutation and must never be blindly replayed after an ambiguous transport failure.

## Directory deletion

- The existing UI requires explicit confirmation before any delete; cancelling never starts the operation. The confirmation applies to all descendants of a selected directory.
- Delete the selected directory's contents depth-first, files before their parent folders, then remove the now-empty selected directory. Empty directories must also work.
- Every regular file encountered, including inside nested directories, uses the **same read-only recovery** as deleting a single file. SMBJ's `DiskShare.rmdir(path, true)` recursively calls `rm` and bypasses XFiles' file recovery, so it must not be used for this operation.
- Ignore `.` and `..` directory enumeration entries. Reject unsafe child names (blank, path separators, or other traversal names), rather than letting them escape the selected subtree.
- Never walk directory reparse points/junctions; remove the link entry itself without traversing its target. Check the selected root as well.
- Do not issue a recursive delete for the configured connection root or the SMB share root. Deleting a saved connection uses the distinct connection-management UI and does not modify NAS contents.
- Stop at the first failure. Do not report success or blindly retry non-idempotent mutations; already removed children may remain removed, and the filesystem should be refreshed to reflect partial progress.
- Do not swallow ACL, sharing, transport, or directory-not-empty failures. Preserve the SMB cause for the UI's existing error handling.
- Keep one authenticated share open for the operation; use an explicit traversal stack rather than unbounded recursion.

## Error presentation

Low-level SMB diagnostics remain useful for logs, but the filesystem layer must preserve the meaningful NTSTATUS and operation. The UI must not infer that `STATUS_CANNOT_DELETE` always means missing ACL permissions: for the `FileDispositionInformation` path it specifically requires read-only handling as described above.

## Tests required before implementation

Regression coverage must prove:

- an ordinary file delete succeeds without attribute mutation;
- non-`STATUS_CANNOT_DELETE` failures are propagated without recovery;
- `STATUS_CANNOT_DELETE` with no read-only attribute is propagated without mutation;
- a read-only file has only `FILE_ATTRIBUTE_READONLY` cleared, then delete is retried once;
- a read-only-only attribute set uses `FILE_ATTRIBUTE_NORMAL` rather than zero;
- if the retry fails, restoration uses the original attribute value;
- an empty directory is deleted;
- populated and nested directories are removed in postorder and every descendant file passes through the file-delete recovery;
- `.`/`..` entries are ignored and an unsafe child path fails closed;
- directory reparse points are never traversed;
- a failure interrupts deletion and propagates the original error;
- deleting the connection root is rejected without touching the server.

The Rust backend must carry equivalent protocol-level tests before it replaces the SMBJ mutation path.

## NAS acceptance tests (pending)

On a Synology SMB share, verify deleting an empty folder, a normal nonempty folder, nested folders, a folder containing a read-only file, and failure cases (insufficient permission / open handles). Verify that a directory link, if supported, does not delete its target. Do not close Issue #279 until these real-device checks and CI are complete.
