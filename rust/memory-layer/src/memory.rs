use crate::storage::{RecordStore, SCAN_PAGE_SIZE};
use crate::{MemoryError, Result, StorageClient};
use java_diff_utils_rs::{DiffUtils, Patch};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fmt;
use std::str::FromStr;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

const RECORD_MAGIC: &[u8; 4] = b"YML1";
const RECORD_VERSION: u8 = 1;
const HEADER_SIZE: usize = 122;
const CHUNK_SIZE: usize = 3000;
const MAX_CHUNKS: usize = 24_000;
const MAX_CONTENT_SIZE: usize = 64 * 1024 * 1024;
const KIND_SNAPSHOT: u8 = 1;
const KIND_DELTA: u8 = 2;
const KIND_COMMIT: u8 = 3;
const NO_HASH: [u8; 32] = [0; 32];
static NEXT_BATCH_COUNTER: AtomicU32 = AtomicU32::new(1);

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct MemoryId(pub [u8; 16]);

impl FromStr for MemoryId {
    type Err = MemoryError;

    fn from_str(value: &str) -> Result<Self> {
        if value.len() != 32 {
            return Err(MemoryError::InvalidRecord("memory IDs must be 32 hexadecimal characters".into()));
        }
        let mut id = [0; 16];
        for (index, byte) in id.iter_mut().enumerate() {
            let start = index * 2;
            *byte = u8::from_str_radix(&value[start..start + 2], 16)
                .map_err(|_| MemoryError::InvalidRecord("memory ID contains a non-hexadecimal character".into()))?;
        }
        Ok(Self(id))
    }
}

impl fmt::Display for MemoryId {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in self.0 {
            write!(formatter, "{byte:02x}")?;
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Memory {
    id: MemoryId,
    revision: u64,
    storage_key: i32,
    content: String,
    content_hash: [u8; 32],
}

impl Memory {
    pub fn id(&self) -> MemoryId {
        self.id
    }

    pub fn revision(&self) -> u64 {
        self.revision
    }

    pub fn storage_key(&self) -> i32 {
        self.storage_key
    }

    pub fn content(&self) -> &str {
        &self.content
    }
}

pub struct MemoryLayer<S: RecordStore> {
    storage: S,
    memories: HashMap<MemoryId, Memory>,
}

impl MemoryLayer<StorageClient> {
    pub fn connect(address: &str) -> Result<Self> {
        Self::load(StorageClient::connect(address)?)
    }
}

impl<S: RecordStore> MemoryLayer<S> {
    pub fn load(mut storage: S) -> Result<Self> {
        let mut memories = HashMap::new();
        let mut batches: HashMap<BatchKey, PendingBatch> = HashMap::new();
        let mut cursor = i64::from(i32::MIN) - 1;

        loop {
            let records = storage.scan_after(cursor, SCAN_PAGE_SIZE)?;
            if records.is_empty() {
                break;
            }
            let page_count = records.len();
            for record in records {
                if i64::from(record.key) <= cursor {
                    return Err(MemoryError::Protocol("scan cursor did not advance".into()));
                }
                cursor = i64::from(record.key);
                if let Some(fragment) = decode_fragment(&record.value)? {
                    ingest_fragment(fragment, record.key, &mut batches, &mut memories)?;
                }
            }
            if page_count < SCAN_PAGE_SIZE {
                break;
            }
        }

        Ok(Self { storage, memories })
    }

    pub fn create(&mut self, id: MemoryId, content: &str) -> Result<u64> {
        if self.memories.contains_key(&id) {
            return Err(MemoryError::AlreadyExists);
        }
        self.write_revision(id, 1, &NO_HASH, KIND_SNAPSHOT, content.as_bytes())
    }

    pub fn update(&mut self, id: MemoryId, content: &str) -> Result<u64> {
        let previous = self.memories.get(&id).cloned().ok_or(MemoryError::NotFound)?;
        if previous.content == content {
            return Ok(previous.revision);
        }

        let (source_lines, _) = split_lines(&previous.content);
        let (target_lines, trailing_newline) = split_lines(content);
        let patch = DiffUtils::diff(&source_lines, &target_lines, None);
        let mut delta = vec![u8::from(trailing_newline)];
        delta.extend(
            bincode::serialize(&patch)
                .map_err(|error| MemoryError::Serialization(error.to_string()))?,
        );
        let revision = previous.revision + 1;
        self.write_revision(id, revision, &previous.content_hash, KIND_DELTA, &delta)
    }

