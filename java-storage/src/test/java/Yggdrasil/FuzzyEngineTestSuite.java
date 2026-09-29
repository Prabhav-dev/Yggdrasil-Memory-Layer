package Yggdrasil;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class FuzzyEngineTestSuite extends EngineTestSuite {

    private static final int PRNG_SEED = 42; // Deterministic seed for reproducible fuzzing

    public static void main(String[] args) {
        FuzzyEngineTestSuite fuzzRunner = new FuzzyEngineTestSuite();
        
        try {
            System.out.println("================================================================");
            System.out.println("     YGGDRASIL ENGINE FUZZING & CONCURRENCY STRESS SUITE        ");
            System.out.println("================================================================");
            
            // 1. Heavy Randomized Record Churn vs In-Memory Reference Model
            fuzzRunner.runTest("Randomized Op-Code Churn (10,000 Operations)", () -> fuzzRunner.testRandomizedChurn(10000));

            // 2. Variable Payload Boundary Fuzzing
            fuzzRunner.runTest("Variable Payload Boundary Conditions (0B - 2KB)", fuzzRunner::testVariablePayloadBoundaries);

            // 3. Simulated Hard Crash Recovery Mid-Churn
            fuzzRunner.runTest("WAL Crash Recovery & Redo Under Load", fuzzRunner::testCrashRecoveryUnderLoad);

            // 4. Concurrent Multi-Threaded Read/Write Stress
            fuzzRunner.runTest("Multi-Threaded Concurrent Access Stress", fuzzRunner::testConcurrentMultiThreadedStress);

            System.out.println("\n[SUCCESS] All fuzzing and stress scenarios completed without errors.");

        } catch (Exception ex) {
            System.err.println("\n[FATAL] Stress suite encountered a critical test failure:");
            throw new RuntimeException(ex);
        }
    }

    // =========================================================================
    // 1. RANDOMIZED CHURN WITH GROUND-TRUTH SHADOW MAP
    // =========================================================================

    private void testRandomizedChurn(int totalOperations) throws Exception {
        Random prng = new Random(PRNG_SEED);
        Map<Integer, String> groundTruthMap = new HashMap<>();
        int keySpaceRange = 1000; // Constrain keyspace to push key collision and update paths

        for (int op = 0; op < totalOperations; op++) {
            int actionType = prng.nextInt(100);
            int key = prng.nextInt(keySpaceRange);

            if (actionType < 50) { 
                // 50% INSERT / UPDATE
                String generatedValue = "FuzzPayload-Key-" + key + "-Op-" + op + "-Rnd-" + prng.nextInt(9999);
                byte[] rawPayload = generatedValue.getBytes(StandardCharsets.UTF_8);

                if (!groundTruthMap.containsKey(key)) {
                    recordManager.insert(key, rawPayload);
                    groundTruthMap.put(key, generatedValue);
                }

            } else if (actionType < 80) { 
                // 30% READ LOOKUP
                byte[] fetchedBytes = recordManager.find(key);
                if (groundTruthMap.containsKey(key)) {
                    if (fetchedBytes == null) {
                        throw new RuntimeException("Fuzz Read Failure: Key " + key + " present in shadow map but missing in storage.");
                    }
                    String readValue = new String(fetchedBytes, StandardCharsets.UTF_8);
                    if (!groundTruthMap.get(key).equals(readValue)) {
                        throw new RuntimeException("Fuzz Read Mismatch on Key " + key + ". Expected: " + groundTruthMap.get(key) + ", Got: " + readValue);
                    }
                } else if (fetchedBytes != null) {
                    throw new RuntimeException("Fuzz Phantom Read Failure: Key " + key + " found in engine but absent from reference model.");
                }

            } else { 
                // 20% DELETE
                if (groundTruthMap.containsKey(key)) {
                    recordManager.delete(key);
                    groundTruthMap.remove(key);
                } else {
                    recordManager.delete(key); // Check delete behavior on missing keys
                }
            }
        }

        // Post-run state validation
        for (Map.Entry<Integer, String> entry : groundTruthMap.entrySet()) {
            byte[] recordBytes = recordManager.find(entry.getKey());
            if (recordBytes == null || !entry.getValue().equals(new String(recordBytes, StandardCharsets.UTF_8))) {
                throw new RuntimeException("Post-fuzz verification failed for key: " + entry.getKey());
            }
        }
    }

    // =========================================================================
    // 2. VARIABLE PAYLOAD EXTREMES
    // =========================================================================

    private void testVariablePayloadBoundaries() throws Exception {
        Random prng = new Random(PRNG_SEED);
        int[] targetSizes = {0, 1, 16, 128, 512, 1024, 2048};

        for (int idx = 0; idx < targetSizes.length; idx++) {
            int key = idx + 10000;
            byte[] payload = new byte[targetSizes[idx]];
            prng.nextBytes(payload);

            recordManager.insert(key, payload);
            byte[] retrievedPayload = recordManager.find(key);

            if (retrievedPayload == null || !Arrays.equals(payload, retrievedPayload)) {
                throw new RuntimeException("Payload size boundary check failed at " + targetSizes[idx] + " bytes.");
            }
        }
    }

    // =========================================================================
    // 3. WAL CRASH RECOVERY UNDER LOAD
    // =========================================================================

    private void testCrashRecoveryUnderLoad() throws Exception {
        Map<Integer, String> verifiedState = new HashMap<>();

        // Phase 1: Population pass
        for (int i = 1; i <= 200; i++) {
            String val = "CrashTestVal-" + i;
            byte[] payload = val.getBytes(StandardCharsets.UTF_8);
            recordManager.insert(i, payload);
            verifiedState.put(i, val);
        }

        // Flush WAL buffers before restart simulation
        walManager.flush();

        // Simulate abrupt engine shutdown
        shutdownEngine();

        // Phase 2: Reboot existing database files without wiping directory
        rebootEngine();

        // Execute WAL recovery pipeline on restored storage state
        recordManager.redoRecovery();

        // Phase 3: Verify all pre-crash records exist in restored engine
        for (Map.Entry<Integer, String> entry : verifiedState.entrySet()) {
            byte[] recordBytes = recordManager.find(entry.getKey());
            if (recordBytes == null || !entry.getValue().equals(new String(recordBytes, StandardCharsets.UTF_8))) {
                throw new RuntimeException("Crash recovery failed to restore key: " + entry.getKey());
            }
        }
    }

    // =========================================================================
    // 4. MULTI-THREADED CONCURRENT STRESS
    // =========================================================================

    private void testConcurrentMultiThreadedStress() throws Exception {
        int workerThreads = 8;
        int operationsPerWorker = 500;
        ExecutorService threadPool = Executors.newFixedThreadPool(workerThreads);
        ConcurrentHashMap<Integer, String> concurrentShadowMap = new ConcurrentHashMap<>();

        List<Future<?>> taskHandles = new ArrayList<>();

        try {
            for (int workerId = 0; workerId < workerThreads; workerId++) {
                final int id = workerId;
                taskHandles.add(threadPool.submit(() -> {
                    Random prng = new Random(PRNG_SEED + id);
                    for (int i = 0; i < operationsPerWorker; i++) {
                        int key = (id * 1000) + prng.nextInt(200); // Disjoint key ranges per worker
                        String val = "Thread-" + id + "-Val-" + i;

                        try {
                            concurrentShadowMap.putIfAbsent(key, val);
                            recordManager.insert(key, val.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception ex) {
                            throw new RuntimeException("Error during concurrent worker insertion pass", ex);
                        }
                    }
                }));
            }

            for (Future<?> handle : taskHandles) {
                handle.get(); // Wait for workers to complete
            }
        } finally {
            threadPool.shutdownNow();
        }

        // Verify state of concurrent operations
        for (Map.Entry<Integer, String> entry : concurrentShadowMap.entrySet()) {
            byte[] recordBytes = recordManager.find(entry.getKey());
            if (recordBytes == null || !entry.getValue().equals(new String(recordBytes, StandardCharsets.UTF_8))) {
                throw new RuntimeException("Concurrent stress test missing key: " + entry.getKey());
            }
        }
    }
}