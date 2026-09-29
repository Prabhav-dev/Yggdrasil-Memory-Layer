# Yggdrasil Memory Layer — Standalone Project Contents

**Status as of:** 2026-09-30
**Stage:** Exact-text MVP — implemented and tested. Semantic path, deletion, scalable catalog, and authenticated transport are **not yet implemented**.

---

## Project-Level Files

| File | Purpose | Status |
|------|---------|--------|
| `README.md` | Quick-start guide: what works, prerequisites, build/test commands, run instructions | Complete |
| `DESIGN.md` | Full architecture, integration contract, durability/security requirements, and remaining delivery plan | Complete |
| `Cargo.toml` | Workspace definition: `rust/java-diff-utils-rs` + `rust/memory-layer`; test profile at `opt-level = 2` | Complete |
| `Cargo.lock` | Locked dependency graph for the Rust workspace | Auto-managed |
| `.gitignore` | Ignores Rust `target/`, Java `out/`, and data directories | Complete |
| **`contents.md`** | This file — project map and coverage summary | New |

---

## `scripts/`

| File | Purpose | Status |
|------|---------|--------|
| `scripts/run-storage.ps1` | Compiles the Java storage engine and launches `MemoryStorageServer` on a chosen port and data directory. Validates `YGGDRASIL_ROOT_KEY_HEX` (32 hex chars) before starting. | Complete |
| `scripts/test.ps1` | Runs `cargo test --workspace` then compiles and runs `Yggdrasil.TcpIntegrationTest` via `javac`/`java`. | Complete |

---

## `rust/` — Rust Workspace

### `rust/java-diff-utils-rs/` — Diff/Patch Crate (copied from upstream commit `26ebbda`)

This is a **read-only copy** of the upstream crate. Modifications here do not flow back to the source repository.

| File / Path | Purpose | Status |
|-------------|---------|--------|
| `Cargo.toml` | Package metadata; deps: `regex`, `lazy_static`, `serde`, `bincode`, `unicode-segmentation` | Upstream copy |
| `src/lib.rs` | Public API surface: re-exports `DiffUtils`, `Patch`, `UnifiedDiff`, algorithm selection | Upstream copy |
| `src/unified_diff_utils.rs` | Myers / linear-space Myers / histogram diffing; patch application; unified-diff parsing and writing | Upstream copy |
| `tests/` | Upstream integration tests for diff round trips | Upstream copy |
| `benches/` | Criterion benchmarks | Upstream copy |
| `README.md`, `TESTS.md`, `Comparison.md`, `Decision.md`, `Bug fixes.md`, `changes.md` | Upstream documentation | Upstream copy |
| `LICENSE` | LGPL-2.1 license retained from upstream | Upstream copy |

**Used by `memory-layer` for:** `DiffUtils::diff()` (generates `Patch<String>`) and `Patch::apply_to()` (applies patch to source lines). Serialized via `bincode` for storage in revision delta records.

---

### `rust/memory-layer/` — Rust Memory Client and API

**Crate name:** `yggdrasil-memory-layer`
**Binary entry point:** `src/main.rs` (CLI: `create`, `update`, `get`)
**Library entry point:** `src/lib.rs`

#### `rust/memory-layer/Cargo.toml`

Dependencies:
- `bincode = "1.3"`
- `java-diff-utils-rs = { path = "../java-diff-utils-rs" }`
- `sha2 = "0.10"`

#### Source Files

| File | Key Types / Functions | Purpose | Status |
|------|----------------------|---------|--------|
| `src/lib.rs` | `MemoryError`, `Result<T>` | Crate root: declares `mod memory`, `mod storage`; exports public types; defines the shared error enum with 8 variants (`Io`, `Protocol`, `Remote`, `InvalidRecord`, `Patch`, `Serialization`, `AlreadyExists`, `NotFound`) | Complete |
| `src/storage.rs` | `StorageClient`, `RecordStore` trait, `StoredRecord` | TCP client for the Java wire protocol. Sends framed APPEND (`0x05`) and SCAN (`0x06`) requests; validates magic byte, request-ID echo, payload bounds (16 MiB), and strict scan ordering. Includes a mock-server unit test for round-trip framing. | Complete |
| `src/memory.rs` | `MemoryLayer<S>`, `Memory`, `MemoryId`, `Fragment`, `PendingBatch` | Core memory semantics: create (rev 1 snapshot), update (delta via diff), get, and WAL-safe reload. Chunked writes (3 000 B/chunk), commit markers, SHA-256 parent/result hash verification. Startup full-scan rebuilds the in-memory ID map. | Complete |
| `src/main.rs` | `main()`, `run()` | CLI binary: `<host:port> create|update|get <32-hex-id> [file]`. Writes content to stdout, revision to stderr on `get`. | Complete |

