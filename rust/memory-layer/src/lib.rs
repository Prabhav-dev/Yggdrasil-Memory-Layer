#![forbid(unsafe_code)]

mod memory;
mod storage;

pub use memory::{Memory, MemoryId, MemoryLayer};
pub use storage::{RecordStore, StorageClient, StoredRecord};

use std::fmt;
use std::io;

pub type Result<T> = std::result::Result<T, MemoryError>;

#[derive(Debug)]
pub enum MemoryError {
    Io(io::Error),
    Protocol(String),
    Remote { status: u8, message: String },
    InvalidRecord(String),
    Patch(String),
    Serialization(String),
    AlreadyExists,
    NotFound,
}

impl fmt::Display for MemoryError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "storage I/O failed: {error}"),
            Self::Protocol(message) => write!(formatter, "storage protocol error: {message}"),
            Self::Remote { status, message } => {
                write!(formatter, "storage server status {status:#04x}: {message}")
            }
            Self::InvalidRecord(message) => write!(formatter, "invalid memory record: {message}"),
            Self::Patch(message) => write!(formatter, "revision patch failed: {message}"),
            Self::Serialization(message) => write!(formatter, "revision serialization failed: {message}"),
            Self::AlreadyExists => formatter.write_str("memory ID already exists"),
            Self::NotFound => formatter.write_str("memory ID was not found"),
        }
    }
}

impl std::error::Error for MemoryError {}

impl From<io::Error> for MemoryError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}