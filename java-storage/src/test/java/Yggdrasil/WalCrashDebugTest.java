package Yggdrasil;

import Yggdrasil.core.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Diagnostic test harness verifying WAL crash recovery sequence,
 * tracking TupleIDs and page/slot allocations across engine reboots.
 */
public class WalCrashDebugTest {
    public static void main(String[] args) throws Exception {
        System.out.println("================================================================");
        System.out.println("          WAL CRASH RECOVERY DIAGNOSTIC TEST HARNESS            ");
        System.out.println("================================================================");

        // Purge stale data directory
        File storageDir = new File(StorageManager.DATA);
        if (storageDir.exists() && storageDir.listFiles() != null) {
            for (File file : storageDir.listFiles()) {
                file.delete();
            }
        }

        // === PHASE 1: Write initial state and simulate engine crash ===
        System.out.println("\n[PHASE 1] Initializing fresh storage engine...");
        StorageManager storagePhase1 = new StorageManager();
        storagePhase1.boot();

        WalManager walPhase1 = new WalManager();
        walPhase1.open();
        walPhase1.truncateLog();

        BPlusTree indexTree1 = new BPlusTree(storagePhase1);
        RecordManager recordMgr1 = new RecordManager(storagePhase1, indexTree1, walPhase1);

        int recordKey = 500;
        byte[] rawPayload = "Uncommitted Row 1".getBytes(StandardCharsets.UTF_8);
        recordMgr1.insert(recordKey, rawPayload);

        Long initialTupleId = indexTree1.search(recordKey);
        System.out.println("  Inserted TupleID: " + initialTupleId + 
                           " -> Page: " + TupleID.getPageNumber(initialTupleId) + 
                           ", Slot: " + TupleID.getSlotOffset(initialTupleId));

        System.out.println("  WAL file size before shutdown: " + walPhase1.getWalFile().length() + " bytes");

        // Simulate abrupt crash by closing handles
        walPhase1.close();
        storagePhase1.close();

        // Release references to allow Windows OS file handles and unmapped buffers to clear
        recordMgr1 = null;
        indexTree1 = null;
        System.gc();
        Thread.sleep(20);

        System.out.println("  WAL file size post-shutdown: " + walPhase1.getWalFile().length() + " bytes");
        System.out.println("[PHASE 1] Engine crash simulation complete.\n");

        // === PHASE 2: Reboot engine and execute WAL REDO recovery ===
        System.out.println("[PHASE 2] Rebooting storage engine...");
        StorageManager storagePhase2 = new StorageManager();
        storagePhase2.boot();

        WalManager walPhase2 = new WalManager();
        walPhase2.open();
        System.out.println("  WAL file size on startup: " + walPhase2.getWalFile().length() + " bytes");

        BPlusTree indexTree2 = new BPlusTree(storagePhase2);
        RecordManager recordMgr2 = new RecordManager(storagePhase2, indexTree2, walPhase2);

        System.out.println("  Pre-REDO B+Tree lookup (key " + recordKey + "): " + indexTree2.search(recordKey));

        // Execute recovery
        recordMgr2.redoRecovery();

        Long recoveredTupleId = indexTree2.search(recordKey);
        System.out.println("  Post-REDO TupleID: " + recoveredTupleId);
        if (recoveredTupleId != null) {
            System.out.println("    -> Page: " + TupleID.getPageNumber(recoveredTupleId) + 
                               ", Slot: " + TupleID.getSlotOffset(recoveredTupleId));
        }

        System.out.println("  Executing RecordManager.find(" + recordKey + ")...");
        byte[] recoveredPayload = recordMgr2.find(recordKey);

        if (recoveredPayload != null && Arrays.equals(rawPayload, recoveredPayload)) {
            System.out.println("  [PASS] Successfully recovered expected row payload.");
        } else {
            String actualStr = (recoveredPayload == null) ? "null" : new String(recoveredPayload, StandardCharsets.UTF_8);
            System.out.println("  [FAIL] Payload corrupt or missing. Found: " + actualStr);
        }

        walPhase2.close();
        storagePhase2.close();
        System.out.println("\n[SUCCESS] WAL crash diagnostic completed.");
    }
}