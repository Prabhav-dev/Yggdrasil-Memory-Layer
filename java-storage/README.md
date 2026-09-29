# Yggdrasil

## Zero Dependency Hackathon — August 28–31
### Track: Data & Storage

Yggdrasil is a Java-based embedded storage engine and networking middleware designed for low-level data durability, authenticated page security, and index persistence without external dependencies. The strongest fit is clearly **Data & Storage**, though it also touches adjacent work in parsing, developer tooling, web dashboards, and security.

### Why this fits the track

- **Data & Storage** — primary focus: page layout, heap allocation, B+Tree indexing, WAL recovery, and record serialization.
- **Parser** — adjacent through binary wire protocol decoding and frame reconstruction in `WireProtocol.java` and `FrameAccumulator.java`.
- **Developer Tools** — adjacent through the Swing monitoring console and raw page inspection panels.
- **Web & Security** — adjacent through the dashboard server, authenticated encryption, and tamper detection.

Yggdrasil combines 4 KiB encrypted page storage, a B+Tree index, write-ahead logging, and NIST-standardized Ascon-128 AEAD protection in a single zero-dependency Java project. It also includes a Swing-based visualizer for live telemetry, index mutations, and security state.

---

## Quick Start

Clone the repository:

```powershell
git clone https://github.com/Prabhav-dev/Yggdrasil-Blind_Storage.git
cd Yggdrasil-Blind_Storage
```

Open the project root in VS Code. The workspace folder should contain `src/`, `web/`, and `README.md`.

If Java tooling reports package mismatch warnings, confirm that the source paths are configured as:

```json
{
  "java.project.sourcePaths": [
    "src/main/java",
    "src/test/java"
  ]
}
```

Use JDK 17 or newer.

---

## Build and Run

From the project root:

```powershell
New-Item -ItemType Directory -Force out | Out-Null
$sources = Get-ChildItem src/main/java,src/test/java -Recurse -Filter *.java | ForEach-Object FullName
javac -encoding UTF-8 -d out $sources
```

Run the full test suite:

```powershell
java -cp out Yggdrasil.RunTests
```

Run the visual supervisor console:

```powershell
java -cp out Yggdrasil.gui.SupervisorConsole
```

The project has no external runtime dependencies and is designed to compile and run directly from the repository root.

---

## Core Features

- **Authenticated page security:** 4 KiB pages are encrypted with Ascon-128 AEAD and bound to page-specific associated data to prevent frame-swap attacks.
- **Encrypted metadata layer:** Page 0 stores encrypted metadata and root pointers to keep recovery state private on restart.
- **B+Tree indexing:** Supports point lookups, range queries, node splits, and merges over persisted records.
- **Write-ahead logging:** WAL records page after-images and tuple updates, and discards torn trailing records on recovery.
- **NIO networking layer:** Uses non-blocking Java I/O with frame accumulation and request dispatching.
- **Interactive inspector GUI:** Provides live B+Tree visualization, security monitoring, hex inspection, and telemetry.

---

## Dependency Proof

I verified the project archive contains no dependency manifests:

```powershell
Get-ChildItem -Recurse -Include "pom.xml", "*.gradle", "*.gradle.kts" | Select-String -Pattern "<dependency>", "implementation", "api", "compile"
```

This checks Maven/Gradle build files for dependency declarations. If it returns no results, there are no dependency manifests or direct dependency declarations in the project.

For Java import analysis, use:

```powershell
Get-ChildItem -Recurse -Filter "*.java" | Select-String -Pattern "^import (?!java\.|javax\.|sun\.|com\.sun\.)"
```

This helps confirm whether custom project imports are being used beyond the standard Java and JDK packages.

---

## Package Killer

This project intentionally replaces common frameworks with direct Java implementations:

| Instead of | Implemented here |
| --- | --- |
| Netty | `NioServer.java` using raw `java.nio.channels` |
| BouncyCastle / Commons Crypto | `Ascon128Engine.java` with hand-rolled Ascon-128 AEAD |
| Hibernate / JPA | `RecordManager`, `HeapFile`, and `BPlusTree` |
| Protobuf / Jackson | `WireProtocol.java` with manual `ByteBuffer` packing |
| Spring Boot / Javalin | `DashboardServer.java` using `com.sun.net.httpserver` |
| JUnit / Mockito | Aggregated handwritten test runner in `RunTests` |

---

## GUI Modules

The `Yggdrasil.gui` package provides a supervisor dashboard for live inspection:

- **SupervisorConsole** — main engine dashboard and control surface.
- **BPlusTreeCanvas** — visualizes node layout, splits, and merges.
- **HexInspectorPanel** — inspects page bytes, headers, and slot offsets.
- **SecurityPanel** — displays authentication and tamper status.
- **EngineTelemetry / ControlPanel** — tracks throughput, page state, and live engine actions.

---

## Repository Structure

