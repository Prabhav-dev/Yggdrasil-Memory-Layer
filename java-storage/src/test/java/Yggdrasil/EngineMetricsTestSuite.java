package Yggdrasil;

import Yggdrasil.core.*;
import Yggdrasil.gui.EngineTelemetry;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Extended Storage Engine & Telemetry Diagnostics Suite.
 *
 * Checks engine telemetry counters, cache invalidation, multi-page range scan 
 * coverage, payload limits, concurrency guarantees, and query operators.
 */
public class EngineMetricsTestSuite extends EngineTestSuite {

    public static void main(String[] args) {
        EngineMetricsTestSuite suiteRunner = new EngineMetricsTestSuite();

        try {
            System.out.println("================================================================");
            System.out.println("   YGGDRASIL ENGINE TELEMETRY & EXTENDED STORAGE TEST HARNESS   ");
            System.out.println("================================================================");

            suiteRunner.runTest("EngineTelemetry Disk Flush Counters",             suiteRunner::testTelemetryFlushCounterAccuracy);
            suiteRunner.runTest("EngineTelemetry Cache Hit/Miss Ratio Logic",      suiteRunner::testCacheHitMissRatioSanity);
            suiteRunner.runTest("Storage Cache Page Eviction & Reload Cycle",       suiteRunner::testEvictAndReloadPage);
            suiteRunner.runTest("Multi-Page Sequential Range Query Completeness",  suiteRunner::testLargeSequentialRangeScan);
            suiteRunner.runTest("RecordManager Duplicate Key Idempotency",          suiteRunner::testRecordManagerDuplicateKeyRejection);
            suiteRunner.runTest("Storage Metadata Cold Restart Retention",         suiteRunner::testMetadataPersistenceWithManyPages);
            suiteRunner.runTest("B+Tree LESS_THAN_OR_EQUAL Scan Boundary",         suiteRunner::testBTreeLessThanOrEqualBoundary);
            suiteRunner.runTest("Near-Capacity Binary Payload Round-Trip",         suiteRunner::testLargeBinaryPayloadRoundTrip);
            suiteRunner.runTest("EngineTelemetry Atomic Concurrency Safety",        suiteRunner::testTelemetryConcurrentSafety);
            suiteRunner.runTest("B+Tree LESS_THAN Strict Scan Operator",           suiteRunner::testPartialRangeScanLessThan);

            System.out.println("\n[SUCCESS] All telemetry, persistence, and range query tests completed cleanly.");

        } catch (Exception ex) {
            System.err.println("\n[FATAL] Telemetry and storage test suite crashed unexpectedly:");
            throw new RuntimeException(ex);
        }
    }

    // =========================================================================
    // (a) EngineTelemetry counter accuracy during insert/search
    // =========================================================================

    private void testTelemetryFlushCounterAccuracy() throws Exception {
        EngineTelemetry.resetStats();

        long initialFlushes = EngineTelemetry.getPageFlushCount();
        long initialBytes = EngineTelemetry.getBytesWritten();

        int targetInserts = 5;
        for (int i = 1; i <= targetInserts; i++) {
            tree.insert(i, i * 100L);
        }

        long updatedFlushes = EngineTelemetry.getPageFlushCount();
        long updatedBytes = EngineTelemetry.getBytesWritten();

        if (updatedFlushes <= initialFlushes) {
            throw new RuntimeException(
                "Telemetry page flush counter failed to increment on record insert. " +
                "Initial=" + initialFlushes + ", Updated=" + updatedFlushes);
        }
        if (updatedBytes <= initialBytes) {
            throw new RuntimeException(
                "Telemetry write counter failed to track written bytes. " +
                "Initial=" + initialBytes + ", Updated=" + updatedBytes);
        }
    }

    // =========================================================================
    // (b) Cache hit/miss ratio sanity
    // =========================================================================

    private void testCacheHitMissRatioSanity() throws Exception {
        EngineTelemetry.resetStats();

        for (int i = 1; i <= 10; i++) {
            tree.insert(i, i * 50L);
        }

        // Clear counters to isolate key read passes
        EngineTelemetry.resetStats();

        for (int iteration = 0; iteration < 3; iteration++) {
            for (int i = 1; i <= 10; i++) {
                tree.search(i);
            }
        }

        long totalHits = EngineTelemetry.getCacheHits();
        long totalMisses = EngineTelemetry.getCacheMisses();

        if (totalHits + totalMisses == 0) {
            throw new RuntimeException("Telemetry recorded zero cache events across 30 read operations.");
        }
        if (totalHits <= totalMisses) {
            throw new RuntimeException(
                "Cache hit count unexpectedly low relative to misses. Hits=" + 
                totalHits + ", Misses=" + totalMisses);
        }
    }