    pub fn get(&self, id: MemoryId) -> Option<&Memory> {
        self.memories.get(&id)
    }

    pub fn len(&self) -> usize {
        self.memories.len()
    }

    pub fn is_empty(&self) -> bool {
        self.memories.is_empty()
    }

    fn write_revision(
        &mut self,
        id: MemoryId,
        revision: u64,
        parent_hash: &[u8; 32],
        kind: u8,
        data: &[u8],
    ) -> Result<u64> {
        if data.len() > MAX_CONTENT_SIZE {
            return Err(MemoryError::InvalidRecord("memory content exceeds 64 MiB".into()));
        }
        let chunk_count = data.len().max(1).div_ceil(CHUNK_SIZE);
        if chunk_count > MAX_CHUNKS {
            return Err(MemoryError::InvalidRecord("memory revision has too many chunks".into()));
        }
        let result_content = if kind == KIND_SNAPSHOT {
            std::str::from_utf8(data)
                .map_err(|_| MemoryError::InvalidRecord("memory content must be UTF-8".into()))?
                .to_owned()
        } else {
            let prior = self.memories.get(&id).ok_or(MemoryError::NotFound)?;
            let (source_lines, _) = split_lines(&prior.content);
            let patch: Patch<String> = bincode::deserialize(&data[1..])
                .map_err(|error| MemoryError::Serialization(error.to_string()))?;
            let target_lines = patch
                .apply_to(&source_lines)
                .map_err(|error| MemoryError::Patch(error.to_string()))?;
            join_lines(&target_lines, data.first() == Some(&1))
        };
        if result_content.len() > MAX_CONTENT_SIZE {
            return Err(MemoryError::InvalidRecord("memory content exceeds 64 MiB".into()));
        }

        let result_hash = hash_content(&result_content);
        let batch_id = new_batch_id();
        for index in 0..chunk_count {
            let start = index * CHUNK_SIZE;
            let end = (start + CHUNK_SIZE).min(data.len());
            let chunk = if data.is_empty() { &[][..] } else { &data[start..end] };
            let value = encode_fragment(&Fragment {
                kind,
                id,
                batch_id,
                revision,
                chunk_index: index as u32,
                chunk_count: chunk_count as u32,
                parent_hash: *parent_hash,
                result_hash,
                data: chunk.to_vec(),
            })?;
            self.storage.append(&value)?;
        }

        let commit = encode_fragment(&Fragment {
            kind: KIND_COMMIT,
            id,
            batch_id,
            revision,
            chunk_index: 0,
            chunk_count: chunk_count as u32,
            parent_hash: *parent_hash,
            result_hash,
            data: Vec::new(),
        })?;
        let storage_key = self.storage.append(&commit)?;
        self.memories.insert(
            id,
            Memory {
                id,
                revision,
                storage_key,
                content: result_content,
                content_hash: result_hash,
            },
        );
        Ok(revision)
    }
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
struct BatchKey {
    id: MemoryId,
    revision: u64,
    batch_id: [u8; 16],
}

struct Fragment {
    kind: u8,
    id: MemoryId,
    batch_id: [u8; 16],
    revision: u64,
    chunk_index: u32,
    chunk_count: u32,
    parent_hash: [u8; 32],
    result_hash: [u8; 32],
    data: Vec<u8>,
}

struct PendingBatch {
    kind: u8,
    chunk_count: usize,
    parent_hash: [u8; 32],
    result_hash: [u8; 32],
    chunks: Vec<Option<Vec<u8>>>,
}

impl PendingBatch {
    fn new(fragment: &Fragment) -> Result<Self> {
        let chunk_count = fragment.chunk_count as usize;
        if chunk_count == 0 || chunk_count > MAX_CHUNKS {
            return Err(MemoryError::InvalidRecord("invalid chunk count".into()));
        }
        Ok(Self {
            kind: fragment.kind,
            chunk_count,
            parent_hash: fragment.parent_hash,
            result_hash: fragment.result_hash,
            chunks: vec![None; chunk_count],
        })
    }

