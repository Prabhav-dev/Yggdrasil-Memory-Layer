use crate::{MemoryError, Result};
use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::Duration;

const MAGIC: u8 = b'Y';
const HEADER_SIZE: usize = 14;
const MAX_PAYLOAD_SIZE: usize = 16 * 1024 * 1024;
const OP_APPEND: u8 = 0x05;
const OP_SCAN: u8 = 0x06;
const OP_RESPONSE_OK: u8 = 0x80;
const OP_RESPONSE_ERROR: u8 = 0x81;
const STATUS_SUCCESS: u8 = 0x00;
pub(crate) const SCAN_PAGE_SIZE: usize = 128;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StoredRecord {
    pub key: i32,
    pub value: Vec<u8>,
}

pub trait RecordStore {
    fn append(&mut self, value: &[u8]) -> Result<i32>;
    fn scan_after(&mut self, after_key: i64, limit: usize) -> Result<Vec<StoredRecord>>;
}

pub struct StorageClient {
    stream: TcpStream,
    next_request_id: u64,
}

impl StorageClient {
    pub fn connect(address: &str) -> Result<Self> {
        let stream = TcpStream::connect(address)?;
        stream.set_read_timeout(Some(Duration::from_secs(20)))?;
        stream.set_write_timeout(Some(Duration::from_secs(20)))?;
        Ok(Self {
            stream,
            next_request_id: 1,
        })
    }

    fn request(&mut self, opcode: u8, payload: &[u8]) -> Result<Vec<u8>> {
        if payload.len() > MAX_PAYLOAD_SIZE {
            return Err(MemoryError::Protocol("request payload exceeds 16 MiB".into()));
        }

        let request_id = self.next_request_id;
        self.next_request_id = self.next_request_id.wrapping_add(1);
        let mut frame = Vec::with_capacity(HEADER_SIZE + payload.len());
        frame.push(MAGIC);
        frame.push(opcode);
        frame.extend_from_slice(&request_id.to_be_bytes());
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(payload);
        self.stream.write_all(&frame)?;

        let mut header = [0_u8; HEADER_SIZE];
        self.stream.read_exact(&mut header)?;
        if header[0] != MAGIC {
            return Err(MemoryError::Protocol("invalid response magic byte".into()));
        }
        let response_id = u64::from_be_bytes(header[2..10].try_into().unwrap());
        if response_id != request_id {
            return Err(MemoryError::Protocol(format!(
                "response request ID {response_id} did not match {request_id}"
            )));
        }
        let response_length = u32::from_be_bytes(header[10..14].try_into().unwrap()) as usize;
        if response_length > MAX_PAYLOAD_SIZE {
            return Err(MemoryError::Protocol("response payload exceeds 16 MiB".into()));
        }
        let mut response = vec![0; response_length];
        self.stream.read_exact(&mut response)?;

        match header[1] {
            OP_RESPONSE_OK => Ok(response),
            OP_RESPONSE_ERROR => {
                if response.is_empty() {
                    return Err(MemoryError::Protocol("empty error response".into()));
                }
                let status = response[0];
                let message = String::from_utf8_lossy(&response[1..]).into_owned();
                if status == STATUS_SUCCESS {
                    return Err(MemoryError::Protocol("error opcode carried success status".into()));
                }
                Err(MemoryError::Remote { status, message })
            }
            other => Err(MemoryError::Protocol(format!(
                "unexpected response opcode {other:#04x}"
            ))),
        }
    }
}

impl RecordStore for StorageClient {
    fn append(&mut self, value: &[u8]) -> Result<i32> {
        let response = self.request(OP_APPEND, value)?;
        if response.len() != 4 {
            return Err(MemoryError::Protocol("append response must contain one 32-bit key".into()));
        }
        Ok(i32::from_be_bytes(response.try_into().unwrap()))
    }

    fn scan_after(&mut self, after_key: i64, limit: usize) -> Result<Vec<StoredRecord>> {
        if limit == 0 || limit > 256 {
            return Err(MemoryError::Protocol("scan limit must be between 1 and 256".into()));
        }
        let mut request = Vec::with_capacity(12);
        request.extend_from_slice(&after_key.to_be_bytes());
        request.extend_from_slice(&(limit as u32).to_be_bytes());
        let response = self.request(OP_SCAN, &request)?;
        if response.len() < 4 {
            return Err(MemoryError::Protocol("scan response is missing its record count".into()));
        }

        let count = u32::from_be_bytes(response[0..4].try_into().unwrap()) as usize;
        if count > limit {
            return Err(MemoryError::Protocol("scan response exceeded its requested limit".into()));
        }
        let mut cursor = 4;
        let mut previous_key = after_key;
        let mut records = Vec::with_capacity(count);
        for _ in 0..count {
            if response.len().saturating_sub(cursor) < 8 {
                return Err(MemoryError::Protocol("truncated scan entry header".into()));
            }
            let key = i32::from_be_bytes(response[cursor..cursor + 4].try_into().unwrap());
            let value_length = u32::from_be_bytes(response[cursor + 4..cursor + 8].try_into().unwrap()) as usize;
            cursor += 8;
            if i64::from(key) <= previous_key || value_length > response.len().saturating_sub(cursor) {
                return Err(MemoryError::Protocol("scan entries are unordered or truncated".into()));
            }
            records.push(StoredRecord {
                key,
                value: response[cursor..cursor + value_length].to_vec(),
            });
            previous_key = i64::from(key);
            cursor += value_length;
        }
        if cursor != response.len() {
            return Err(MemoryError::Protocol("scan response contains trailing bytes".into()));
        }
        Ok(records)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::thread;

    #[test]
    fn client_round_trips_append_and_scan_frames() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            for (expected_opcode, response_payload) in [
                (OP_APPEND, 7_i32.to_be_bytes().to_vec()),
                (
                    OP_SCAN,
                    [1_u32.to_be_bytes().as_slice(), 7_i32.to_be_bytes().as_slice(), 3_u32.to_be_bytes().as_slice(), b"abc"].concat(),
                ),
            ] {
                let mut request_header = [0_u8; HEADER_SIZE];
                stream.read_exact(&mut request_header).unwrap();
                assert_eq!(request_header[0], MAGIC);
                assert_eq!(request_header[1], expected_opcode);
                let length = u32::from_be_bytes(request_header[10..14].try_into().unwrap()) as usize;
                let mut request_payload = vec![0; length];
                stream.read_exact(&mut request_payload).unwrap();

                let mut response = Vec::new();
                response.push(MAGIC);
                response.push(OP_RESPONSE_OK);
                response.extend_from_slice(&request_header[2..10]);
                response.extend_from_slice(&(response_payload.len() as u32).to_be_bytes());
                response.extend_from_slice(&response_payload);
                stream.write_all(&response).unwrap();
            }
        });

        let mut client = StorageClient::connect(&address.to_string()).unwrap();
        assert_eq!(client.append(b"value").unwrap(), 7);
        assert_eq!(
            client.scan_after(6, 1).unwrap(),
            vec![StoredRecord { key: 7, value: b"abc".to_vec() }]
        );
        server.join().unwrap();
    }
}