---

#### `src/memory.rs` — Detailed Coverage

**`MemoryId`** — 128-bit opaque ID, hex-encoded 32 chars. `FromStr` / `Display` round-trip tested.

**Record format** — Binary fragment layout (122-byte fixed header):

| Offset | Field | Size |
|--------|-------|------|
| 0-3 | Magic `YML1` | 4 B |
| 4 | Version (`1`) | 1 B |
| 5 | Kind (`1`=snapshot, `2`=delta, `3`=commit) | 1 B |
| 6-21 | Memory ID | 16 B |
| 22-37 | Batch ID (timestamp + PID + counter) | 16 B |
| 38-45 | Revision (u64 BE) | 8 B |
| 46-49 | Chunk index (u32 BE) | 4 B |
| 50-53 | Chunk count (u32 BE) | 4 B |
| 54-85 | Parent SHA-256 | 32 B |
| 86-117 | Result SHA-256 | 32 B |
| 118-121 | Data length (u32 BE) | 4 B |
| 122+ | Data | up to 3 000 B |

**Revision rules:**
- Rev 1: `KIND_SNAPSHOT`, parent hash = `[0; 32]`, data = raw UTF-8 bytes.
- Rev N > 1: `KIND_DELTA`, data = `[trailing_newline_flag u8] ++ bincode(Patch<String>)`.
- A `KIND_COMMIT` record with matching batch ID finalizes the revision; uncommitted chunks are silently discarded at reload.

**`MemoryLayer<S: RecordStore>` methods covered:**

| Method | What it does |
|--------|-------------|
| `connect(addr)` | Opens `StorageClient` and calls `load()` |
| `load(store)` | Full SCAN loop; rebuilds `HashMap<MemoryId, Memory>` from committed fragments |
| `create(id, content)` | Errors if ID exists; writes rev-1 snapshot chunks + commit |
| `update(id, content)` | No-op if content unchanged; otherwise diffs, writes delta chunks + commit |
| `get(id)` | In-memory lookup; returns `Option<&Memory>` |
| `len()` / `is_empty()` | Collection size helpers |

**Tests in `src/memory.rs`:**
1. `revisions_round_trip_exact_text_across_reload` — create, update, reload; checks content and revision number.
2. `line_ending_and_empty_content_changes_are_lossless` — newline, empty string, mixed CRLF.
3. `partial_uncommitted_revision_is_ignored` — manually inserts a chunk with no commit; confirms it does not appear after reload.
4. `memory_id_hex_round_trips` — parse/display symmetry; rejects short IDs.

**Tests in `src/storage.rs`:**
1. `client_round_trips_append_and_scan_frames` — spawns a minimal mock TCP server; validates APPEND and SCAN frame encoding and response parsing end-to-end.

---

## `java-storage/` — Java Storage Engine (standalone copy)

> This is a self-contained copy of the Yggdrasil Blind Storage engine. Changes here do not update the sibling `Yggdrasil-Blind_Storage/` folder.

### Top-Level Java Docs

| File | Purpose |
|------|---------|
| `java-storage/README.md` | Java engine overview, build steps, protocol summary |
| `java-storage/BUG_FIX_REPORT.md` | Known bug fixes applied to this copy |
| `java-storage/STDLIB.md` | Standard library / zero-dependency constraint documentation |

### `src/main/java/Yggdrasil/` — Server Layer