    fn validate(&self, fragment: &Fragment) -> Result<()> {
        if self.kind != fragment.kind
            || self.chunk_count != fragment.chunk_count as usize
            || self.parent_hash != fragment.parent_hash
            || self.result_hash != fragment.result_hash
        {
            return Err(MemoryError::InvalidRecord("inconsistent revision chunk metadata".into()));
        }
        Ok(())
    }
}

fn ingest_fragment(
    fragment: Fragment,
    storage_key: i32,
    batches: &mut HashMap<BatchKey, PendingBatch>,
    memories: &mut HashMap<MemoryId, Memory>,
) -> Result<()> {
    let key = BatchKey {
        id: fragment.id,
        revision: fragment.revision,
        batch_id: fragment.batch_id,
    };
    if fragment.kind == KIND_COMMIT {
        if !fragment.data.is_empty() {
            return Err(MemoryError::InvalidRecord("commit record must not contain data".into()));
        }
        let batch = batches
            .remove(&key)
            .ok_or_else(|| MemoryError::InvalidRecord("commit has no revision chunks".into()))?;
        if batch.chunk_count != fragment.chunk_count as usize
            || batch.parent_hash != fragment.parent_hash
            || batch.result_hash != fragment.result_hash
            || batch.chunks.iter().any(Option::is_none)
        {
            return Err(MemoryError::InvalidRecord("committed revision is incomplete".into()));
        }
        let data: Vec<u8> = batch.chunks.into_iter().flatten().flatten().collect();
        let content = materialize(fragment.id, fragment.revision, batch.kind, &batch.parent_hash, &batch.result_hash, &data, memories)?;
        let memory = Memory {
            id: fragment.id,
            revision: fragment.revision,
            storage_key,
            content,
            content_hash: batch.result_hash,
        };
        match memories.get(&fragment.id) {
            Some(existing) if existing.revision == memory.revision && existing.content_hash == memory.content_hash => {}
            Some(existing) if existing.revision >= memory.revision => {
                return Err(MemoryError::InvalidRecord("conflicting or out-of-order committed revision".into()));
            }
            _ => {
                memories.insert(fragment.id, memory);
            }
        }
        return Ok(());
    }

    if fragment.kind != KIND_SNAPSHOT && fragment.kind != KIND_DELTA {
        return Err(MemoryError::InvalidRecord("unknown fragment kind".into()));
    }
    if fragment.revision == 0
        || fragment.chunk_count == 0
        || fragment.chunk_count as usize > MAX_CHUNKS
        || fragment.chunk_index >= fragment.chunk_count
        || fragment.data.len() > CHUNK_SIZE
    {
        return Err(MemoryError::InvalidRecord("invalid revision chunk bounds".into()));
    }
    let batch = match batches.entry(key) {
        std::collections::hash_map::Entry::Occupied(entry) => entry.into_mut(),
        std::collections::hash_map::Entry::Vacant(entry) => entry.insert(PendingBatch::new(&fragment)?),
    };
    batch.validate(&fragment)?;
    let slot = &mut batch.chunks[fragment.chunk_index as usize];
    if let Some(existing) = slot {
        if existing != &fragment.data {
            return Err(MemoryError::InvalidRecord("conflicting duplicate revision chunk".into()));
        }
    } else {
        *slot = Some(fragment.data);
    }
    Ok(())
}

fn materialize(
    id: MemoryId,
    revision: u64,
    kind: u8,
    parent_hash: &[u8; 32],
    result_hash: &[u8; 32],
    data: &[u8],
    memories: &HashMap<MemoryId, Memory>,
) -> Result<String> {
    let content = match kind {
        KIND_SNAPSHOT if revision == 1 && parent_hash == &NO_HASH => String::from_utf8(data.to_vec())
            .map_err(|_| MemoryError::InvalidRecord("snapshot is not valid UTF-8".into()))?,
        KIND_DELTA if revision > 1 => {
            let previous = memories
                .get(&id)
                .ok_or_else(|| MemoryError::InvalidRecord("revision has no committed parent".into()))?;
            if previous.revision + 1 != revision || &previous.content_hash != parent_hash {
                return Err(MemoryError::InvalidRecord("revision parent does not match".into()));
            }
            let (&trailing_newline, patch_bytes) = data
                .split_first()
                .ok_or_else(|| MemoryError::InvalidRecord("empty delta payload".into()))?;
            if trailing_newline > 1 {
                return Err(MemoryError::InvalidRecord("invalid trailing-newline flag".into()));
            }
            let patch: Patch<String> = bincode::deserialize(patch_bytes)
                .map_err(|error| MemoryError::Serialization(error.to_string()))?;
            let (source_lines, _) = split_lines(&previous.content);
            let target_lines = patch
                .apply_to(&source_lines)
                .map_err(|error| MemoryError::Patch(error.to_string()))?;
            join_lines(&target_lines, trailing_newline == 1)
        }
        _ => return Err(MemoryError::InvalidRecord("revision kind and number disagree".into())),
    };
    if hash_content(&content) != *result_hash {
        return Err(MemoryError::InvalidRecord("revision content hash mismatch".into()));
    }
    Ok(content)
}

fn encode_fragment(fragment: &Fragment) -> Result<Vec<u8>> {
    if fragment.data.len() > CHUNK_SIZE {
        return Err(MemoryError::InvalidRecord("revision chunk exceeds the page-safe limit".into()));
    }
    let mut encoded = Vec::with_capacity(HEADER_SIZE + fragment.data.len());
    encoded.extend_from_slice(RECORD_MAGIC);
    encoded.push(RECORD_VERSION);
    encoded.push(fragment.kind);
    encoded.extend_from_slice(&fragment.id.0);
    encoded.extend_from_slice(&fragment.batch_id);
    encoded.extend_from_slice(&fragment.revision.to_be_bytes());
    encoded.extend_from_slice(&fragment.chunk_index.to_be_bytes());
    encoded.extend_from_slice(&fragment.chunk_count.to_be_bytes());
    encoded.extend_from_slice(&fragment.parent_hash);
    encoded.extend_from_slice(&fragment.result_hash);
    encoded.extend_from_slice(&(fragment.data.len() as u32).to_be_bytes());
    encoded.extend_from_slice(&fragment.data);
    Ok(encoded)
}

fn decode_fragment(record: &[u8]) -> Result<Option<Fragment>> {
    if !record.starts_with(RECORD_MAGIC) {
        return Ok(None);
    }
    if record.len() < HEADER_SIZE || record[4] != RECORD_VERSION {
        return Err(MemoryError::InvalidRecord("unsupported or truncated memory record".into()));
    }
    let data_length = u32::from_be_bytes(record[118..122].try_into().unwrap()) as usize;
    if record.len() != HEADER_SIZE + data_length {
        return Err(MemoryError::InvalidRecord("memory record length does not match its header".into()));
    }
    let mut id = [0; 16];
    id.copy_from_slice(&record[6..22]);
    let mut batch_id = [0; 16];
    batch_id.copy_from_slice(&record[22..38]);
    let mut parent_hash = [0; 32];
    parent_hash.copy_from_slice(&record[54..86]);
    let mut result_hash = [0; 32];
    result_hash.copy_from_slice(&record[86..118]);
    Ok(Some(Fragment {
        kind: record[5],
        id: MemoryId(id),
        batch_id,
        revision: u64::from_be_bytes(record[38..46].try_into().unwrap()),
        chunk_index: u32::from_be_bytes(record[46..50].try_into().unwrap()),
        chunk_count: u32::from_be_bytes(record[50..54].try_into().unwrap()),
        parent_hash,
        result_hash,
        data: record[HEADER_SIZE..].to_vec(),
    }))
}

fn split_lines(content: &str) -> (Vec<String>, bool) {
    let trailing_newline = content.ends_with('\n');
    if content.is_empty() {
        return (Vec::new(), false);
    }
    let body = if trailing_newline {
        &content[..content.len() - 1]
    } else {
        content
    };
    (body.split('\n').map(str::to_owned).collect(), trailing_newline)
}

fn join_lines(lines: &[String], trailing_newline: bool) -> String {
    let mut content = lines.join("\n");
    if trailing_newline {
        content.push('\n');
    }
    content
}

fn hash_content(content: &str) -> [u8; 32] {
    Sha256::digest(content.as_bytes()).into()
}

fn new_batch_id() -> [u8; 16] {
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    let process_id = std::process::id();
    let counter = NEXT_BATCH_COUNTER.fetch_add(1, Ordering::Relaxed);
    let mut batch_id = [0; 16];
    batch_id[..8].copy_from_slice(&timestamp.to_be_bytes());
    batch_id[8..12].copy_from_slice(&process_id.to_be_bytes());
    batch_id[12..].copy_from_slice(&counter.to_be_bytes());
    batch_id
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::StoredRecord;
    use std::cell::RefCell;
    use std::rc::Rc;

    #[derive(Clone, Default)]
    struct FakeStore(Rc<RefCell<Vec<StoredRecord>>>);

    impl RecordStore for FakeStore {
        fn append(&mut self, value: &[u8]) -> Result<i32> {
            let mut records = self.0.borrow_mut();
            let key = records.last().map_or(i32::MIN, |record| record.key + 1);
            records.push(StoredRecord { key, value: value.to_vec() });
            Ok(key)
        }

        fn scan_after(&mut self, after_key: i64, limit: usize) -> Result<Vec<StoredRecord>> {
            Ok(self.0.borrow().iter()
                .filter(|record| i64::from(record.key) > after_key)
                .take(limit)
                .cloned()
                .collect())
        }
    }

    fn test_id(value: u8) -> MemoryId {
        MemoryId([value; 16])
    }

    #[test]
    fn revisions_round_trip_exact_text_across_reload() {
        let storage = FakeStore::default();
        let mut layer = MemoryLayer::load(storage.clone()).unwrap();
        let original = "fn main() {\n    println!(\"hello\");\n}\n";
        assert_eq!(layer.create(test_id(7), original).unwrap(), 1);
        let revised = "fn main() {\n    println!(\"hello, world\");\n}\n";
        assert_eq!(layer.update(test_id(7), revised).unwrap(), 2);
        assert_eq!(layer.update(test_id(7), revised).unwrap(), 2);
        drop(layer);

        let reloaded = MemoryLayer::load(storage).unwrap();
        let memory = reloaded.get(test_id(7)).unwrap();
        assert_eq!(memory.revision(), 2);
        assert_eq!(memory.content(), revised);
    }

    #[test]
    fn line_ending_and_empty_content_changes_are_lossless() {
        let storage = FakeStore::default();
        let mut layer = MemoryLayer::load(storage.clone()).unwrap();
        layer.create(test_id(8), "\n").unwrap();
        layer.update(test_id(8), "").unwrap();
        layer.update(test_id(8), "a\r\nb\n\n").unwrap();
        drop(layer);

        let reloaded = MemoryLayer::load(storage).unwrap();
        assert_eq!(reloaded.get(test_id(8)).unwrap().content(), "a\r\nb\n\n");
    }

    #[test]
    fn partial_uncommitted_revision_is_ignored() {
        let storage = FakeStore::default();
        let mut layer = MemoryLayer::load(storage.clone()).unwrap();
        layer.create(test_id(9), "base").unwrap();
        drop(layer);

        let mut records = storage.0.borrow_mut();
        let fragment = Fragment {
            kind: KIND_SNAPSHOT,
            id: test_id(10),
            batch_id: [3; 16],
            revision: 1,
            chunk_index: 0,
            chunk_count: 1,
            parent_hash: NO_HASH,
            result_hash: hash_content("never committed"),
            data: b"never committed".to_vec(),
        };
        let value = encode_fragment(&fragment).unwrap();
        let key = records.last().unwrap().key + 1;
        records.push(StoredRecord { key, value });
        drop(records);

        let reloaded = MemoryLayer::load(storage).unwrap();
        assert!(reloaded.get(test_id(9)).is_some());
        assert!(reloaded.get(test_id(10)).is_none());
    }

    #[test]
    fn memory_id_hex_round_trips() {
        let id = test_id(0xab);
        assert_eq!(id.to_string().parse::<MemoryId>().unwrap(), id);
        assert!("1234".parse::<MemoryId>().is_err());
    }
}