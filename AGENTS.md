# XFiles Engineering Instructions

## Rust SMB engine

The SMB implementation is being migrated from SMBJ toward a purpose-built Pure Rust SMB2/SMB3 client. Treat `docs/RUST_SMB_ENGINE_DESIGN.md` as the canonical architecture document for this work.

### Product concept

Do **not** build an SMBJ API clone. Build a protocol-correct, reusable SMB2/SMB3 client core whose data path is deliberately optimized for XFiles workloads, especially large media files, random seeking, thumbnail/metadata extraction, copy, and random-access editing.

The core abstraction is positional I/O:

- `read_at(offset, len)`
- `write_at(offset, data)`
- `stat`, `list`, `open`, `truncate`, `flush`, `rename`, `delete`, `mkdir`

`seek + read` may exist as an adapter, but must not be the internal primitive.

### Non-negotiable design rules

1. **Pure Rust protocol core.** Do not depend on SMBJ, Samba, `libsmbclient`, `libsmb2`, or another native SMB implementation in the Rust data path.
2. **Official specification first.** Implement against Microsoft's current `[MS-SMB2]` Open Specification. Other clients are references for interoperability, not the source of truth.
3. **No SMB1.** Never negotiate or silently fall back to SMB1.
4. **Security is protocol core, not polish.** Dialect negotiation, SMB signing, SMB 3.1.1 preauthentication integrity, key derivation, and signature verification must be designed from the beginning. Never invent cryptographic primitives; use audited Rust cryptography crates.
5. **Never silently weaken security.** If a server/share requires a security feature not yet implemented, return an explicit unsupported/security error instead of downgrading.
6. **Own SMB Credits.** Request scheduling must understand negotiated `MaxReadSize`/`MaxWriteSize`, multi-credit `CreditCharge`, credit grants, MessageIds, and outstanding requests.
7. **Pipeline large I/O.** Large reads/writes must be split by negotiated limits and may have multiple requests in flight. Do not serialize every chunk behind a request/response round trip.
8. **Foreground I/O wins.** Playback/interactive reads outrank speculative prefetch, thumbnail work, and background transfers. A seek creates a new read generation; obsolete queued work is dropped and eligible in-flight work is canceled with SMB2 CANCEL.
9. **Adaptive rather than magic constants.** Do not bake XFiles' current `2 MiB x 2` Kotlin cache into the Rust design. Tune chunk size and prefetch depth from negotiated limits, credits, RTT, throughput, access pattern, and a bounded memory budget.
10. **Share connections.** Do not create one TCP connection/session/tree for every file operation. Reuse a server connection and authenticated session across compatible operations, then reuse TreeConnects per share.
11. **No blind replay.** Reconnect/retry logic must classify operations by idempotency. Reads/queries may be retried after validation; writes, rename, delete, and other mutations must never be replayed blindly.
12. **Keep the core portable.** Android is the first consumer, not an architectural dependency. Core crates must not import JNI/Android concepts.
13. **Keep FFI thin.** Kotlin must not see SMB wire structs, NTSTATUS packet layouts, credit bookkeeping, or crypto internals. Expose stable file/session operations and typed errors.
14. **Avoid double caching.** Once Rust owns media prefetch/cache, remove or bypass equivalent Kotlin caching in `SmbDataSource` rather than stacking independent caches.
15. **Core crates forbid unsafe.** Prefer `#![forbid(unsafe_code)]` in protocol/client/stream crates. Any unavoidable JNI `unsafe` stays inside the Android bridge, is small, documented, and tested.
16. **Untrusted bytes never panic.** Every packet parser must be length checked, reject malformed offsets/counts, and be fuzzable. Malformed network input must become an error, not a panic or out-of-bounds access.
17. **Measure before claiming faster.** Every optimization must be benchmarkable against the existing SMBJ path on the same server/file/network.
18. **Do not big-bang migrate.** Keep a backend boundary so SMBJ and Rust can be A/B tested until the Rust backend meets the replacement gates.

### Initial protocol scope

Target dialects:

- SMB 2.0.2
- SMB 2.1
- SMB 3.0
- SMB 3.0.2
- SMB 3.1.1

Initial transport: direct TCP on port 445.

Initial authentication: SPNEGO with NTLMv2 plus anonymous sessions where the server permits them. Kerberos is a later extension.

Required command path before SMBJ removal:

- `NEGOTIATE`
- `SESSION_SETUP`
- `TREE_CONNECT` / `TREE_DISCONNECT`
- `CREATE`
- `READ`
- `WRITE`
- `FLUSH`
- `CLOSE`
- `QUERY_INFO`
- `QUERY_DIRECTORY`
- `SET_INFO`
- `CANCEL`
- `LOGOFF`

SMB3 encryption, durable handles, leases/oplocks, Kerberos, multichannel, QUIC, RDMA, DFS, compression, named-pipe/RPC support, printer shares, and server implementation are not reasons to distort the first milestones. The architecture must leave clean extension points for the relevant features. SMB3 encryption is required before declaring broad production parity with SMBJ when encrypted shares are part of the compatibility target.

### Rust layering

Keep responsibilities separated even if some layers initially live in one Cargo workspace:

- `smb-wire`: endian-safe packet types, encode/decode, validation, NetBIOS-over-TCP framing as applicable.
- `smb-auth`: SPNEGO/NTLM integration and credential/session-key handling.
- `smb-client`: transport, negotiate/session/tree/open state machines, MessageIds, credits, outstanding-request dispatch, signing/security transforms, reconnect coordination.
- `smb-fs`: filesystem-oriented API and SMB CREATE/QUERY/SET_INFO semantics.
- `smb-stream`: positional I/O, request splitting/reassembly, priority scheduling, adaptive read-ahead/cache, cancellation generations, metrics.
- `smb-android`: JNI-facing handles/DTOs/error mapping only.
- `smb-testkit`: protocol fixtures, mock transport, interoperability harnesses, fuzz/property-test helpers.

The names can evolve; the responsibility boundaries must not collapse.

### Android migration contract

Preserve the caller-facing behavior of the existing XFiles SMB paths while replacing their internals incrementally:

- `SmbFileSystem`
- `SmbRandomAccessFile`
- `SmbRandomAccessOutputFile`
- `SmbDataSource`

Introduce an internal backend boundary so an SMBJ implementation and Rust implementation can coexist during migration. Do not spread a temporary feature flag throughout UI code.

Media3's blocking `DataSource.read()` contract is allowed to block the caller's I/O thread, but the Rust engine must maintain its own long-lived async runtime and pipeline network requests. Do not create a Tokio runtime or TCP connection per `read()` call.

### Quality gates for SMBJ removal

Do not remove SMBJ merely because basic NAS access works. The Rust backend must pass:

- filesystem behavior parity needed by XFiles;
- read/write/truncate/flush and same-share rename/move flows;
- SMB signing and SMB 3.1.1 preauthentication integrity interoperability;
- malformed-packet/fuzz safety for the wire layer;
- reconnect/recovery tests without unsafe mutation replay;
- Android lifecycle/reopen tests;
- performance comparison for playback start, sequential throughput, random seek latency, CPU, memory, and connection count.

On the same test environment, the Rust path must show no material regression versus SMBJ. For media workloads, seek responsiveness and sustained throughput are first-class acceptance metrics, not optional tuning after migration.
