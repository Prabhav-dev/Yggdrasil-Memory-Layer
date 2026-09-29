package Yggdrasil;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import Yggdrasil.core.*;

/**
 * Validates ASCON-128 AEAD disk bit-flip detection and maps
 * output to STATUS_AUTH_TAMPER_ERROR inside RequestDispatcher.
 */
public class TamperSecurityTest {

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================");
        System.out.println("       YGGDRASIL ASCON-128 AEAD TAMPER INTEGRITY SUITE          ");
        System.out.println("================================================================");

        File workDir = new File("tamper_test_data");
        if (workDir.exists()) {
            File[] existingFiles = workDir.listFiles();
            if (existingFiles != null) {
                for (File file : existingFiles) file.delete();
            }
        }
        File dbFile = new File(workDir, StorageManager.DATABASE);

        byte[] masterKey = new byte[16];
        StorageManager storageManager = new StorageManager(masterKey, "tamper_test_data");
        storageManager.boot();
        
        WalManager walManager = new WalManager("tamper_test_data");
        walManager.open();
        
        BPlusTree indexTree = new BPlusTree(storageManager);
        RecordManager recordManager = new RecordManager(storageManager, indexTree, walManager);
        recordManager.redoRecovery();

        // 1. Persist valid payload
        recordManager.insert(100, "secure_payload".getBytes(StandardCharsets.UTF_8));

        // Flush and release locks to persist state to disk
        walManager.flush();
        walManager.close();
        storageManager.close();

        // 2. Introduce single byte bit-flip corruption on physical page 1
        try (RandomAccessFile raf = new RandomAccessFile(dbFile, "rw")) {
            long corruptOffset = Page.PAGE_SIZE + Page.OFFSET_CIPHERTEXT + 5;
            raf.seek(corruptOffset);
            byte originalByte = raf.readByte();
            raf.seek(corruptOffset);
            raf.writeByte(originalByte ^ 0xFF);
            raf.getFD().sync();
        }

        // 3. Restart engine and rebuild dispatcher components
        StorageManager corruptStorage = new StorageManager(masterKey, "tamper_test_data");
        corruptStorage.boot();
        
        WalManager corruptWal = new WalManager("tamper_test_data");
        corruptWal.open();
        
        BPlusTree corruptTree = new BPlusTree(corruptStorage);
        RecordManager corruptRecordMgr = new RecordManager(corruptStorage, corruptTree, corruptWal);

        RequestDispatcher dispatcher = new RequestDispatcher(corruptRecordMgr, corruptTree, null);

        // 4. Dispatch AUDIT_CHECK request -> verify handle of PageAuthenticationException
        WireProtocol.Frame auditRequest = new WireProtocol.Frame(
                new WireProtocol.Header(WireProtocol.MAGIC_BYTE, WireProtocol.OP_AUDIT_CHECK, 5001L, 0),
                new byte[0]
        );

        ByteBuffer responseBuffer = dispatcher.dispatch(auditRequest);
        responseBuffer.position(1);
        byte responseOpCode = responseBuffer.get();
        
        responseBuffer.position(WireProtocol.HEADER_SIZE);
        byte actualStatusCode = (responseOpCode == WireProtocol.OP_RESPONSE_ERR) 
                ? responseBuffer.get() 
                : WireProtocol.STATUS_SUCCESS;

        if (actualStatusCode != WireProtocol.STATUS_AUTH_TAMPER_ERROR) {
            throw new AssertionError("Tamper check failed: Expected STATUS_AUTH_TAMPER_ERROR (0x02), received 0x"
                    + String.format("%02X", actualStatusCode & 0xFF));
        }

        System.out.println("  [PASS] Corrupted disk page caught: returned STATUS_AUTH_TAMPER_ERROR (0x02)");

        corruptStorage.close();
        corruptWal.close();

        System.out.println("\n[SUCCESS] ASCON tamper security test completed cleanly.");
    }
}