package Yggdrasil.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.logging.Logger;

/**
 * WalManager handles Write-Ahead Logging (WAL) for Yggdrasil.
 * Enforces transaction durability by persisting insertion, deletion, and page update 
 * operations before disk writes, enabling crash recovery log replay.
 */
public class WalManager {

    private static final Logger LOGGER = Logger.getLogger(WalManager.class.getName());

    private static final String DEFAULT_WAL_DIR = StorageManager.DATA;
    private static final String WAL_FILE_NAME = "engine.wal";
    
    // Operation Types
    public static final byte OP_INSERT_TUPLE = 0x01;
    public static final byte OP_DELETE_TUPLE = 0x02;
    public static final byte OP_PAGE_UPDATE  = 0x03;

    private final File walFile;
    private RandomAccessFile raf;
    private FileChannel channel;
    private long nextLsn = 1;

    public WalManager(String dataDir) {
        File dir = new File(dataDir != null ? dataDir : DEFAULT_WAL_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.walFile = new File(dir, WAL_FILE_NAME);
    }

    public WalManager() {
        this(DEFAULT_WAL_DIR);
    }

    public synchronized void open() throws IOException {
        this.raf = new RandomAccessFile(walFile, "rw");
        this.channel = raf.getChannel();
        this.nextLsn = findNextLsn();
        this.channel.position(channel.size());
    }

    /**
     * Scans the log file on startup to calculate the next sequence number (LSN)
     * and truncates trailing corrupt/incomplete log frames.
     */
    private long findNextLsn() throws IOException {
        long highestLsn = 0;
        long position = 0;
        long fileSize = channel.size();
        ByteBuffer header = ByteBuffer.allocate(9); // LSN (8B) + OpType (1B)

        while (position + header.capacity() <= fileSize) {
            header.clear();
            if (!readFullyAt(header, position)) {
                break;
            }
            
            header.flip();
            long lsn = header.getLong();
            byte operation = header.get();
            int bodySize;

            switch (operation) {
                case OP_INSERT_TUPLE -> bodySize = 16;
                case OP_DELETE_TUPLE -> bodySize = 12;
                case OP_PAGE_UPDATE   -> bodySize = 8;
                default               -> bodySize = -1;
            }

            if (bodySize == -1) {
                break; // Corrupt operation header
            }

            ByteBuffer bodyHeader = ByteBuffer.allocate(bodySize);
            if (!readFullyAt(bodyHeader, position + header.capacity())) {
                break;
            }
            
            bodyHeader.flip();
            if (operation == OP_INSERT_TUPLE) {
                bodyHeader.position(12);
                int payloadLength = bodyHeader.getInt();
                if (payloadLength < 0) break;
                bodySize += payloadLength;
            } else if (operation == OP_PAGE_UPDATE) {
                int payloadLength = bodyHeader.getInt(4);
                if (payloadLength < 0) break;
                bodySize += payloadLength;
            }

            long recordSize = header.capacity() + bodySize;
            if (position + recordSize > fileSize) {
                break;
            }

            highestLsn = Math.max(highestLsn, lsn);
            position += recordSize;
        }

        if (position < fileSize) {
            channel.truncate(position);
        }

        return highestLsn + 1;
    }

    /**
     * Reads fully into the buffer starting at a specific channel offset without modifying channel position.
     */
    private boolean readFullyAt(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int bytesRead = channel.read(buffer, position);
            if (bytesRead < 0) return false;
            if (bytesRead == 0) continue;
            position += bytesRead;
        }
        return true;
    }

    private boolean readFully(ByteBuffer buffer) throws IOException {
        return readFullyFrom(channel, buffer);
    }

