package Yggdrasil;

import Yggdrasil.core.*;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Standalone self-contained WAL manager test implementation
 * validating log formatting, appending, flushing, and replay parsing.
 */
public class WalManagerSelfTest {

    private final String walFilePath;
    private RandomAccessFile channelFile;
    private long currentLsn = 0;

    @FunctionalInterface
    public interface LogReplayConsumer {
        void accept(long lsn, byte opType, int key, long tupleID, byte[] payload) throws Exception;
    }

    public WalManagerSelfTest(String walFilePath) {
        this.walFilePath = walFilePath;
    }

    public synchronized void open() throws Exception {
        File targetFile = new File(walFilePath);
        if (targetFile.getParentFile() != null && !targetFile.getParentFile().exists()) {
            targetFile.getParentFile().mkdirs();
        }
        this.channelFile = new RandomAccessFile(targetFile, "rw");
    }

    public synchronized long appendInsert(int key, long tupleID, byte[] payload) throws Exception {
        currentLsn++;
        int entrySize = 8 + 1 + 4 + 8 + 4 + payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(entrySize);

        buffer.putLong(currentLsn);
        buffer.put(WalManager.OP_INSERT_TUPLE);
        buffer.putInt(key);
        buffer.putLong(tupleID);
        buffer.putInt(payload.length);
        buffer.put(payload);

        channelFile.seek(channelFile.length());
        channelFile.write(buffer.array());

        return currentLsn;
    }

    public synchronized long appendDelete(int key, long tupleID) throws Exception {
        currentLsn++;
        ByteBuffer buffer = ByteBuffer.allocate(8 + 1 + 4 + 8);
        
        buffer.putLong(currentLsn);
        buffer.put(WalManager.OP_DELETE_TUPLE);
        buffer.putInt(key);
        buffer.putLong(tupleID);

        channelFile.seek(channelFile.length());
        channelFile.write(buffer.array());
        
        return currentLsn;
    }

    public synchronized void flush() throws Exception {
        if (channelFile != null) {
            channelFile.getFD().sync();
        }
    }

    public synchronized void replayLog(LogReplayConsumer consumer) throws Exception {
        if (channelFile == null) return;

        channelFile.seek(0);
        while (channelFile.getFilePointer() < channelFile.length()) {
            long lsn = channelFile.readLong();
            byte opType = channelFile.readByte();
            int key = channelFile.readInt();
            long tupleID = channelFile.readLong();
            int payloadLen = (opType == WalManager.OP_INSERT_TUPLE) ? channelFile.readInt() : 0;

            byte[] payloadBytes = new byte[payloadLen];
            channelFile.readFully(payloadBytes);

            consumer.accept(lsn, opType, key, tupleID, payloadBytes);
            if (lsn > currentLsn) {
                currentLsn = lsn;
            }
        }
    }

    public synchronized void checkpoint() throws Exception {
        if (channelFile != null) {
            channelFile.setLength(0);
            currentLsn = 0;
        }
    }

    public synchronized void close() throws Exception {
        if (channelFile != null) {
            channelFile.close();
            channelFile = null;
        }
    }

    // =========================================================================
    // MAIN ENTRY POINT FOR STANDALONE TEST
    // =========================================================================
    public static void main(String[] args) {
        System.out.println("================================================================");
        System.out.println("        STANDALONE WAL MANAGER SELF-TEST EXECUTION             ");
        System.out.println("================================================================");
        
        String targetWalPath = "btree_data/standalone_wal.log";

        try {
            // Purge old log file if present
            File legacyLog = new File(targetWalPath);
            if (legacyLog.exists()) {
                legacyLog.delete();
            }

            // 1. Initialize and append records
            WalManagerSelfTest testWal = new WalManagerSelfTest(targetWalPath);
            testWal.open();

            System.out.print("Appending WAL records... ");
            long lsn1 = testWal.appendInsert(101, 0x00010002L, "INSERT_TUPLE_1".getBytes());
            long lsn2 = testWal.appendDelete(101, 0x00010002L);
            testWal.flush();
            testWal.close();
            System.out.println("PASSED (LSNs: " + lsn1 + ", " + lsn2 + ")");

            // 2. Replay log and verify recovery state
            System.out.print("Replaying log records for crash recovery... ");
            WalManagerSelfTest recoveryWal = new WalManagerSelfTest(targetWalPath);
            recoveryWal.open();

            int[] recordCounter = {0};
            recoveryWal.replayLog((lsn, opType, key, tupleID, payload) -> {
                if (tupleID != 0x00010002L) {
                    throw new IllegalStateException("Tuple ID field corrupted during log replay.");
                }
                if (lsn == 1 && (opType != WalManager.OP_INSERT_TUPLE || key != 101
                        || !Arrays.equals(payload, "INSERT_TUPLE_1".getBytes()))) {
                    throw new IllegalStateException("Insert record fields misaligned during replay.");
                }
                if (lsn == 2 && (opType != WalManager.OP_DELETE_TUPLE || key != 101
                        || payload.length != 0)) {
                    throw new IllegalStateException("Delete record fields misaligned during replay.");
                }
                recordCounter[0]++;
            });

            recoveryWal.close();

            if (recordCounter[0] != 2) {
                throw new AssertionError("Expected 2 records during replay, found " + recordCounter[0]);
            }
            System.out.println("PASSED (Successfully replayed 2 records)");
            System.out.println("\n[SUCCESS] Standalone WAL manager test completed cleanly.");

        } catch (Exception ex) {
            System.err.println("\n[FATAL] Standalone WAL test failed with exception:");
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }
}