    // =========================================================================
    // (c) evictPageFromCache + Reload Correctness
    // =========================================================================

    private void testEvictAndReloadPage() throws Exception {
        int key = 777;
        long recordVal = 12345L;
        tree.insert(key, recordVal);

        Long initialLookup = tree.search(key);
        if (initialLookup == null || initialLookup != recordVal) {
            throw new RuntimeException("Pre-check failed: key missing before cache eviction test.");
        }

        // Force cache eviction on every allocated data page
        List<Integer> activePages = storageManager.getMetadata().getDataPageNumbers();
        for (int pageId : activePages) {
            storageManager.evictPageFromCache(pageId);
        }

        // Search forces page reload from disk
        Long reloadedVal = tree.search(key);
        if (reloadedVal == null || reloadedVal != recordVal) {
            throw new RuntimeException(
                "Cache reload returned invalid or missing record after page eviction. " +
                "Expected=" + recordVal + ", Received=" + reloadedVal);
        }
    }

    // =========================================================================
    // (d) Large sequential range scan completeness
    // =========================================================================

    private void testLargeSequentialRangeScan() throws Exception {
        int recordCount = 700;
        for (int i = 1; i <= recordCount; i++) {
            tree.insert(i, (long) i);
        }

        List<Long> rangeResults = tree.query(1, BPlusTree.Operator.GREATER_THAN_OR_EQUAL);

        if (rangeResults.size() != recordCount) {
            throw new RuntimeException(
                "Multi-page range query missed elements. Expected=" + recordCount + 
                ", Received=" + rangeResults.size());
        }

        for (int idx = 0; idx < rangeResults.size(); idx++) {
            long expectedValue = idx + 1L;
            if (rangeResults.get(idx) != expectedValue) {
                throw new RuntimeException(
                    "Sequence violation in range scan at index " + idx +
                    ". Expected=" + expectedValue + ", Received=" + rangeResults.get(idx));
            }
        }
    }

    // =========================================================================
    // (e) RecordManager duplicate-key rejection (idempotency)
    // =========================================================================

    private void testRecordManagerDuplicateKeyRejection() throws Exception {
        int key = 99;
        byte[] primaryData = "Original Payload".getBytes(StandardCharsets.UTF_8);
        byte[] duplicateData = "Duplicate Payload".getBytes(StandardCharsets.UTF_8);

        recordManager.insert(key, primaryData);
        int dataPagesBeforeDuplicate = storageManager.getMetadata().getDataPageNumbers().size();

        // Submitting duplicate key should be handled safely/idempotently
        recordManager.insert(key, duplicateData);

        byte[] fetchedData = recordManager.find(key);
        if (fetchedData == null) {
            throw new RuntimeException("Record lookup returned null following duplicate insert pass.");
        }
        if (!Arrays.equals(primaryData, fetchedData)) {
            throw new RuntimeException(
                "Duplicate key insertion altered pre-existing record payload.");
        }
        int dataPagesAfterDuplicate = storageManager.getMetadata().getDataPageNumbers().size();
        if (dataPagesAfterDuplicate != dataPagesBeforeDuplicate) {
            throw new RuntimeException(
                "Duplicate key insertion allocated an unreachable heap page.");
        }
    }

    // =========================================================================
    // (f) Metadata persistence with many allocated data pages
    // =========================================================================

    private void testMetadataPersistenceWithManyPages() throws Exception {
        int insertCount = 500;
        for (int i = 1; i <= insertCount; i++) {
            tree.insert(i, i * 3L);
        }

        int preRestartPages = storageManager.getMetadata().getDataPageNumbers().size();

        // Perform cold reboot
        walManager.close();
        storageManager.close();
        storageManager = new StorageManager(new byte[16], TEST_DATA_DIR);
        storageManager.boot();
        walManager = new WalManager(TEST_DATA_DIR);
        walManager.open();
        tree = new BPlusTree(storageManager);

        int postRestartPages = storageManager.getMetadata().getDataPageNumbers().size();

        if (postRestartPages < preRestartPages) {
            throw new RuntimeException(
                "Page allocations were lost from metadata across cold boot. " +
                "Pre=" + preRestartPages + ", Post=" + postRestartPages);
        }

        for (int i = 1; i <= insertCount; i++) {
            Long val = tree.search(i);
            if (val == null || val != i * 3L) {
                throw new RuntimeException(
                    "Key lookup failed after metadata reload. Key=" + i);
            }
        }
    }

