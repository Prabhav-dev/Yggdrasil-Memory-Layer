# Bug-Fix and Architecture Status Report

Status: current implementation snapshot, validated 2026-08-28.

## Validation record

The repository compiles with the documented JDK workflow. The available test
run reached the core WAL, encryption, metadata, B+Tree, persistence, range
query, telemetry, and concurrency checks successfully. `RunTests` is the
authoritative aggregate entry point and also includes network, tamper,
resource-cleanup, and benchmark suites.

## Verified fixes

| Area | Resolution |
| --- | --- |
| Metadata initialization | Page zero is a full authenticated encrypted frame; it no longer stores root/allocation state in plaintext. |
| AEAD page swapping | The physical page number is associated data, so ciphertext copied to another page number fails authentication. |
| Nonce entropy | Nonces are fully random; page type is not written into nonce bytes. |
| Ascon permutation | The round constant is standards-compatible and the NIST known-answer test passes. |
| Internal-node reload | Only active keys and child references are decoded; inactive child references remain `-1`. |
| Pin accounting | Fetching an already pinned page increments its pin count. Internal rebalance paths release their target once. |
| Range-query cleanup | Leaf traversal uses `finally` for one unpin per acquired page. |
| Duplicate records | `RecordManager` checks the index before heap allocation; duplicate inserts are idempotent without orphaning a heap record. |
| Page LSN persistence | `setLsn` marks the page dirty. |
| Heap restart discovery | Encrypted metadata persists data-page IDs; restart does not decrypt the entire database merely to find heap pages. |
| Torn WAL records | Startup and recovery truncate incomplete trailing WAL records. |
| Flush failure | A dirty-page flush failure is propagated as `UncheckedIOException` and the in-memory page is retained. |
| WAL page replay | Page after-images are applied during recovery; WAL logging is suspended while replay runs. |
| Slotted-page corruption | Header and record bounds are validated before reads. |
| Network mutation routing | `RequestDispatcher` now requires a WAL-backed `RecordManager`; the legacy direct-storage constructor fails fast. |

## Additional verified coverage

| Area | Evidence |
| --- | --- |
| WAL standalone behavior | `WalManagerSelfTest` appends and replays records successfully. |
| Cold restart behavior | `WalCrashDebugTest` and the comprehensive suite recover persisted records after restart. |
| B+Tree structural changes | Leaf splits, cascading internal splits, underflow merges, root reset, and range boundaries pass. |
| Network back pressure | TCP integration and server-concurrency suites exercise framing, worker saturation, and connection limits. |
| Cryptographic compatibility | `AsconNistVectorTest` validates the implementation against NIST known-answer vectors. |
| Resource behavior | `MemoryLeakDetectionTest` and telemetry tests cover cleanup and cache accounting. |

## Design decisions

### Tombstones are retained intentionally

Tuple IDs encode `(page number, slot index)`. Removing or renumbering deleted slot headers during compaction would make B+Tree pointers refer to the wrong records. Tombstones are therefore retained and reused by later inserts; they do not grow without bound during normal insert/delete reuse cycles.

### Current concurrency model

The engine uses coarse synchronized operations in `RecordManager` and `BPlusTree`. This is a serialized-access safety mechanism, not fine-grained concurrency control.

## Remaining limitations

1. WAL attachment is currently supplied through `RecordManager`. Direct standalone use of `BPlusTree` without a configured `WalManager` is not crash-recoverable.
2. Structural recovery uses page after-images rather than a full transactional ARIES-style protocol. There are no transaction/commit records, undo, or isolation levels.
3. The buffer cache is a `WeakHashMap`, not a bounded, deterministic buffer pool with an eviction policy.
4. Metadata’s persisted data-page directory has finite page-zero capacity. A scalable implementation should use catalog/bitmap pages.
5. Existing plaintext-metadata databases require migration or recreation.
6. `DashboardServer` deliberately falls back to simulated telemetry when live inspection fails, and its demo IoT provisioning key is source-defined. Neither behavior is suitable for a security-sensitive deployment.
7. The server has bounded worker and connection capacity, but it does not provide authentication, authorization, TLS, request quotas per client, or durable device-session storage.

## Before implementing true concurrency

- Make WAL mandatory at storage-manager construction.
- Introduce transaction IDs, commit records, and recovery ordering rules.
- Replace broad synchronized methods with read/write page latches and a documented lock order.
- Implement a bounded buffer pool with explicit dirty-page and eviction behavior.
- Add fault-injection tests around every split, merge, root change, and WAL/page flush boundary.
- Add a regression test proving every network mutation remains WAL-backed.
- Replace dashboard demo secrets with injected configuration backed by an external secret store before deployment.
- Add protocol authentication and TLS, plus request-size and per-client rate limits.
