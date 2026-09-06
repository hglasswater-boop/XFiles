# Rust SMB Engine Design

Status: design baseline for implementation

## 1. Purpose

XFiles currently uses SMBJ for SMB2/SMB3 filesystem access and has additional Kotlin-side logic for positional media reads and caching. This project replaces that stack incrementally with a purpose-built Pure Rust SMB2/SMB3 client engine.

This is **not** a line-for-line SMBJ port and it is **not** an attempt to implement every SMB feature before producing value. The target is a protocol-correct client core with a data path specifically good at the workloads XFiles actually generates:

- browsing large SMB shares;
- opening and streaming large video files;
- low-latency random seek;
- metadata and thumbnail extraction at arbitrary offsets;
- copy and export;
- positional read/write for media editing;
- truncate, flush, rename, move, delete, and mkdir;
- recovery from ordinary mobile-network interruptions without corrupting remote state.

The implementation should become reusable outside Android, but XFiles is the first production consumer and the first benchmark target.

## 2. Current XFiles baseline

The existing Kotlin implementation establishes the compatibility contract we must preserve, not the internal architecture we must copy.

Current important behaviors:

- `XFileSystem` exposes blocking `list`, `stat`, `openIn`, `openOut`, `createFile`, `mkdir`, `delete`, and `rename` operations.
- `SmbFileSystem` currently creates SMBJ clients/sessions/shares for filesystem operations.
- `SmbRandomAccessFile` exposes offset-based reads for Media3 and thumbnail/metadata extraction.
- `SmbRandomAccessOutputFile` exposes positional read/write, truncate, and flush for editing/export paths.
- `SmbDataSource` currently compensates for small Media3 reads using two 2 MiB aligned LRU blocks.

The Rust backend should preserve these visible behaviors while eliminating unnecessary connection churn, serialized network round trips, and duplicate buffering.

## 3. Design principles

### 3.1 Positional I/O is the native model

The internal file API is based on offsets:

```rust
pub trait RandomAccessFile {
    async fn read_at(&self, offset: u64, len: usize) -> Result<Bytes, SmbError>;
    async fn write_at(&self, offset: u64, data: Bytes) -> Result<usize, SmbError>;
    async fn len(&self) -> Result<u64, SmbError>;
    async fn set_len(&self, len: u64) -> Result<(), SmbError>;
    async fn flush(&self) -> Result<(), SmbError>;
}
```

The exact Rust trait shape may change as ownership and zero-copy decisions mature, but offset-based I/O remains the primitive. Sequential streams are adapters on top.

Why:

- Media3 seeks are naturally positional.
- MP4/MKV metadata readers jump between file regions.
- thumbnail extraction often probes the head, tail, and keyframe areas.
- editing and export require writes at known offsets.
- a positional API allows independent requests to be pipelined without a shared mutable cursor.

### 3.2 Correct protocol state before clever optimization

Performance work must sit on explicit SMB connection/session/tree/open state rather than hiding protocol state behind generic streams.

The engine owns:

- negotiated dialect and capabilities;
- `MaxReadSize`, `MaxWriteSize`, `MaxTransactSize`;
- multi-credit support;
- MessageId sequence allocation;
- current credit balance and grants;
- outstanding requests keyed by MessageId/AsyncId;
- session signing/encryption state;
- TreeIds and FileIds;
- reconnect generation and open-handle recovery metadata.

If the state is not explicit, robust pipelining, cancellation, security verification, and reconnect behavior become fragile.

### 3.3 One logical engine, shared transport resources

Do not model a file as owning an entire TCP connection.

Initial topology:

```text
SmbEngine
  └─ ServerPool[ServerKey]
       └─ Connection (TCP 445, negotiated SMB state)
            └─ Session[CredentialIdentity]
                 └─ Tree[ShareName]
                      ├─ FileHandle A
                      ├─ FileHandle B
                      └─ FileHandle C
```

