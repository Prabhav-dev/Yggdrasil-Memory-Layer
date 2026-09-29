# STDLIB.md — Zero-Dependency Architecture Log

**Project:** Yggdrasil — encrypted, disk-resident B+Tree storage engine
**Language:** Java (JDK 17+)
**Dependency manifest:** none. No `pom.xml`, `build.gradle`, or equivalent exists in this repo. Compiled with `javac` directly against the JDK — there is nothing to resolve and nothing to hide. `find . -iname "*.xml" -o -iname "*.gradle"` returns no build-dependency files.

This log records, for every subsystem, what a normal implementation would reach for, what JDK stdlib primitive we used instead, and what we gave up by not using the library. Where relevant, it also notes a bug the tradeoff caused and how we found it — because "we hand-rolled it" is only a craft claim if it actually got exercised.

---

### 1. Networking — `java.nio.channels` instead of Netty

- **Normally used:** Netty, Tomcat, Spring Boot's embedded server.
- **stdlib replacement:** `Selector`, `ServerSocketChannel`, `SocketChannel`, `ByteBuffer` (`java.nio.channels`, `java.nio`).
- **Where:** `NioServer.java` runs a single-threaded reactor loop over a `Selector`; `ConnectionContext.java` tracks per-connection state by hand instead of relying on a framework's session object; `FrameAccumulator.java` reassembles TCP's byte stream into complete frames manually, since NIO gives you bytes, not messages.
- **Tradeoff:** Netty gives you battle-tested backpressure, zero-copy buffer pooling, and framing codecs out of the box. We had to write frame accumulation, connection lifecycle, and worker dispatch ourselves — and it showed: a TOCTOU race surfaced in `RequestDispatcher` because the legacy insert path performed the heap write and the index write as two separately-synchronized calls, and the shared worker pool could interleave two concurrent `PUT`s between them. Fixed by removing the unsafe constructor path entirely and forcing all mutations through a single atomic `RecordManager.insert()` call — a lock-ordering bug you don't hit for free with a framework, but also don't have to find yourself.

### 2. Concurrency — `java.util.concurrent` instead of a framework thread model

- **Normally used:** Netty's `EventLoopGroup`, Spring's task executors, or a DI-managed thread pool.
- **stdlib replacement:** `ThreadPoolExecutor` with an explicit `AbortPolicy`, `ConcurrentHashMap`, `AtomicLong` (`java.util.concurrent`, `java.util.concurrent.atomic`).
- **Where:** `NioServer` bounds query execution with a hand-configured `ThreadPoolExecutor` so a burst of requests fails loudly (`AbortPolicy`) rather than queuing unboundedly; `DashboardServer` tracks live counters (`simulatedPagesWritten`, `nextLogicalPageId`) with `AtomicLong` and device sessions in a `ConcurrentHashMap` instead of pulling in a metrics library.
- **Tradeoff:** No dependency-injected thread pool tuning, no built-in backpressure signaling to the client — just a bounded pool and a documented failure policy. Current concurrency control is coarse (`synchronized` blocks in `RecordManager`/`BPlusTree`, serialized rather than latch-based), a limitation we call out directly rather than paper over — see "Remaining limitations" in `BUG_FIX_REPORT.md`.

### 3. Storage engine — `FileChannel` / `RandomAccessFile` instead of an embedded store

- **Normally used:** RocksDB, MapDB, SQLite via JDBC.
- **stdlib replacement:** `java.nio.channels.FileChannel`, `java.io.RandomAccessFile`, raw `ByteBuffer` manipulation.
- **Where:** `StorageManager.java`, `HeapFile.java`, `SlottedPage.java`, `PageFactory.java` implement the entire 4KB slotted-page format, buffer management, and disk I/O from scratch — no embedded-DB engine underneath, no external caching layer.
- **Tradeoff:** We own every byte offset, which means we also own every off-by-one. The WAL replay bug we fixed this weekend is a direct example: the recovery loop read the operation-type byte from offset 0 of a 9-byte record header instead of offset 8 (after the 8-byte LSN), so opcodes looked invalid and recovery silently truncated the log at the first entry. A library WAL implementation doesn't let you make this mistake; ours did, and the fix (`WalManagerSelfTest`, `WalCrashDebugTest` now exercise a full kill-and-recover cycle) is the actual craft evidence, not just the design.

### 4. Cryptography — hand-rolled Ascon-128 instead of a crypto library