| File | Purpose | Status |
|------|---------|--------|
| `WireProtocol.java` | Binary frame format (14-byte header). Opcodes: PUT `0x01`, GET `0x02`, DELETE `0x03`, AUDIT `0x04`, APPEND `0x05`, SCAN `0x06`. Response opcodes `0x80`/`0x81`. `parseHeader()`, `parseFrame()`, `encodeRequest()`, `encodeSuccessResponse()`, `encodeErrorResponse()`. | Complete — APPEND + SCAN added for standalone |
| `MemoryStorageServer.java` | Main entry point. Reads `YGGDRASIL_ROOT_KEY_HEX` (32 hex chars), boots `StorageManager` + `WalManager` + `BPlusTree` + `RecordManager`, binds `NioServer` to `127.0.0.1:<port>`. Shutdown hook stops server and closes managers. | Complete |
| `NioServer.java` | Java NIO selector-based TCP server. Manages `SocketChannel` accept/read/write lifecycle with worker threads. | Complete |
| `RequestDispatcher.java` | Dispatches decoded frames to `RecordManager` operations. APPEND allocates a monotonic key; SCAN returns paginated key/value pairs after an exclusive cursor. | Complete |
| `FrameAccumulator.java` | Per-connection byte accumulator; buffers partial reads until a complete frame is ready. | Complete |
| `ConnectionContext.java` | Holds per-connection state (accumulator, pending write buffers). | Complete |
| `DashboardServer.java` | Optional HTTP status dashboard — not required for memory-layer operation. | Present (optional) |

### `src/main/java/Yggdrasil/core/` — Storage Engine

| File | Key Role |
|------|---------|
| `StorageManager.java` | Page file I/O; reads and writes 4 KiB authenticated encrypted pages using Ascon-128. |
| `WalManager.java` | Write-ahead log: appends redo records, replays on restart, truncates after checkpoint. |
| `BPlusTree.java` | Integer-keyed B+Tree index over page storage; supports insert, find, range scan, and delete. |
| `BTreeNode.java` | Abstract B+Tree node base. |
| `InternalNode.java` | Internal (routing) B+Tree node. |
| `LeafNode.java` | Leaf (data) B+Tree node. |
| `RecordManager.java` | Coordinates `StorageManager` + `BPlusTree` + `WalManager`; exposes insert, find, scan, delete, `redoRecovery()`. |
| `HeapFile.java` | Variable-length record heap backing physical pages. |
| `SlottedPage.java` | Slotted-page layout within a 4 KiB page. |
| `Page.java` | Raw page abstraction. |
| `PageFactory.java` | Creates and deserializes typed pages. |
| `Metadata.java` | Page-level metadata management. |
| `TupleID.java` | Physical record location (page ID + slot). |
| `PageAuthenticationException.java` | Thrown when Ascon-128 authentication tag check fails. |

### `src/main/java/Yggdrasil/crypto/`

| File | Role |
|------|------|
| `Ascon128Engine.java` | Pure-Java, zero-dependency implementation of Ascon-128 AEAD. Used by `StorageManager` to authenticate and encrypt every 4 KiB page. |

### `src/test/java/Yggdrasil/` — Java Test Suite (16 classes)

| Test File | Coverage |
|-----------|---------|
| `TcpIntegrationTest.java` | End-to-end APPEND + SCAN framing and response validation via a live `MemoryStorageServer`. Primary cross-language compatibility check. |
| `ComprehensiveEngineTestSuite.java` | Full-stack storage: insert, find, scan, delete across large data sets. |
| `EngineTestSuite.java` | Core unit tests for `RecordManager` and `BPlusTree`. |
| `EngineMetricsTestSuite.java` | Performance and metrics tracking for storage operations. |
| `EngineBenchmarkSuite.java` | Throughput benchmarks (insert, read, mixed workload). |
| `FuzzyEngineTestSuite.java` | Randomised/fuzz tests for storage edge cases. |
| `AsconNistVectorTest.java` | NIST test vectors for `Ascon128Engine`. |
| `StorageSecurityTest.java` | Verifies that tampered pages produce `PageAuthenticationException`. |
| `TamperSecurityTest.java` | Direct page-level tampering checks. |
| `WalManagerSelfTest.java` | WAL append and replay unit tests. |
| `WalManagerRegressionTest.java` | Regression cases for known WAL bugs. |
| `WalCrashDebugTest.java` | Simulated crash and recovery scenarios. |
| `MemoryLeakDetectionTest.java` | Checks that long-running operations do not leak page or record handles. |
| `ServerConcurrencyTest.java` | Multi-threaded client access to `NioServer`. |
| `StorageCapacityTest.java` | Tests behaviour at or near storage limits. |
| `RunTests.java` | Test harness entry point that executes suites without a build tool. |