`ServerKey` includes at minimum host, port, and security-policy-relevant identity. Sessions are reused only when authentication identity and security policy are compatible.

A connection can service playback, thumbnail reads, directory queries, and file operations concurrently through SMB's MessageId and credit machinery.

This is the first major structural difference from the current per-operation/per-file connection behavior.

## 4. Protocol scope

### 4.1 Dialects

Initial client negotiation supports:

- SMB 2.0.2 (`0x0202`)
- SMB 2.1 (`0x0210`)
- SMB 3.0 (`0x0300`)
- SMB 3.0.2 (`0x0302`)
- SMB 3.1.1 (`0x0311`)

SMB1 is intentionally unsupported. The client must never negotiate or silently fall back to SMB1.

### 4.2 Transport

Initial transport is direct SMB over TCP, normally port 445.

Later extensions may add:

- SMB over QUIC;
- SMB Multichannel;
- RDMA/SMB Direct.

These are not phase-1 dependencies. The transport interface must nonetheless avoid hard-coding TCP concepts into packet/state code.

### 4.3 Authentication

Initial authentication:

- SPNEGO framing;
- NTLMv2;
- anonymous session where explicitly permitted by the server and requested by configuration.

Kerberos is planned after the core client is stable.

Credentials and derived keys are secret types:

- never `Debug` plaintext credentials/session keys;
- zeroize sensitive buffers where practical;
- never include them in telemetry, panic messages, packet traces, or test snapshots.

### 4.4 Core SMB commands

Commands required for the production replacement path:

- NEGOTIATE
- SESSION_SETUP
- TREE_CONNECT
- TREE_DISCONNECT
- CREATE
- READ
- WRITE
- FLUSH
- CLOSE
- QUERY_INFO
- QUERY_DIRECTORY
- SET_INFO
- CANCEL
- LOGOFF

Additional commands are added when a concrete compatibility need appears. Avoid implementing unrelated named-pipe/RPC/server-management functionality merely to inflate protocol coverage.

## 5. Security architecture

Security must be implemented as part of connection/session establishment, not patched in after the filesystem works.

### 5.1 SMB 3.1.1 preauthentication integrity

For SMB 3.1.1, maintain the rolling preauthentication integrity hash over the exact negotiate/session-setup messages required by `[MS-SMB2]`. The resulting value participates in session key derivation.

This implies:

- wire serialization used for hashing must be the exact bytes sent;
- response bytes must be preserved/hashed before higher-layer normalization loses information;
- reconnect/reauthentication must have explicit preauth hash state rather than a global singleton.

### 5.2 Signing

Support signing algorithms by dialect/capability:

- HMAC-SHA256 for SMB 2.x;
- AES-128-CMAC for SMB 3.x where applicable;
- AES-GMAC for SMB 3.1.1 where negotiated.

Design requirements:

- verify signed responses before delivering payloads to callers;
- signing-required state is session-specific;
- reject invalid signatures as security errors and tear down the affected trust context;
- no user-facing "ignore bad signature" mode.

### 5.3 Encryption

SMB3 transform encryption is an explicit production-parity milestone, not required to prove the first read-only prototype.

The client architecture includes a message-transform layer so encryption can be introduced without rewriting request dispatch:

```text
SMB request object
   -> encode
   -> sign if required
   -> encrypt transform if required
   -> transport frame

transport frame
   -> decrypt transform if present/required
   -> verify signature as applicable
   -> decode/validate
   -> dispatch response
```

When a server/share requires encryption before support is complete, return an explicit unsupported-security-feature error. Do not downgrade or bypass the requirement.

### 5.4 Cryptography policy

Do not implement AES, HMAC, CMAC, GMAC, SHA, KDFs, MD4/MD5 primitives, or random generation from scratch. Use established Rust cryptography crates and keep algorithm selection in one reviewed security module.

## 6. Cargo workspace and responsibility boundaries

