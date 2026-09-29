package Yggdrasil;

import Yggdrasil.core.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("unused")
public class EngineBenchmarkSuite {

    private static final String BENCHMARK_DIR = "benchmark_data";
    private static final int WARMUP_COUNT = 500;           // Adjusted for disk-heavy engines
    private static final int TOTAL_OPERATIONS = 10_000;     // Scaled for fast execution feedback
    private static final int CONCURRENT_THREADS = 4;

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================");
        System.out.println("         YGGDRASIL ENGINE OPTIMIZED BENCHMARK SUITE             ");
        System.out.println("================================================================");

        cleanupDirectory(new File(BENCHMARK_DIR));

        byte[] masterKey = new byte[16];
        StorageManager storage = new StorageManager(masterKey, BENCHMARK_DIR);
        storage.boot();

        WalManager wal = new WalManager(BENCHMARK_DIR);
        wal.open();

        BPlusTree index = new BPlusTree(storage);
        RecordManager recordMgr = new RecordManager(storage, index, wal);
        recordMgr.redoRecovery();

        byte[] dummyPayload = "BenchMarkPayload_32BytesDataBlock".getBytes(StandardCharsets.UTF_8);

        AtomicBoolean benchmarkRunning = new AtomicBoolean(true);
        Thread heartbeatThread = new Thread(() -> {
            while (benchmarkRunning.get()) {
                try {
                    Thread.sleep(5000); // 5-second interval heartbeat
                    if (benchmarkRunning.get()) {
                        System.out.println("  [HEARTBEAT] Engine processing workload...");
                    }
                } catch (InterruptedException ignored) {}
            }
        });
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        try {
            // 1. Warmup
            System.out.println("\n[1/4] Warming up engine (" + WARMUP_COUNT + " ops)...");
            long warmupStart = System.currentTimeMillis();
            for (int i = 0; i < WARMUP_COUNT; i++) {
                recordMgr.insert(i, dummyPayload);
                if ((i + 1) % 100 == 0) {
                    System.out.println(String.format("   -> Warmup progress: %d / %d ops", (i + 1), WARMUP_COUNT));
                }
            }
            wal.flush();
            System.out.println(String.format("  [DONE] Warmup completed in %.2f sec.", (System.currentTimeMillis() - warmupStart) / 1000.0));

            // 2. Writes
            System.out.println("\n[2/4] Executing " + TOTAL_OPERATIONS + " Sequential Writes...");
            long[] writeLatencies = new long[TOTAL_OPERATIONS];
            long startTime = System.nanoTime();

            for (int i = 0; i < TOTAL_OPERATIONS; i++) {
                long opStart = System.nanoTime();
                recordMgr.insert(i + WARMUP_COUNT, dummyPayload);
                writeLatencies[i] = System.nanoTime() - opStart;

                if ((i + 1) % 1000 == 0) {
                    System.out.println(String.format("   -> Sequential Write progress: %d / %d ops completed", (i + 1), TOTAL_OPERATIONS));
                }
            }
            wal.flush();
            long totalWriteNano = System.nanoTime() - startTime;
            double writeSec = totalWriteNano / 1_000_000_000.0;
            printLatencyReport("Sequential Writes", writeLatencies, TOTAL_OPERATIONS / writeSec, writeSec);

            // 3. Reads
            System.out.println("\n[3/4] Executing " + TOTAL_OPERATIONS + " Random Reads...");
            long[] readLatencies = new long[TOTAL_OPERATIONS];
            Random rand = new Random(1337);
            long readStartTime = System.nanoTime();

            for (int i = 0; i < TOTAL_OPERATIONS; i++) {
                int randomKey = rand.nextInt(TOTAL_OPERATIONS + WARMUP_COUNT);
                long opStart = System.nanoTime();
                recordMgr.find(randomKey);
                readLatencies[i] = System.nanoTime() - opStart;

                if ((i + 1) % 2500 == 0) {
                    System.out.println(String.format("   -> Random Read progress: %d / %d ops completed", (i + 1), TOTAL_OPERATIONS));
                }
            }
            long totalReadNano = System.nanoTime() - readStartTime;
            double readSec = totalReadNano / 1_000_000_000.0;
            printLatencyReport("Random Point Reads", readLatencies, TOTAL_OPERATIONS / readSec, readSec);

            wal.close();
            storage.close();
            System.out.println("\n[SUCCESS] Benchmark run complete.");
        } finally {
            benchmarkRunning.set(false);
        }
    }

    private static void printLatencyReport(String label, long[] latenciesNanos, double opsPerSec, double durationSec) {
        Arrays.sort(latenciesNanos);
        double p50Ms = latenciesNanos[(int) (latenciesNanos.length * 0.50)] / 1_000_000.0;
        double p99Ms = latenciesNanos[(int) (latenciesNanos.length * 0.99)] / 1_000_000.0;

        System.out.println(String.format("  Task: %s", label));
        System.out.println(String.format("    - Duration:    %.3f sec", durationSec));
        System.out.println(String.format("    - Throughput:  %.2f ops/sec", opsPerSec));
        System.out.println(String.format("    - p50 Latency: %.3f ms", p50Ms));
        System.out.println(String.format("    - p99 Latency: %.3f ms", p99Ms));
    }

    private static void cleanupDirectory(File dir) {
        if (dir.exists() && dir.listFiles() != null) {
            for (File f : dir.listFiles()) f.delete();
        }
    }
}