```text
Yggdrasil-Blind_Storage/
├── BUG_FIX_REPORT.md
├── README.md
├── web/
│   └── index.html
└── src/
    ├── main/
    │   └── java/
    │       └── Yggdrasil/
    │           ├── ConnectionContext.java
    │           ├── DashboardServer.java
    │           ├── FrameAccumulator.java
    │           ├── NioServer.java
    │           ├── RequestDispatcher.java
    │           ├── WireProtocol.java
    │           ├── core/
    │           │   ├── BPlusTree.java
    │           │   ├── BTreeNode.java
    │           │   ├── HeapFile.java
    │           │   ├── InternalNode.java
    │           │   ├── LeafNode.java
    │           │   ├── Metadata.java
    │           │   ├── Page.java
    │           │   ├── PageAuthenticationException.java
    │           │   ├── PageFactory.java
    │           │   ├── RecordManager.java
    │           │   ├── SlottedPage.java
    │           │   ├── StorageManager.java
    │           │   ├── TupleID.java
    │           │   └── WalManager.java
    │           ├── crypto/
    │           │   └── Ascon128Engine.java
    │           └── gui/
    │               ├── BPlusTreeCanvas.java
    │               ├── ControlPanel.java
    │               ├── EngineObserver.java
    │               ├── EngineTelemetry.java
    │               ├── HexInspectorPanel.java
    │               ├── SecurityPanel.java
    │               └── SupervisorConsole.java
    └── test/
        └── java/
            └── Yggdrasil/
                ├── AsconNistVectorTest.java
                ├── ComprehensiveEngineTestSuite.java
                ├── EngineBenchmarkSuite.java
                ├── EngineMetricsTestSuite.java
                ├── EngineTestSuite.java
                ├── FuzzyEngineTestSuite.java
                ├── MemoryLeakDetectionTest.java
                ├── RunTests.java
                ├── ServerConcurrencyTest.java
                ├── StorageSecurityTest.java
                ├── TamperSecurityTest.java
                ├── TcpIntegrationTest.java
                ├── WalCrashDebugTest.java
                ├── WalManagerRegressionTest.java
                └── WalManagerSelfTest.java
```

---

## Storage Capacity Notes

Yggdrasil uses encrypted 4,096-byte pages. Each physical page includes 16 bytes of nonce and 16 bytes of authentication tag, leaving 4,064 bytes of usable decrypted payload. Page 0 is reserved for encrypted metadata, and page numbers are stored as signed Java `int` values.

The verified page-number limit is `2^31 - 1 = 2,147,483,647`, which yields a logical page-address span of `2^43` bytes, or about 8 TiB. This is an address-space ceiling, not a promise of 8.7 TB of usable record storage. B+Tree pages, metadata overflow pages, WAL files, and filesystem limits reduce practical storage capacity.

The engine also includes a benchmark suite for throughput and correctness, but the normal benchmark should not be mistaken for proof of huge-capacity physical storage.

---

## Contributors

- Rishikeshav Sanjay Jha
- Sejal Santosh Kumavat
- Prabhav .D. Dwiwedi
- Ayush Jha

---

## Summary

Yggdrasil is a research-oriented, zero-dependency Java storage engine focused on resilient persistence, authenticated storage, and low-level systems design. It is intentionally built without common frameworks, making it a useful demonstration of how a database-like engine can be assembled directly on top of the Java standard library.

For a safe Windows addressability probe, run this outside the normal test
runner. It creates a sparse file and should report `8796093022208` bytes while
using very little physical space:

```powershell
$path = Join-Path $env:TEMP "yggdrasil-8tib-probe.bin"
New-Item -ItemType File -Path $path -Force | Out-Null
fsutil sparse setflag $path
$length = ([int64]1 -shl 43)
$stream = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite)
$stream.SetLength($length)
$stream.Position = $length - 4096
$stream.WriteByte(0x59)
$stream.Flush()
$stream.Dispose()
(Get-Item $path).Length
Remove-Item $path
```

Until the physical-fill test passes, the responsible claim is **8 TiB
address-space ceiling**, not **8.7 TB of real user storage**.
## Visual Supervisor Console

After compiling, start the Swing visual console with:

```powershell
java -cp out Yggdrasil.gui.SupervisorConsole
```

The dashboard can also be served from the `web` directory when started by the
console or an embedding application. `DashboardServer` supports both live
engine telemetry and an explicitly simulated standalone mode; simulated values
must not be treated as production metrics.

## Compatibility and Scope

`RecordManager` and `BPlusTree` currently use coarse synchronization for
serialized correctness. This is not fine-grained transaction isolation. Read
the [bug-fix and architecture report](BUG_FIX_REPORT.md) before building a
multi-threaded access model or relying on crash guarantees beyond the tested
WAL path.

### Storage Format Compatibility

Modern database files write encrypted metadata headers to Page 0. Plaintext
metadata files created by legacy versions are not read automatically and must
be migrated or recreated. The project is an educational storage engine, not a
drop-in production database.