A recommended workspace shape is:

```text
native/smb/
  Cargo.toml
  crates/
    smb-wire/
    smb-auth/
    smb-client/
    smb-fs/
    smb-stream/
    smb-android/
    smb-testkit/
```

This can start with fewer physical crates if iteration speed demands it. The following boundaries remain conceptually strict.

### 6.1 `smb-wire`

Responsibilities:

- SMB2 header and command structures;
- little-endian field encoding/decoding;
- offset/length validation;
- compound-message framing when introduced;
- TCP session framing;
- NTSTATUS representation;
- no sockets, credentials, reconnect logic, Android, or caching.

Properties:

- deterministic encode/decode;
- no `unsafe`;
- malformed input returns typed parse errors;
- fuzzable independently.

Avoid giant enum serializers that allocate a fresh `Vec` for every field. Prefer bounded writers/readers over byte slices and `bytes::Bytes`/`BytesMut`-style ownership where appropriate.

### 6.2 `smb-auth`

Responsibilities:

- SPNEGO token flow;
- NTLMv2 integration;
- session-key extraction into secret types;
- later Kerberos provider boundary.

The SMB session state machine consumes an authentication provider interface rather than directly knowing NTLM packet details.

### 6.3 `smb-client`

Responsibilities:

- transport lifecycle;
- negotiate/session/tree/open state machines;
- MessageId allocation;
- SMB Credits and multi-credit accounting;
- request writer and response demultiplexer;
- outstanding request table;
- timeouts and cancellation;
- signing/preauth/encryption transform coordination;
- reconnect state machine;
- protocol metrics.

### 6.4 `smb-fs`

Responsibilities:

- high-level share/file semantics;
- translating open intentions into CREATE desired access/share access/disposition/options;
- directory listing with QUERY_DIRECTORY;
- stat with QUERY_INFO;
- rename/truncate/delete semantics through SET_INFO/CREATE as required;
- mkdir/file creation;
- path normalization rules.

Do not expose raw access-mask bitfields to Kotlin.

### 6.5 `smb-stream`

Responsibilities:

- `read_at` / `write_at` orchestration;
- split requests by negotiated server limits;
- reassemble out-of-order subresponses;
- prioritize interactive reads;
- adaptive prefetch;
- bounded cache;
- cancellation generations;
- workload metrics.

This is where XFiles-specific media advantage lives, without polluting wire/protocol correctness.

### 6.6 `smb-android`

Responsibilities only:

- initialize one long-lived engine/runtime;
- convert Kotlin connection settings into Rust configuration;
- opaque handle IDs;
- byte/string/DTO conversion;
- error mapping;
- JNI lifecycle safety.

Do not put SMB protocol behavior in JNI functions.

Core crates should use `#![forbid(unsafe_code)]`. If JNI requires unsafe code, confine it to `smb-android` and document each unsafe block's invariant.

## 7. Request engine

### 7.1 Single writer, response dispatcher

Recommended connection tasks:

```text
Callers / schedulers
        |
        v
  RequestBroker
  - priority
  - credits
  - MessageIds
        |
        v
   writer task  ---- TCP ----> server

   reader task  <--- TCP ----- server
        |
        v
 outstanding[MessageId/AsyncId]
        |
        +--> oneshot/future completion
```

The broker assigns a request ID only when the request is eligible for dispatch and enough credits exist.

The reader task:

1. reads a complete SMB frame;
2. applies decrypt/verify processing;
3. validates header/command correlation;
4. applies returned credit grants;
5. resolves the corresponding outstanding request;
6. handles unsolicited/async cases explicitly rather than dropping unknown packets silently.

### 7.2 Credit accounting

Credits are a scheduling resource, not a counter printed in logs.

For multi-credit connections, large READ/WRITE requests calculate CreditCharge according to protocol rules. READs larger than negotiated `MaxReadSize` are split into separate operations. The scheduler may send those reads in parallel/order-independent fashion and reassemble results by offset.

