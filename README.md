# Yggdrasil Memory Layer

Standalone exact-text memory prototype composed from local copies of:

- `java-storage/`: the Yggdrasil Java encrypted-page storage engine.
- `rust/java-diff-utils-rs/`: the Rust port of Java diff/patch behavior, copied from source commit `26ebbda`.
- `rust/memory-layer/`: the new Rust client and exact-revision memory API.

The copied components are independent of the two sibling repositories. Changes in this folder do not update those source repositories.

## What Works

- Create, update, and retrieve UTF-8 text memories with a stable 128-bit ID.
- Store revision 1 as a snapshot and later revisions as patches generated and applied by `java-diff-utils-rs`.
- Split revision payloads into page-safe chunks, add a commit marker, and verify SHA-256 parent/result hashes when loading.
- Rebuild the current memory map by scanning the Java store after restart.
- Keep the standalone Java network listener on `127.0.0.1`.

This is an exact-text MVP, not the full proposed semantic-memory system. It has no embeddings, summaries, automatic tiering, delete operation, persistent ID index, or client authentication/TLS. The storage listener must remain local-only.

## Prerequisites

- JDK 17 or newer.
- Rust toolchain with Cargo.
- Windows PowerShell for the helper scripts; the Java and Rust components can also be built directly with their toolchains.

## Build and Test

From this folder:

```powershell
.scripts\test.ps1
```

Or run the suites directly:

```powershell
cargo test --workspace
$sources = Get-ChildItem java-storage\src\main\java,java-storage\src\test\java -Recurse -Filter *.java | ForEach-Object FullName
New-Item -ItemType Directory -Force java-storage\out | Out-Null
javac -encoding UTF-8 -d java-storage\out $sources
Push-Location java-storage
java -cp out Yggdrasil.TcpIntegrationTest
Pop-Location
```

## Run Locally

Set a 16-byte storage key as 32 hexadecimal characters. Keep it stable for the lifetime of the data directory and do not commit it:

```powershell
$env:YGGDRASIL_ROOT_KEY_HEX = '<32 hex characters>'
.\scripts\run-storage.ps1 -Port 8765 -DataDirectory .\data
```

In a second terminal, build the Rust client and create/update/retrieve a file:

```powershell
cargo build -p yggdrasil-memory-layer
Set-Content -NoNewline .\memory.txt 'fn answer() -> i32 { 42 }'
cargo run -p yggdrasil-memory-layer -- 127.0.0.1:8765 create 00112233445566778899aabbccddeeff .\memory.txt
Set-Content -NoNewline .\memory.txt 'fn answer() -> i32 { 43 }'
cargo run -p yggdrasil-memory-layer -- 127.0.0.1:8765 update 00112233445566778899aabbccddeeff .\memory.txt
cargo run -p yggdrasil-memory-layer -- 127.0.0.1:8765 get 00112233445566778899aabbccddeeff
```

`get` writes the memory bytes to standard output and its revision number to standard error. The Java server must be restarted with the same key and data directory to validate recovery.

## Source Notes

The copied Rust diff crate retains its upstream license and tests. The design details and known limits are in [DESIGN.md](DESIGN.md). The original project folders remain separate and unchanged.