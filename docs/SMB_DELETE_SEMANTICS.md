# SMB delete semantics

Status: product/implementation contract for SMB file deletion

## Scope

This document defines the behavior of `XFileSystem.delete()` for regular files on SMB shares. Directory deletion keeps its existing recursive behavior and is outside the read-only-file recovery described here.

The current production filesystem path is SMBJ. The Pure Rust SMB backend must implement the same externally visible semantics when filesystem mutations move to Rust.

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

## Error presentation

Low-level SMB diagnostics remain useful for logs, but the filesystem layer must preserve the meaningful NTSTATUS and operation. The UI must not infer that `STATUS_CANNOT_DELETE` always means missing ACL permissions: for the `FileDispositionInformation` path it specifically requires read-only handling as described above.

## Tests required before implementation

Regression coverage must prove:

- an ordinary file delete succeeds without attribute mutation;
- non-`STATUS_CANNOT_DELETE` failures are propagated without recovery;
- `STATUS_CANNOT_DELETE` with no read-only attribute is propagated without mutation;
- a read-only file has only `FILE_ATTRIBUTE_READONLY` cleared, then delete is retried once;
- a read-only-only attribute set uses `FILE_ATTRIBUTE_NORMAL` rather than zero;
- if the retry fails, restoration uses the original attribute value.

The Rust backend must carry equivalent protocol-level tests before it replaces the SMBJ mutation path.