    public static boolean readFullyFrom(ReadableByteChannel source, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int bytesRead = source.read(buffer);
            if (bytesRead < 0) return false;
            if (bytesRead == 0) continue;
        }
        return true;
    }

    /**
     * Flushes buffered WAL data down to persistent physical storage.
     */
    public synchronized void flush() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.force(false);
        }
    }

    /**
     * Logs an insert operation pairing a search key, packed TupleID, and record payload.
     * Frame: LSN (8B) | Op (1B) | Key (4B) | TupleID (8B) | RecordLen (4B) | RecordBytes (NB)
     */
    public synchronized long logTupleInsert(int key, long tupleID, byte[] recordPayload) throws IOException {
        long currentLsn = nextLsn++;
        int frameSize = 8 + 1 + 4 + 8 + 4 + recordPayload.length;
        
        ByteBuffer buffer = ByteBuffer.allocate(frameSize);
        buffer.putLong(currentLsn);
        buffer.put(OP_INSERT_TUPLE);
        buffer.putInt(key);
        buffer.putLong(tupleID);
        buffer.putInt(recordPayload.length);
        buffer.put(recordPayload);
        
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        
        return currentLsn;
    }

    /**
     * Logs a tuple deletion operation.
     * Frame: LSN (8B) | Op (1B) | Key (4B) | TupleID (8B)
     */
    public synchronized long logTupleDelete(int key, long tupleID) throws IOException {
        long currentLsn = nextLsn++;
        
        ByteBuffer buffer = ByteBuffer.allocate(21);
        buffer.putLong(currentLsn);
        buffer.put(OP_DELETE_TUPLE);
        buffer.putInt(key);
        buffer.putLong(tupleID);
        
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        
        return currentLsn;
    }

    /**
     * Logs a direct page update operation.
     * Frame: LSN (8B) | Op (1B) | PageID (4B) | PayloadLen (4B) | PageBytes (NB)
     */
    public synchronized long logPageUpdate(int pageID, byte[] pagePayload) throws IOException {
        long currentLsn = nextLsn++;
        int frameSize = 8 + 1 + 4 + 4 + pagePayload.length;

        ByteBuffer buffer = ByteBuffer.allocate(frameSize);
        buffer.putLong(currentLsn);
        buffer.put(OP_PAGE_UPDATE);
        buffer.putInt(pageID);
        buffer.putInt(pagePayload.length);
        buffer.put(pagePayload);

        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        
        return currentLsn;
    }

    /**
     * Replays write-ahead log records on startup to recover uncommitted or crashed page states.
     */
    public synchronized void recover(StorageManager storageManager, BPlusTree bTree, HeapFile heapFile) throws IOException {
        if (channel == null || channel.size() == 0) return;

        WalManager suspendedWal = storageManager.suspendWal();

        int insertsApplied = 0;
        int deletesApplied = 0;
        int pageUpdatesApplied = 0;
        int rootPageID = bTree.getRootID();
        if (rootPageID != -1 && !storageManager.pageExists(rootPageID)) {
            storageManager.setRootPageID(-1);
        }
        channel.position(0);
        ByteBuffer headerBuf = ByteBuffer.allocate(9); // LSN (8B) + OpType (1B)

        while (channel.position() < channel.size()) {
            long recordStart = channel.position();
            headerBuf.clear();

            if (!readFully(headerBuf)) {
                channel.truncate(recordStart);
                break;
            }
            
            headerBuf.flip();
            headerBuf.getLong(); // Consume the LSN before reading the operation type.
            byte opType = headerBuf.get();

            switch (opType) {
                case OP_INSERT_TUPLE -> {
                    // Frame Body: Key (4B) | TupleID (8B) | RecordLen (4B) = 16 Bytes Total
                    ByteBuffer bodyBuf = ByteBuffer.allocate(16);
                    if (!readFully(bodyBuf)) {
                        channel.truncate(recordStart);
                        break;
                    }
                    bodyBuf.flip();

                    int key = bodyBuf.getInt();
                    long loggedTupleId = bodyBuf.getLong();
                    int payloadLen = bodyBuf.getInt();

                    if (payloadLen < 0 || payloadLen > channel.size() - channel.position()) {
                        channel.truncate(recordStart);
                        break;
                    }

                    byte[] payload = new byte[payloadLen];
                    ByteBuffer payloadBuf = ByteBuffer.wrap(payload);
                    if (!readFully(payloadBuf)) {
                        channel.truncate(recordStart);
                        break;
                    }

                    if (!heapFile.hasRecord(loggedTupleId, payload)) {
                        heapFile.insertRecordAt(loggedTupleId, payload);
                        insertsApplied++;
                    }

                    bTree.insert(key, loggedTupleId);
                }

                case OP_DELETE_TUPLE -> {
                    // Frame Body: Key (4B) | TupleID (8B) = 12 Bytes Total
                    ByteBuffer bodyBuf = ByteBuffer.allocate(12);
                    if (!readFully(bodyBuf)) {
                        channel.truncate(recordStart);
                        break;
                    }
                    bodyBuf.flip();

                    int key = bodyBuf.getInt();
                    long loggedTupleId = bodyBuf.getLong();
                    
                    if (heapFile.hasRecord(loggedTupleId, null)) {
                        heapFile.deleteRecord(loggedTupleId);
                        deletesApplied++;
                    }
                    
                    if (bTree.getRootID() != -1 && storageManager.getTotalPages() > 0) {
                        bTree.delete(key);
                    }
                }

                case OP_PAGE_UPDATE -> {
                    ByteBuffer bodyBuf = ByteBuffer.allocate(8);
                    if (!readFully(bodyBuf)) {
                        channel.truncate(recordStart);
                        break;
                    }
                    bodyBuf.flip();

                    int pageID = bodyBuf.getInt();
                    int payloadLen = bodyBuf.getInt();

                    if (payloadLen < 0 || payloadLen > channel.size() - channel.position()) {
                        channel.truncate(recordStart);
                        break;
                    }

                    ByteBuffer payloadBuf = ByteBuffer.allocate(payloadLen);
                    if (!readFully(payloadBuf)) {
                        channel.truncate(recordStart);
                        break;
                    }

                    storageManager.applyPageUpdate(pageID, payloadBuf.array());
                    pageUpdatesApplied++;
                }

                default -> {
                    channel.truncate(recordStart);
                    break;
                }
            }
        }
        
        try {
            storageManager.flushAllDirtyPagesNoWal();
            truncateLog();
                LOGGER.info("WAL recovery complete: " + (insertsApplied + deletesApplied + pageUpdatesApplied)
                    + " REDO operations applied (inserts=" + insertsApplied
                    + ", deletes=" + deletesApplied + ", pageUpdates=" + pageUpdatesApplied + ")");
        } finally {
            storageManager.restoreWal(suspendedWal);
        }
    }

    public synchronized void truncateLog() throws IOException {
        if (channel != null) {
            channel.truncate(0);
            channel.force(false);
            nextLsn = 1;
        }
    }

    public synchronized void close() throws IOException {
        if (channel != null) channel.close();
        if (raf != null) raf.close();
    }

    public File getWalFile() {
        return walFile;
    }
}