Key rules:

- never send a request that exceeds the negotiated max operation size;
- never oversubscribe granted credits;
- reserve capacity so speculative prefetch cannot starve an urgent foreground read;
- update available credits from every valid response grant;
- expose credit wait time and utilization metrics.

### 7.3 Priority classes

Initial priority order:

1. `Interactive`: bytes directly blocking playback/current user request.
2. `Control`: open/stat/list/close needed to progress visible operations.
3. `SequentialPrefetch`: likely next bytes for the active stream.
4. `Transfer`: user-started copy/export/write pipeline.
5. `Background`: thumbnails/indexing/speculation.

Priority must not become starvation. Use bounded fairness/aging for lower classes, but foreground playback is allowed to preempt speculative work.

### 7.4 Cancellation and generations

Each streaming consumer owns an access generation.

On a large seek:

1. increment generation;
2. discard queued prefetch from older generations;
3. cancel eligible old in-flight speculative requests with SMB2 CANCEL;
4. keep cache entries only if they remain generally useful and memory budget permits;
5. dispatch the requested new-offset read at interactive priority immediately.

Do not cancel a request solely because its data will not be used if cancellation would cost more than allowing an almost-complete request to finish. The scheduler can use elapsed/expected RTT to make that decision later.

## 8. Adaptive media read pipeline

The current fixed 2 MiB x 2 cache solves a real latency problem, but the Rust engine should tune rather than freeze those numbers.

### 8.1 Inputs

Maintain EWMAs or similarly stable observations for:

- request RTT;
- delivered throughput;
- cache hit ratio;
- sequential-run length;
- seek frequency/distance;
- negotiated MaxReadSize;
- current credit balance;
- memory pressure/budget;
- active consumers.

### 8.2 Chunk size

A practical policy starts conservatively and moves toward larger requests when sequential access is stable.

Conceptually:

```text
chunk = clamp(
  negotiated-safe-size informed by RTT and throughput,
  min_chunk,
  min(MaxReadSize, configured_max_chunk)
)
```

Do not assume the server's largest legal request is always the lowest-latency choice. Short random probes and high-latency sequential playback have different optima.

### 8.3 Prefetch window

Prefer a time-oriented target over a fixed number of bytes:

```text
prefetch_bytes ~= observed_throughput * target_buffer_seconds
```

Then clamp by:

- cache memory budget;
- credits currently available;
- MaxReadSize and request-count limits;
- foreground demand from other active handles.

The first implementation may use conservative constants behind this policy, but the architecture and metrics must make adaptive tuning possible.

### 8.4 Cache ownership

The media cache belongs in Rust once the Rust backend is active.

Benefits:

- one cache serves Media3, metadata probes, and related positional reads;
- cached ranges are aware of requests already in flight;
- prefetch scheduler and cache share the same access-pattern observations;
- Kotlin avoids allocating/copying whole 2 MiB blocks independently;
- seek cancellation can invalidate speculative state coherently.

Cache data structure should represent byte ranges/pages, not assume all consumers align exactly to one hard-coded block size.

## 9. Write pipeline and mutation safety

Writes need different reliability semantics from reads.

### 9.1 Positional writes

`write_at` splits by `MaxWriteSize` and credit limits. It may pipeline independent ranges where the caller's ordering semantics allow it.

Do not claim a write is durable merely because the WRITE response arrived. `flush()` maps to SMB2 FLUSH and is the durability boundary requested by current XFiles behavior.

### 9.2 Truncate

Expose `set_len` at the filesystem/file-handle layer and map it to the appropriate SMB file-information operation. Preserve the current `SmbRandomAccessOutputFile.setLength()` behavior.

### 9.3 Retry classification

Every request type has retry metadata, for example:

```rust
enum RetryClass {
    SafeAfterReconnect,
    ReopenAndValidate,
    ReplayAwareOnly,
    NeverAutomatically,
}
```

Examples:

- READ / QUERY: generally safe after reopening/validation.
- directory query: restartable with care around resume state.
- WRITE: never blindly resend after an ambiguous transport failure; the server may already have applied it.
- rename/delete/SET_INFO: never blindly replay.

Later durable handles and SMB3 replay mechanisms may safely broaden recovery, but correctness comes before convenience.

## 10. Reconnect state machine

Mobile devices switch Wi-Fi/APs, sleep, and temporarily lose connectivity. Reconnect behavior is a core concern.

Suggested state model:

```text
Connected
   |
 transport failure
   v
Recovering
   |
 new transport + NEGOTIATE
   v
Reauthenticating
   |
 SESSION_SETUP
   v
RestoringTrees
   |
 TREE_CONNECT
   v
RestoringHandles
   |
 reopen / durable reconnect
   v
Connected(new generation)
```

Rules:

- one connection recovery coordinator prevents a thundering herd of file handles all reconnecting independently;
- callers may await recovery up to their operation deadline;
- each restored file handle obtains a new internal generation;
- stale responses from an old connection generation are never delivered to new requests;
- read-only path reopen validates identity-relevant metadata where practical before continuing;
- mutation requests with ambiguous completion surface an explicit error instead of auto-replay.

Durable handles should fit into `RestoringHandles` later without changing the public file API.

## 11. Error model

Do not flatten every failure to `IOException("SMB operation failed")` inside Rust.

Rust internal error categories should retain context:

```text
Transport
Timeout
Cancelled
Authentication
Authorization
Negotiation
UnsupportedDialect
UnsupportedSecurityFeature
SignatureMismatch
Encryption
ProtocolViolation
MalformedResponse
NtStatus(status)
NotFound
AlreadyExists
NotDirectory
IsDirectory
StaleHandle
ReconnectAmbiguousMutation
Closed
```

Android maps these into a stable Kotlin exception/error model, ultimately preserving enough detail for UI messaging and diagnostics.

Logs should contain operation type, host alias/connection ID, share alias, MessageId when safe, dialect, timings, and status. They must not contain passwords, NTLM responses, session keys, or full sensitive filenames unless explicit debug policy permits path logging.

## 12. Android/JNI integration

### 12.1 Runtime lifecycle

Use one long-lived Rust async runtime owned by the application-level SMB engine. Do not construct Tokio runtimes per operation/read.

Media3's `DataSource.read(byte[], off, len)` is blocking, so the JNI facade may synchronously wait for the requested bytes while Rust continues to maintain asynchronous in-flight network work and prefetch behind it.

### 12.2 Data crossing JNI

Do not hold a pinned JVM byte array across network waits. Network I/O completes into Rust-owned buffers/cache, then the requested bytes are copied into the Java/Kotlin destination buffer during the short JNI call completion path.

This intentionally accepts one bounded memory copy in exchange for GC safety and a much simpler lifetime model.

If later profiling shows JNI copying is a bottleneck, introduce a carefully designed direct-buffer path only after measurement.

### 12.3 Opaque handles

Kotlin sees opaque engine/session/file handles, not Rust pointers and not SMB FileIds directly.

Example conceptual API:

```text
SmbNativeEngine.openFile(connectionId, path, mode) -> FileHandle
FileHandle.readAt(position, byteArray, offset, length) -> Int
FileHandle.writeAt(position, byteArray, offset, length) -> Int
FileHandle.length() -> Long
FileHandle.setLength(length)
FileHandle.flush()
FileHandle.close()
```

Filesystem methods can be exposed through an engine/share facade. The precise surface should minimize JNI crossings for bulk operations such as directory listings.

## 13. Kotlin migration architecture

Do not replace direct SMBJ imports across the application one by one.

Introduce one backend abstraction behind existing XFiles filesystem classes:

```text
                   SmbBackend
                  /          \
             SMBJ backend   Rust backend
                  \          /
       SmbFileSystem / RandomAccess facades
                     |
             existing XFiles callers
```

The backend boundary should cover:

- test connection;
- list/stat;
- open read/random read;
- open write/random read-write;
- create/mkdir/delete/rename/move within share;
- truncate/flush/close.

Migration sequence:

1. create backend interface with SMBJ implementation only; behavior must remain unchanged;
2. add Rust backend behind the same contract;
3. add developer-only backend selection/A-B instrumentation in one dependency-injection point;
4. move media cache/prefetch into Rust and disable Kotlin `SmbDataSource` block cache for Rust handles;
5. compare correctness and performance;
6. make Rust default when gates pass;
7. retain SMBJ temporarily as rollback/reference;
8. remove SMBJ only after compatibility and performance gates are complete.

A feature flag must not leak into UI/viewer/business code.

## 14. Testing strategy

### 14.1 Wire unit tests

For each command structure:

- encode known field values to exact expected bytes;
- decode exact packet fixtures;
- round-trip encode/decode where semantically valid;
- test every offset/length boundary and truncated packet location;
- test unknown/reserved values according to spec behavior.

### 14.2 Property and fuzz testing

`smb-wire` is network-input parsing code and should be treated accordingly.

Use property/fuzz tests for:

- arbitrary packet truncation;
- malicious lengths/offsets;
- overflow attempts;
- compound-message loops/invalid alignment when compound support lands;
- transform headers;
- directory entry chains;
- security-buffer offsets.

Invariant: arbitrary input cannot cause panic, OOB access, unbounded allocation, or integer overflow.

### 14.3 Mock transport tests

Exercise the request broker without a real server:

- responses returned out of order;
- partial TCP frames;
- multiple frames in one read;
- credit grants increasing/decreasing concurrency;
- timeout before send vs after send;
- cancellation races;
- reconnect while requests are outstanding;
- stale old-generation response after reconnect;
- invalid signature/protocol response.

### 14.4 Integration matrix

Automatable baseline:

- Samba container in CI with multiple security/signing configurations;
- anonymous and NTLM authenticated shares;
- read/write filesystem tests;
- large sequential/random file tests.

Real-device/manual compatibility matrix should eventually include:

- Windows file server/current Windows desktop sharing;
- at least one common NAS implementation used by the project;
- signed SMB 3.1.1;
- encrypted share once encryption lands;
- Android ARM64 release build.

### 14.5 Differential testing

For migration-critical operations, run SMBJ and Rust backends against the same disposable share and compare observable results:

- listings and metadata;
- create/write/flush/readback;
- truncate;
- rename/move;
- recursive delete behavior expected by XFiles;
- access/error outcomes.

Do not run destructive differential tests against user data.

## 15. Performance benchmarks and telemetry

Performance claims use the same NAS, file, Android device, network, and test sequence for SMBJ and Rust.

Track at least:

- connection + authentication time;
- first-byte/playback-start latency;
- sequential read throughput;
- random seek latency p50/p95/p99;
- write throughput;
- number of SMB round trips per MiB;
- in-flight READ/WRITE depth;
- credit utilization/wait time;
- cache hit rate;
- bytes prefetched then discarded;
- CPU usage;
- native + JVM memory;
- TCP connection/session/tree count;
- reconnect time and success rate.

Initial acceptance rule: no material regression from SMBJ on required functionality. Media-path implementation should demonstrate that pipelining/adaptive prefetch reduces latency-bound behavior rather than merely moving the same serialized pattern from Java to Rust.

Avoid one synthetic headline number. A fast sequential benchmark can hide terrible seek latency, memory growth, or excessive connection creation.

## 16. Implementation phases

### Phase 0: migration seam + protocol test harness