---

## What Has Been Covered

### Implemented and Tested

- **Binary wire protocol** — 14-byte framed TCP transport with APPEND and SCAN opcodes, request-ID echo, and bounded payloads (16 MiB). Golden-tested from both Java and Rust.
- **Java storage engine** — 4 KiB Ascon-128 authenticated encrypted pages, integer-keyed B+Tree, slotted heap pages, WAL with redo recovery. Zero third-party runtime dependencies.
- **Rust storage client** (`StorageClient`) — connects over TCP, sends APPEND/SCAN, validates responses strictly; mock-server unit test verifies frame encoding.
- **Exact-text memory API** (`MemoryLayer`) — create, update, get; SHA-256 hash verification on every revision; chunked writes (3 000 B/chunk); commit-marker protocol that makes incomplete writes invisible after restart.
- **Diff-based revision storage** — rev 1 stored as a full UTF-8 snapshot; rev N stored as a `bincode`-serialized `Patch<String>` generated by `java-diff-utils-rs` Myers/histogram diff.
- **WAL-safe reload** — startup full-scan reassembles the memory map; only committed batches are visible; partial/uncommitted chunks are silently discarded.
- **CLI binary** — `create`, `update`, `get` commands over a live Java storage server.
- **Test coverage** — 4 Rust unit tests (memory + storage modules), 16 Java test classes including TCP integration test, upstream diff-crate tests.
- **Build and run scripts** — PowerShell `run-storage.ps1` and `test.ps1`.

### Not Yet Implemented (Remaining Delivery Plan from `DESIGN.md`)

| Item | Priority | Notes |
|------|----------|-------|
| `delete` operation | Medium | No way to remove a memory ID from the store. Required for full API symmetry. |
| Scalable durable catalog | High | Startup does a full SCAN of all records. Must be replaced with a durable ID-to-revision index so cold-start time does not grow linearly with store size. |
| Idempotent multi-chunk commit | Medium | Multi-chunk writes are not crash-idempotent; a lost response after commit could cause the client to retry and hit the duplicate-key guard incorrectly. |
| Semantic memory path | Low — future | Summaries, embedding-provider interface, ANN similarity search. No vector index or provider is present. |
| Tier policy | Low — future | Tier 1/2/3 promotion and demotion logic; recency/frequency-based priority queue. |
| Authenticated client transport | High — security | The TCP connection is plaintext and unauthenticated. Must be added before any non-loopback use. |
| TLS | High — security | Required alongside client authentication for remote deployment. |
| Key provisioning and rotation | Medium — security | `YGGDRASIL_ROOT_KEY_HEX` is a static environment variable; no rotation, backup, or recovery path is defined. |
| WAL / metadata plaintext audit | Medium — security | WAL records and metadata indexes may contain plaintext even though page data is encrypted. Needs explicit review and documentation. |
| End-to-end restart integration test | Medium | Automate: start Java server, create/update via Rust CLI, restart Java server, verify exact retrieval. |
| Latency and compression benchmarks | Low | No measured figures yet; do not publish until benchmarked on a documented workload. |

---

## Dependency Graph (Summary)

```
CLI (main.rs)
  +-- MemoryLayer<StorageClient>  (memory.rs)
        +-- StorageClient         (storage.rs)   <-- Java MemoryStorageServer (TCP loopback)
        |                                               +-- NioServer + RequestDispatcher
        |                                                     +-- RecordManager + BPlusTree + WalManager
        |                                                           +-- StorageManager (Ascon-128 pages)
        +-- java-diff-utils-rs    (local path dep)
              +-- DiffUtils::diff / Patch::apply_to
```

---

*This file is maintained alongside the project. Update the "Not Yet Implemented" table as items are completed.*