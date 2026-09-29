use std::env;
use std::fs;
use std::io::{self, Write};
use std::str::FromStr;
use yggdrasil_memory_layer::{MemoryId, MemoryLayer};

fn main() {
    if let Err(error) = run() {
        eprintln!("error: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = env::args().collect();
    if args.len() < 4 || args.len() > 5 {
        return Err("usage: memory-layer <host:port> <create|update|get> <32-hex-id> [file]".into());
    }
    let address = &args[1];
    let action = &args[2];
    let id = MemoryId::from_str(&args[3])?;
    let mut memory = MemoryLayer::connect(address)?;

    match action.as_str() {
        "create" | "update" if args.len() == 5 => {
            let bytes = fs::read(&args[4])?;
            let content = std::str::from_utf8(&bytes)?;
            let revision = if action == "create" {
                memory.create(id, content)?
            } else {
                memory.update(id, content)?
            };
            println!("{id} revision {revision}");
        }
        "get" if args.len() == 4 => {
            let record = memory.get(id).ok_or("memory ID was not found")?;
            io::stdout().write_all(record.content().as_bytes())?;
            eprintln!("revision {}", record.revision());
        }
        _ => return Err("usage: memory-layer <host:port> <create|update|get> <32-hex-id> [file]".into()),
    }
    Ok(())
}