- define `SmbBackend` at Kotlin boundary;
- wrap existing SMBJ behavior without functional changes;
- create Rust workspace and test infrastructure;
- establish benchmark fixtures.

Exit: app behavior unchanged and SMBJ baseline measurable.

### Phase 1: wire + TCP + NEGOTIATE

- SMB2 header/framing;
- NEGOTIATE request/response;
- dialect/capability state;
- parser bounds/fuzz foundation.

Exit: negotiate against Samba and real server, no authentication yet.

### Phase 2: SPNEGO/NTLM + SESSION + TREE + signing foundation

- NTLMv2 authentication provider;
- SESSION_SETUP;
- TREE_CONNECT;
- SMB2.x/3.x signing key model;
- SMB 3.1.1 preauth integrity;
- request signing and response verification.

Exit: signed authenticated share connection works.

### Phase 3: read-only file path

- CREATE file;
- READ;
- QUERY_INFO;
- CLOSE;
- positional `read_at`;
- negotiated max-read splitting.

Exit: arbitrary offsets can be read correctly from large media files.

### Phase 4: credits + concurrency + media scheduler

- MessageId/credit allocator;
- multiple outstanding requests;
- out-of-order response dispatch;
- interactive/background priority;
- adaptive cache/read-ahead;
- CANCEL and seek generations.

Exit: Media3 playback/seek prototype beats the serialized Rust baseline and competes with/exceeds SMBJ path.

### Phase 5: filesystem coverage

- QUERY_DIRECTORY/list;
- stat metadata parity;
- create/mkdir/delete/rename;
- same-share move semantics.

Exit: normal XFiles SMB browsing/management works on Rust backend.

### Phase 6: random write/edit path

- WRITE with credits/pipeline;
- read-write handle;
- truncate;
- FLUSH;
- partial-file/export workflows.

Exit: current random-access editing/export needs work through Rust backend.

### Phase 7: resilience and production security coverage

- coordinated reconnect;
- reopen/validate logic;
- explicit mutation ambiguity behavior;
- durable handle support where valuable;
- SMB3 encryption;
- security/interoperability matrix.

Exit: ordinary mobile interruptions and encrypted-share target scenarios are handled intentionally.

### Phase 8: Android default + SMBJ removal gate

- JNI release hardening;
- Android lifecycle tests;
- performance/memory comparison;
- Rust default;
- soak period;
- remove SMBJ dependency only after all gates pass.

## 17. Deferred features

Deferred unless a real requirement moves them forward:

- SMB1;
- SMB server implementation;
- named-pipe/RPC ecosystem;
- printer shares;
- DFS;
- Kerberos;
- Multichannel;
- QUIC;
- RDMA;
- compression;
- leases/oplocks beyond what becomes necessary for correctness/performance;
- broad POSIX extension support.

Deferred means "architecture must not prevent it," not "implement placeholder code now."

## 18. Decisions that should remain explicit

Record changes to these as architecture decisions rather than accidental code drift:

- supported dialect floor;
- signing default policy;
- encryption milestone;
- session/connection reuse key;
- cache memory budget policy;
- foreground/background scheduling fairness;
- mutation retry rules;
- JNI copy strategy;
- criteria for removing SMBJ.

## 19. Source of truth

Protocol behavior is based on Microsoft's current `[MS-SMB2]` Open Specification. As of this design baseline, the published specification revision is 87.0 dated 2026-07-14.

Useful official references:

- `[MS-SMB2]` main specification: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/5606ad47-5ee0-437a-817e-70c366052962
- Versioning/capability negotiation: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/fac3655a-7eb5-4337-b0ab-244bbcd014e8
- SMB2 CANCEL request: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/91913fc6-4ec9-4a83-961b-370070067e63
- Multi-credit requests: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/0edce9a0-766e-41aa-a3b7-2044defbbb8f

When the spec is revised, update tests/behavior based on relevant protocol changes rather than pinning assumptions forever to this document revision.