    // =========================================================================
    // (g) B+Tree LESS_THAN_OR_EQUAL ascending boundary test
    // =========================================================================

    private void testBTreeLessThanOrEqualBoundary() throws Exception {
        for (int i = 1; i <= 50; i++) {
            tree.insert(i, (long) i);
        }

        List<Long> scanMatches = tree.query(25, BPlusTree.Operator.LESS_THAN_OR_EQUAL);

        if (scanMatches.size() != 25) {
            throw new RuntimeException(
                "LESS_THAN_OR_EQUAL scan boundary length mismatch. Expected=25, Received=" + scanMatches.size());
        }

        for (long entry : scanMatches) {
            if (entry > 25) {
                throw new RuntimeException(
                    "LESS_THAN_OR_EQUAL scan included key above upper boundary: " + entry);
            }
        }
    }

    // =========================================================================
    // (h) Large binary payload round-trip (near PAYLOAD_SIZE limit)
    // =========================================================================

    private void testLargeBinaryPayloadRoundTrip() throws Exception {
        int payloadSize = 3000;
        byte[] boundaryPayload = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            boundaryPayload[i] = (byte) (i % 127);
        }

        int key = 55555;
        recordManager.insert(key, boundaryPayload);

        byte[] retrievedPayload = recordManager.find(key);
        if (retrievedPayload == null) {
            throw new RuntimeException("Failed to find large record payload after insertion.");
        }
        if (!Arrays.equals(boundaryPayload, retrievedPayload)) {
            throw new RuntimeException(
                "Payload data corruption detected on large binary record read. " +
                "Expected size=" + payloadSize + ", Received size=" + retrievedPayload.length);
        }
    }

    // =========================================================================
    // (i) Thread-safe EngineTelemetry atomic counters under concurrency
    // =========================================================================

    private void testTelemetryConcurrentSafety() throws Exception {
        EngineTelemetry.resetStats();

        int workerThreads = 8;
        int operationsPerWorker = 50;
        Thread[] pool = new Thread[workerThreads];
        int[] errorTracker = {0};

        for (int t = 0; t < workerThreads; t++) {
            pool[t] = new Thread(() -> {
                try {
                    for (int op = 0; op < operationsPerWorker; op++) {
                        EngineTelemetry.recordDiskWrite(4096);
                        EngineTelemetry.recordDiskRead(4096);
                        EngineTelemetry.recordCacheHit();
                        EngineTelemetry.recordCacheMiss();
                    }
                } catch (Exception ex) {
                    synchronized (errorTracker) { errorTracker[0]++; }
                }
            });
        }

        for (Thread worker : pool) worker.start();
        for (Thread worker : pool) worker.join(5000);

        if (errorTracker[0] > 0) {
            throw new RuntimeException(
                "Concurrent telemetry test raised exceptions: " + errorTracker[0]);
        }

        long targetWriteBytes = (long) workerThreads * operationsPerWorker * 4096L;
        long actualWriteBytes = EngineTelemetry.getBytesWritten();
        if (actualWriteBytes != targetWriteBytes) {
            throw new RuntimeException(
                "Telemetry bytes-written counter mismatch under multi-threaded load. " +
                "Expected=" + targetWriteBytes + ", Received=" + actualWriteBytes);
        }

        long targetHits = (long) workerThreads * operationsPerWorker;
        long actualHits = EngineTelemetry.getCacheHits();
        if (actualHits != targetHits) {
            throw new RuntimeException(
                "Telemetry cache-hit counter race condition detected. " +
                "Expected=" + targetHits + ", Received=" + actualHits);
        }
    }

    // =========================================================================
    // (j) Partial range scan with LESS_THAN operator
    // =========================================================================

    private void testPartialRangeScanLessThan() throws Exception {
        for (int i = 1; i <= 100; i++) {
            tree.insert(i, (long) i * 2);
        }

        List<Long> scanMatches = tree.query(50, BPlusTree.Operator.LESS_THAN);

        if (scanMatches.size() != 49) {
            throw new RuntimeException(
                "LESS_THAN scan returned incorrect item count. Expected=49, Received=" + scanMatches.size());
        }

        for (long val : scanMatches) {
            if (val >= 100) {
                throw new RuntimeException(
                    "LESS_THAN query returned record exceeding boundary threshold: " + val);
            }
        }

        for (int i = 1; i < scanMatches.size(); i++) {
            if (scanMatches.get(i) <= scanMatches.get(i - 1)) {
                throw new RuntimeException(
                    "LESS_THAN query results failed ascending order check at index " + i);
            }
        }
    }
}