- **Normally used:** BouncyCastle, Apache Commons Crypto, or `javax.crypto` with AES-GCM.
- **stdlib replacement:** Nothing but native `long[]` arithmetic for the 320-bit permutation state (`Ascon128Engine.java`); `java.security.SecureRandom` for nonce generation, which is the one place we do lean on a JDK-provided primitive rather than implementing randomness ourselves.
- **Where:** Every page is individually encrypted and authenticated; the physical page ID is passed as Associated Data so ciphertext copied onto a different page slot fails the tag check.
- **Tradeoff:** No side-channel hardening review, no independent audit — verified only against NIST known-answer test vectors (`AsconNistVectorTest`), not against a fuzzed or adversarial corpus. Two real bugs came out of building this ourselves rather than trusting a library: nonces briefly leaked page-type bits before being made fully random, and page zero (metadata) was originally stored in plaintext before being brought under the same AEAD frame as every other page. We disclose both as fixed, not as if they never happened — that's the point of writing this file.

### 5. Wire protocol — manual byte-packing instead of Protobuf/JSON

- **Normally used:** Protocol Buffers, Jackson, Gson.
- **stdlib replacement:** `ByteBuffer` put/get calls against a fixed binary layout (`WireProtocol.java`).
- **Tradeoff:** No schema evolution, no cross-language codegen, no reflection-based (de)serialization safety net — every field offset is manual, and getting it wrong fails silently rather than throwing a schema-mismatch error. In exchange: no parsing overhead, no reflection cost, and a wire format small enough to read by hand with a hex dump.

### 6. Live telemetry — `com.sun.net.httpserver` instead of a web framework

- **Normally used:** Spring MVC, Javalin, an Express-style microframework.
- **stdlib replacement:** `com.sun.net.httpserver.HttpServer` — bundled with the JDK, not a third-party artifact.
- **Where:** `DashboardServer.java` exposes `/api/stats`, `/api/nodes`, `/api/events`, and `/api/iot/register` on a second HTTP listener, backed by `Executors.newFixedThreadPool(4)` as its executor.
- **Tradeoff:** No routing DSL, no middleware chain, no built-in JSON binding — every route is a manually registered `HttpHandler` and every response body is hand-serialized. In exchange, the dashboard reads real state (actual `StorageManager` page counts, an actual BFS walk of the live B+Tree) rather than needing a framework to explain why it isn't just returning mocked JSON.

### 7. UI / observability — Swing/AWT instead of JavaFX or a web frontend

- **Normally used:** JavaFX, Electron, or a separate web dashboard framework.
- **stdlib replacement:** `javax.swing`, `java.awt`.
- **Where:** `SupervisorConsole.java`, `HexInspectorPanel.java`, `BPlusTreeCanvas.java`, `SecurityPanel.java`, `ControlPanel.java` — real-time visualization of the tree, hex-level page inspection, and telemetry, built entirely on components that ship with the JDK.
- **Tradeoff:** Dated widget aesthetics compared to a modern web UI; no CSS, no component ecosystem. What it buys: zero additional runtime, and a UI that can directly call into `StorageManager` in-process instead of talking to it over a serialization boundary.

### 8. Testing — a hand-written runner instead of JUnit

- **Normally used:** JUnit, TestNG, Mockito.
- **stdlib replacement:** Plain Java assertions plus a custom aggregate entry point, `RunTests.java`.
- **Where:** `ComprehensiveEngineTestSuite`, `ServerConcurrencyTest`, `WalManagerSelfTest`, `WalCrashDebugTest`, `AsconNistVectorTest`, `MemoryLeakDetectionTest`, and `EngineBenchmarkSuite` all run through this entry point.
- **Tradeoff:** No test discovery, no parameterized tests, no mocking framework — every fixture and assertion is written by hand, and failures print raw stack traces instead of a formatted diff. In exchange, `RunTests` has no dependency footprint of its own and doubles as living documentation of exactly what's been verified (see the validation table in `BUG_FIX_REPORT.md`).

---

## Why this file matters more than it looks

Every tradeoff above traded a library's correctness guarantees for our own — and in at least three cases (WAL opcode offset, TOCTOU race in `RequestDispatcher`, nonce/metadata plaintext leakage) that trade produced a real bug that a mature library would likely have prevented by construction. We're listing them here instead of only in the bug-fix log because a zero-dependency claim without the scars to back it up is just an unverified assertion. These are the scars.

## Honest limits of "zero dependency"

- We still depend on the JDK itself — `javac`/JRE 17+ is the one thing this project cannot run without. That's the boundary the rules draw, and we stay inside it.
- `SecureRandom` is a JDK-provided cryptographic primitive we use rather than reimplement; we treat CSPRNG construction as out of scope for a from-scratch reimplementation, the same way the rules don't expect entrants to hand-roll the JVM.