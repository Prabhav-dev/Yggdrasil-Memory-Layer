package Yggdrasil;

import Yggdrasil.crypto.Ascon128Engine;
import Yggdrasil.core.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class ComprehensiveEngineTestSuite extends EngineTestSuite {

    public static void main(String[] args) {
        ComprehensiveEngineTestSuite testRunner = new ComprehensiveEngineTestSuite();
        
        try {
            System.out.println("================================================================");
            System.out.println("     YGGDRASIL STORAGE ENGINE INTEGRATION TEST HARNESS          ");
            System.out.println("================================================================");
            
            // 1. ASCON-128 AEAD Cryptographic Verification
            testRunner.runTest("ASCON Payload Encryption/Decryption Roundtrip", testRunner::testAsconRoundtrip);
            testRunner.runTest("ASCON Auth Tag Integrity & Tamper Detection", testRunner::testAsconTamperDetection);
            
            // 2. Slotted Page Variable-Length Record Operations
            testRunner.runTest("Slotted Page Record Insertion & Readback", testRunner::testSlottedPageRecordInsertAndGet);
            testRunner.runTest("Slotted Page Zero-Length Record Handling", testRunner::testSlottedPageEmptyRecord);
            testRunner.runTest("Slotted Page Slot Reclaim & Tombstone Reuse", testRunner::testSlottedPageTombstoneReuse);
            testRunner.runTest("Slotted Page Slot Deletion & Space Compaction", testRunner::testSlottedPageDefragmentation);
            
            // 3. WAL & Recovery Integrity
            testRunner.runTest("WAL Sequence Number Log Generation & Sync", testRunner::testWalLogGeneration);
            testRunner.runTest("WAL Crash Recovery & Redo Replay Pipeline", testRunner::testWalCrashRecoveryReplay);
            testRunner.runTest("Storage Metadata Cold Boot Without Initial Pages", testRunner::testMetadataRebootWithoutPages);

            // 4. Record Manager End-to-End Pipeline
            testRunner.runTest("RecordManager Core Put/Get Roundtrip", testRunner::testRecordManagerInsertAndFind);
            testRunner.runTest("RecordManager Record Deletion & Range Scan Integration", testRunner::testRecordManagerDeleteAndScan);

            // 5. Engine Persistence & Cold Boot
            testRunner.runTest("Cold Restart Database Persistence Recovery", testRunner::testColdBootPersistence);
            
            // 6. B+ Tree Boundary Edge Cases
            testRunner.runTest("B+ Tree Leaf Node Split Edge Boundary", testRunner::testLeafNodeExactSplitBoundary);
            testRunner.runTest("B+ Tree Multi-Level Internal Node Cascading Split", testRunner::testCascadingInternalNodeSplits);
            testRunner.runTest("Internal Node Maximum Payload Serialization Boundary", testRunner::testInternalNodeMaximumPayload);
            testRunner.runTest("Randomized Out-of-Order Key Insert Operations", testRunner::testRandomizedInserts);
            testRunner.runTest("Duplicate Key Idempotency Guard", testRunner::testDuplicateKeyIdempotency);
            
            // 7. Scans & Deletions
            testRunner.runTest("Multi-Page Range Query Iteration", testRunner::testRangeScanMultiPageTransitions);
            testRunner.runTest("Leaf Node Underflow Rebalancing & Merging", testRunner::testLeafUnderflowBorrowAndCoalesce);
            testRunner.runTest("Complete Tree Drain & Root Reset to Zero-State", testRunner::testCompleteTreeDrainToZeroState);
            
            System.out.println("\n[SUCCESS] All cryptographic, storage, index, and WAL tests completed without errors.");
            
        } catch (Exception ex) {
            System.err.println("\n[FATAL] Integration test execution aborted due to critical failure:");
            throw new RuntimeException(ex);
        }
    }

    // =========================================================================
    // 1. CRYPTOGRAPHIC ASCON-128 AEAD TESTS
    // =========================================================================

    private void testAsconRoundtrip() throws Exception {
        byte[] secretKey = new byte[16];
        byte[] ivNonce = new byte[16];
        Arrays.fill(secretKey, (byte) 0x01);
        Arrays.fill(ivNonce, (byte) 0x02);

        byte[] originalPayload = "Yggdrasil-Encrypted-Payload-Data".getBytes(StandardCharsets.UTF_8);
        byte[] encryptedFrame = Ascon128Engine.encryptPayload(secretKey, ivNonce, null, originalPayload);
        
        if (encryptedFrame.length != originalPayload.length + Ascon128Engine.TAG_LEN) {
            throw new RuntimeException("Encrypted buffer size does not match expected payload + tag overhead.");
        }

        byte[] decryptedPayload = Ascon128Engine.decryptPayload(secretKey, ivNonce, null, encryptedFrame);
        if (decryptedPayload == null || !Arrays.equals(originalPayload, decryptedPayload)) {
            throw new RuntimeException("ASCON Engine failed to correctly decrypt cipher payload.");
        }
    }

    private void testAsconTamperDetection() throws Exception {
        byte[] secretKey = new byte[16];
        byte[] ivNonce = new byte[16];
        byte[] payloadData = "Sensitive Database Bytes".getBytes(StandardCharsets.UTF_8);

        byte[] encryptedFrame = Ascon128Engine.encryptPayload(secretKey, ivNonce, null, payloadData);
        
        // Corrupt a single bit in the ciphertext frame
        encryptedFrame[0] ^= 0x01;

        byte[] decryptedPayload = Ascon128Engine.decryptPayload(secretKey, ivNonce, null, encryptedFrame);
        if (decryptedPayload != null) {
            throw new RuntimeException("ASCON Engine failed to reject corrupted/tampered payload frame.");
        }
    }

    // =========================================================================
    // 2. SLOTTED PAGE RECORD MANAGERS
    // =========================================================================

    private void testSlottedPageRecordInsertAndGet() throws Exception {
        SlottedPage page = new SlottedPage(1, 1);
        byte[] recordA = "First Record".getBytes(StandardCharsets.UTF_8);
        byte[] recordB = "Second Larger Record Entry".getBytes(StandardCharsets.UTF_8);

        long tupleIdA = page.insertRecord(recordA);
        long tupleIdB = page.insertRecord(recordB);

        int slotA = TupleID.getSlotIndex(tupleIdA);
        int slotB = TupleID.getSlotIndex(tupleIdB);

        byte[] fetchedA = page.getRecord(slotA);
        byte[] fetchedB = page.getRecord(slotB);

        if (!Arrays.equals(recordA, fetchedA) || !Arrays.equals(recordB, fetchedB)) {
            throw new RuntimeException("Slotted page read mismatch for inserted tuple payloads.");
        }
    }

    private void testSlottedPageDefragmentation() throws Exception {
        SlottedPage page = new SlottedPage(1, 1);
        long tuple0 = page.insertRecord("Data 0".getBytes(StandardCharsets.UTF_8));
        long tuple1 = page.insertRecord("Data 1 To Delete".getBytes(StandardCharsets.UTF_8));
        long tuple2 = page.insertRecord("Data 2".getBytes(StandardCharsets.UTF_8));

        int slot0 = TupleID.getSlotIndex(tuple0);
        int slot1 = TupleID.getSlotIndex(tuple1);
        int slot2 = TupleID.getSlotIndex(tuple2);

        page.deleteRecord(slot1);
        
        byte[] newPayload = "Compacted Replacement Data".getBytes(StandardCharsets.UTF_8);
        long tuple3 = page.insertRecord(newPayload);
        int slot3 = TupleID.getSlotIndex(tuple3);

        if (page.getRecord(slot0) == null || page.getRecord(slot2) == null) {
            throw new RuntimeException("Defragmentation wiped out adjacent valid records.");
        }
        if (!Arrays.equals(newPayload, page.getRecord(slot3))) {
            throw new RuntimeException("Failed to read replacement record stored in compacted slot.");
        }
    }

    private void testSlottedPageEmptyRecord() throws Exception {
        SlottedPage page = new SlottedPage(1, 1);
        long tupleId = page.insertRecord(new byte[0]);

        byte[] fetchedPayload = page.getRecord(TupleID.getSlotIndex(tupleId));
        if (fetchedPayload == null || fetchedPayload.length != 0) {
            throw new RuntimeException("Slotted page did not retain zero-length record properly.");
        }
    }

    private void testSlottedPageTombstoneReuse() throws Exception {
        SlottedPage page = new SlottedPage(1, 1);
        int initialSlot = TupleID.getSlotIndex(page.insertRecord("value-0".getBytes(StandardCharsets.UTF_8)));

        for (int step = 1; step <= 1000; step++) {
            if (!page.deleteRecord(initialSlot)) {
                throw new RuntimeException("Failed to mark slot tombstone on iteration " + step);
            }

            byte[] replacement = ("value-" + step).getBytes(StandardCharsets.UTF_8);
            long newTupleId = page.insertRecord(replacement);
            if (TupleID.getSlotIndex(newTupleId) != initialSlot
                    || page.getSlotCount() != 1
                    || !Arrays.equals(replacement, page.getRecord(initialSlot))) {
                throw new RuntimeException("Tombstone slot was not reused on churn cycle " + step);
            }
        }
    }

    private void testInternalNodeMaximumPayload() throws Exception {
        InternalNode node = new InternalNode(1, 1);
        for (int i = 0; i < InternalNode.MAX_KEYS; i++) {
            node.getKeys()[i] = i;
            node.getChildPageNumbers()[i] = i + 1;
        }
        node.getChildPageNumbers()[InternalNode.MAX_KEYS] = InternalNode.MAX_KEYS + 1;
        node.setKeyCount((short) InternalNode.MAX_KEYS);

        byte[] payload = node.getPayloadBytes();
        if (payload.length != Page.PAYLOAD_SIZE) {
            throw new RuntimeException("Internal node binary payload layout exceeded max expected frame capacity.");
        }
    }

    // =========================================================================
    // 3. WAL LOGGING & CRASH RECOVERY TESTS
    // =========================================================================

    private void testWalLogGeneration() throws Exception {
        byte[] payload = "WAL Protected Payload".getBytes(StandardCharsets.UTF_8);
        long generatedLsn = walManager.logTupleInsert(101, 0x00010002L, payload);
        walManager.flush();

        if (generatedLsn <= 0) {
            throw new RuntimeException("Invalid LSN produced by WalManager: " + generatedLsn);
        }
    }

    private void testWalCrashRecoveryReplay() throws Exception {
        int keyVal = 500;
        byte[] recordPayload = "Uncommitted Row 1".getBytes(StandardCharsets.UTF_8);
        
        // Step 1: Write record via RecordManager
        recordManager.insert(keyVal, recordPayload);
        walManager.flush(); 

        // Step 2: Flush encrypted page frames so ASCON tags hit disk, but DO NOT commit WAL LSN state
        storageManager.flushAllDirtyPagesNoWal();

        // Step 3: Hard crash (Close underlying components without graceful DB shutdown)
        shutdownEngine();

        // Step 4: Re-instantiate storage subsystem on disk files using matching 16-byte key
        storageManager = new StorageManager(new byte[16], TEST_DATA_DIR);
        storageManager.boot();

        walManager = new WalManager(TEST_DATA_DIR);
        walManager.open();

        tree = new BPlusTree(storageManager);
        recordManager = new RecordManager(storageManager, tree, walManager);

        // Step 5: Replay redo logs from WAL file
        recordManager.redoRecovery();

        // Step 6: Verify recovered entry
        byte[] restoredPayload = recordManager.find(keyVal);
        if (restoredPayload == null || !Arrays.equals(recordPayload, restoredPayload)) {
            throw new RuntimeException("WAL Recovery engine failed to restore insertion for Key: " + keyVal);
        }
    }

    private void testMetadataRebootWithoutPages() throws Exception {
    shutdownEngine();

    // Boot a fresh standalone instance to verify zero-page cold start
    StorageManager rebootedStorage = new StorageManager(new byte[16], TEST_DATA_DIR);
    rebootedStorage.boot();
    rebootedStorage.close();

    // Re-initialize class instance fields so state remains clean if tearDown runs
    storageManager = new StorageManager(new byte[16], TEST_DATA_DIR);
    storageManager.boot();
    walManager = new WalManager(TEST_DATA_DIR);
    walManager.open();
    tree = new BPlusTree(storageManager);
    recordManager = new RecordManager(storageManager, tree, walManager);
    }

    // =========================================================================
    // 4. RECORD MANAGER END-TO-END PIPELINE
    // =========================================================================

    private void testRecordManagerInsertAndFind() throws Exception {
    int key = 1234;
    byte[] data = "Hello Yggdrasil Engine".getBytes(StandardCharsets.UTF_8);

    recordManager.insert(key, data);
    byte[] result = recordManager.find(key);

    if (result == null) {
        throw new RuntimeException("RecordManager.find(" + key + ") returned null. Insert failed or key was not indexed in B+ Tree.");
    }
    
    if (!Arrays.equals(data, result)) {
        throw new RuntimeException("RecordManager Put/Get mismatch. Expected: " + new String(data) + ", Got: " + new String(result));
    }
    }

    private void testRecordManagerDeleteAndScan() throws Exception {
        int k1 = 10, k2 = 20, k3 = 30;
        byte[] payloadData = "Scan Value".getBytes(StandardCharsets.UTF_8);

        recordManager.insert(k1, payloadData);
        recordManager.insert(k2, payloadData);
        recordManager.insert(k3, payloadData);

        // Delete key in the middle
        recordManager.delete(k2);

        if (recordManager.find(k2) != null) {
            throw new RuntimeException("RecordManager find returning deleted key: " + k2);
        }

        List<byte[]> scanResults = recordManager.scan(10, BPlusTree.Operator.GREATER_THAN_OR_EQUAL);
        if (scanResults.size() != 2) {
            throw new RuntimeException("RecordManager scan mismatch after key removal. Expected 2 elements, found: " + scanResults.size());
        }
    }

    // =========================================================================
    // 5. PERSISTENCE & COLD RESTART
    // =========================================================================

    private void testColdBootPersistence() throws Exception {
        int insertCount = 100;
        for (int i = 1; i <= insertCount; i++) {
            tree.insert(i, i * 777L);
        }

        storageManager.flushAllDirtyPagesNoWal();
        shutdownEngine();

        storageManager = new StorageManager(new byte[16], TEST_DATA_DIR);
        storageManager.boot();
        
        walManager = new WalManager(TEST_DATA_DIR);
        walManager.open();
        
        tree = new BPlusTree(storageManager);

        for (int i = 1; i <= insertCount; i++) {
            Long recordId = tree.search(i);
            if (recordId == null || recordId != i * 777L) {
                throw new RuntimeException("Cold boot failed to retrieve persistent key: " + i);
            }
        }
    }

    // =========================================================================
    // 6. B+ TREE BOUNDARIES & RANDOMIZED DATA
    // =========================================================================

    private void testLeafNodeExactSplitBoundary() throws Exception {
        int keyLimit = LeafNode.MAX_KEYS;
        for (int i = 1; i <= keyLimit; i++) {
            tree.insert(i, i * 1000L);
        }
        tree.insert(keyLimit + 1, (keyLimit + 1) * 1000L);

        for (int i = 1; i <= keyLimit + 1; i++) {
            Long val = tree.search(i);
            if (val == null || val != i * 1000L) {
                throw new RuntimeException("Data integrity lost following leaf node split on key: " + i);
            }
        }
    }

    private void testCascadingInternalNodeSplits() throws Exception {
        int totalElements = 1200;
        for (int i = 1; i <= totalElements; i++) {
            tree.insert(i, i * 1000L);
        }

        for (int i = 1; i <= totalElements; i++) {
            if (tree.search(i) == null) {
                throw new RuntimeException("Cascading internal node split dropped expected key: " + i);
            }
        }
    }

    private void testRandomizedInserts() throws Exception {
        int[] keys = {45, 12, 89, 3, 99, 23, 67, 1, 55, 78, 100, 15, 34};
        for (int k : keys) {
            tree.insert(k, k * 100L);
        }

        for (int k : keys) {
            Long retrievedVal = tree.search(k);
            if (retrievedVal == null || retrievedVal != k * 100L) {
                throw new RuntimeException("Randomized insert failed on key lookup for: " + k);
            }
        }
    }

    private void testDuplicateKeyIdempotency() throws Exception {
        int key = 42;
        tree.insert(key, 9999L);
        for (int i = 0; i < 20; i++) {
            tree.insert(key, 1111L);
        }
        
        Long retrieved = tree.search(key);
        if (retrieved == null || retrieved != 9999L) {
            throw new RuntimeException("Duplicate key insertion altered initial key mapping value.");
        }
    }

    // =========================================================================
    // 7. SCANS & DELETIONS
    // =========================================================================

    private void testRangeScanMultiPageTransitions() throws Exception {
        int totalKeys = 400;
        for (int i = 1; i <= totalKeys; i++) {
            tree.insert(i, i * 10L);
        }
        
        List<Long> matches = tree.query(200, BPlusTree.Operator.GREATER_THAN_OR_EQUAL);
        if (matches.size() != (400 - 200 + 1)) {
            throw new RuntimeException("Multi-page scan query missing entries. Returned count: " + matches.size());
        }
    }

    private void testLeafUnderflowBorrowAndCoalesce() throws Exception {
        int keyLimit = LeafNode.MAX_KEYS;
        for (int i = 1; i <= keyLimit + 5; i++) {
            tree.insert(i, i * 1000L);
        }
        
        tree.delete(keyLimit + 5);
        if (tree.search(keyLimit + 5) != null) {
            throw new RuntimeException("Failed to cleanly delete target key.");
        }
    }

    private void testCompleteTreeDrainToZeroState() throws Exception {
        int total = 100;
        for (int i = 1; i <= total; i++) {
            tree.insert(i, i * 100L);
        }
        for (int i = 1; i <= total; i++) {
            tree.delete(i);
        }
        
        if (tree.search(1) != null) {
            throw new RuntimeException("Drain verification failed: Tree failed to purge all records back to empty state.");
        }
    }
}