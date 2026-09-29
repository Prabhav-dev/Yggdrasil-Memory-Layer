package Yggdrasil;

/**
 * Primary execution driver for the Yggdrasil test suite ecosystem.
 * Coordinates execution of core engine, network, security, and WAL diagnostics.
 */
public class RunTests {

    public static void main(String[] args) {
        System.out.println("================================================================");
        System.out.println("      YGGDRASIL ENGINE & NETWORK TEST SUITE EXECUTION           ");
        System.out.println("================================================================\n");

        runSuite("WalManagerSelfTest", args, 1, "WAL Standalone Diagnostics");
        runSuite("WalCrashDebugTest", args, 2, "WAL Crash Recovery Pipeline");
        runSuite("WalManagerRegressionTest", args, 3, "WAL Manager Edge Case & Regression Checks");
        runSuite("ComprehensiveEngineTestSuite", args, 4, "B+ Tree Indexing & Core Storage Engine");
        runSuite("EngineMetricsTestSuite", args, 5, "Engine Telemetry & Extended Storage Dynamics");
        runSuite("FuzzyEngineTestSuite", args, 6, "Fuzzing & Concurrency Stress Diagnostics");
        runSuite("AsconNistVectorTest", args, 7, "NIST ASCON-128 Known Answer Tests (KAT)");
        runSuite("StorageSecurityTest", args, 8, "Storage Layer Page Integrity Security");
        runSuite("TamperSecurityTest", args, 9, "ASCON AEAD Disk Tamper Rejection");
        runSuite("TcpIntegrationTest", args, 10, "TCP Socket Network Wire Protocol");
        runSuite("ServerConcurrencyTest", args, 11, "NIO Server Concurrency & Connection Throttling");
        runSuite("MemoryLeakDetectionTest", args, 12, "Memory Leak Detection & Resource Cleanup");
        runSuite("EngineBenchmarkSuite", args, 13, "Engine Benchmark Suite");
        runSuite("StorageCapacityTest", args, 14, "Storage Capacity Arithmetic Boundary");

        System.out.println("\n================================================================");
        System.out.println(" [SUCCESS] ALL YGGDRASIL STORAGE & NETWORK TESTS PASSED CLEANLY!");
        System.out.println("================================================================");
    }

    private static void runSuite(String className, String[] args, int stepNumber, String suiteLabel) {
        System.out.println("----------------------------------------------------------------");
        System.out.println(" ---> [" + stepNumber + "] Running: " + suiteLabel + " (" + className + ")");
        System.out.println("----------------------------------------------------------------");

        try {
            switch (className) {
                case "WalManagerSelfTest" -> WalManagerSelfTest.main(args);
                case "WalCrashDebugTest" -> WalCrashDebugTest.main(args);
                case "WalManagerRegressionTest" -> WalManagerRegressionTest.main(args);
                case "ComprehensiveEngineTestSuite" -> ComprehensiveEngineTestSuite.main(args);
                case "EngineMetricsTestSuite" -> EngineMetricsTestSuite.main(args);
                case "FuzzyEngineTestSuite" -> FuzzyEngineTestSuite.main(args);
                case "AsconNistVectorTest" -> AsconNistVectorTest.main(args);
                case "StorageSecurityTest" -> StorageSecurityTest.main(args);
                case "TamperSecurityTest" -> TamperSecurityTest.main(args);
                case "TcpIntegrationTest" -> TcpIntegrationTest.main(args);
                case "ServerConcurrencyTest" -> ServerConcurrencyTest.main(args);
                case "MemoryLeakDetectionTest" -> MemoryLeakDetectionTest.main(args);
                case "EngineBenchmarkSuite" -> EngineBenchmarkSuite.main(args);
                case "StorageCapacityTest" -> StorageCapacityTest.main(args);
                default -> throw new IllegalArgumentException("Unrecognized test suite identifier: " + className);
            }
            System.out.println();
        } catch (Throwable ex) {
            System.err.println("\n[FATAL] Test execution failure during suite: " + className);
            throw new RuntimeException("Test execution failed for suite: " + className, ex);
        }
    }
}