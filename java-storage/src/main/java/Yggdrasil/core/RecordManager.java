package Yggdrasil.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RecordManager {

    public record StoredRecord(int key, byte[] value) {}

    private final HeapFile heapFile;
    private final BPlusTree indexTree;
    private final StorageManager storageManager;
    private final WalManager walManager;

    public RecordManager(StorageManager storageManager, BPlusTree indexTree, WalManager walManager) {
        this.storageManager = storageManager;
        this.indexTree = indexTree;
        this.walManager = walManager;
        this.heapFile = new HeapFile(storageManager);
        storageManager.setWalManager(walManager);
    }

    public synchronized void insert(int searchKey, byte[] recordPayload) throws IOException {
        // Duplicate keys are idempotent; avoid allocating an unreachable heap record.
        if (indexTree.search(searchKey) != null) return;

        // 1. Insert into heap file to allocate space & get packed TID
        long packedTID = heapFile.insertRecord(recordPayload);

        // 2. Append insert record to WAL log
        long opLsn = walManager.logTupleInsert(searchKey, packedTID, recordPayload);

        // 3. WAL Invariant: Flush log to disk BEFORE writing page frame changes
        walManager.flush();

        // 4. Update data page LSN cleanly
        int dataPageNum = TupleID.getPageNumber(packedTID);
        Page dataPage = storageManager.getPage(-1, dataPageNum);
        try {
            dataPage.setLsn(opLsn);
            dataPage.setDirty(true);
        } finally {
            storageManager.unpinPage(dataPageNum);
        }

        // 5. Direct insert key-TID into B+ Tree index
        indexTree.insert(searchKey, packedTID);
    }

    public synchronized int append(byte[] recordPayload) throws IOException {
        Integer currentMax = indexTree.maxKey();
        if (currentMax != null && currentMax == Integer.MAX_VALUE) {
            throw new IOException("Storage key space is exhausted.");
        }

        int key = currentMax == null ? Integer.MIN_VALUE : currentMax + 1;
        insert(key, recordPayload);
        return key;
    }

    public synchronized List<StoredRecord> scanAfter(long afterKey, int limit) throws IOException {
        List<StoredRecord> records = new ArrayList<>();
        for (BPlusTree.Entry entry : indexTree.scanAfter(afterKey, limit)) {
            byte[] value = heapFile.selectRecord(entry.value());
            if (value != null) {
                records.add(new StoredRecord(entry.key(), value));
            }
        }
        return records;
    }

    public synchronized void delete(int searchKey) throws IOException {
        Long packedTID = indexTree.search(searchKey);
        if (packedTID == null) return;

        // 1. Log delete & flush WAL
        long opLsn = walManager.logTupleDelete(searchKey, packedTID);
        walManager.flush();

        // 2. Modify index & remove record payload from heap
        indexTree.delete(searchKey);
        heapFile.deleteRecord(packedTID);

        // 3. Stamp page LSN and unpin
        int dataPageNum = TupleID.getPageNumber(packedTID);
        Page dataPage = storageManager.getPage(-1, dataPageNum);
        try {
            dataPage.setLsn(opLsn);
            dataPage.setDirty(true);
        } finally {
            storageManager.unpinPage(dataPageNum);
        }
    }

    public synchronized void redoRecovery() throws IOException {
        walManager.recover(storageManager, indexTree, heapFile);
    }

    public synchronized byte[] find(int searchKey) throws IOException {
        Long packedTID = indexTree.search(searchKey);
        if (packedTID == null) return null;
        return heapFile.selectRecord(packedTID);
    }

    public synchronized boolean containsKey(int searchKey) throws IOException {
        return indexTree.search(searchKey) != null;
    }

    public synchronized List<byte[]> scan(int targetKey, BPlusTree.Operator operator) throws IOException {
        List<Long> matchedTIDs = indexTree.query(targetKey, operator);
        List<byte[]> recordResults = new ArrayList<>();
        for (long tid : matchedTIDs) {
            byte[] record = heapFile.selectRecord(tid);
            if (record != null) recordResults.add(record);
        }
        return recordResults;
    }

    public synchronized void auditIntegrity() throws IOException {
        storageManager.auditIntegrity();
    }
}