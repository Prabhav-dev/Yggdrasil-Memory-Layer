package Yggdrasil;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import Yggdrasil.core.*;

/**
 * Regression suite covering edge cases in WAL parsing, channel reading,
 * torn-write truncations, and LSN sequence tracking.
 */
public final class WalManagerRegressionTest {

    private static final String WORKING_DIR = "engine_test_data";

    private WalManagerRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        new File(WORKING_DIR).mkdirs();
        verifyFragmentedReadHelper();
        verifyTornTailIsDiscarded();
        verifyPageUpdateAdvancesLsn();
        System.out.println("[SUCCESS] WAL manager regression tests passed cleanly.");
    }

    private static void verifyFragmentedReadHelper() throws Exception {
        ReadableByteChannel fragmentedChannel = new ReadableByteChannel() {
            private final byte[] dataSource = {1, 2, 3, 4, 5};
            private int readIndex = 0;

            @Override
            public int read(ByteBuffer destination) {
                if (readIndex == dataSource.length) return -1;
                int chunkSize = Math.min(1 + (readIndex % 2), Math.min(destination.remaining(), dataSource.length - readIndex));
                destination.put(dataSource, readIndex, chunkSize);
                readIndex += chunkSize;
                return chunkSize;
            }

            @Override
            public boolean isOpen() { return true; }

            @Override
            public void close() { }
        };

        ByteBuffer readBuffer = ByteBuffer.allocate(5);
        if (!WalManager.readFullyFrom(fragmentedChannel, readBuffer)) {
            throw new AssertionError("Fragmented WAL channel read was unexpectedly rejected.");
        }
    }

    private static void verifyTornTailIsDiscarded() throws Exception {
        File targetWalFile = new File(WORKING_DIR, "engine.wal");

        // Ensure clean slate without relying on non-deterministic System.gc() call
        if (targetWalFile.exists()) {
            try (RandomAccessFile raf = new RandomAccessFile(targetWalFile, "rw")) {
                raf.setLength(0);
            }
        }

        WalManager wal = new WalManager(WORKING_DIR);
        wal.open();
        wal.logTupleInsert(7, 0x00010001L, new byte[] {9, 8, 7, 6});
        wal.flush();
        wal.close();

        // Truncate file mid-record to simulate a torn write / un-flushed tail
        try (RandomAccessFile raf = new RandomAccessFile(targetWalFile, "rw")) {
            raf.setLength(raf.length() - 3);
        }

        WalManager recoveryManager = new WalManager(WORKING_DIR);
        recoveryManager.open();

        if (targetWalFile.length() != 0) {
            recoveryManager.close();
            throw new AssertionError("Torn WAL record tail was not properly discarded on recovery initialization.");
        }
        recoveryManager.close();
    }

    private static void verifyPageUpdateAdvancesLsn() throws Exception {
        File targetWalFile = new File(WORKING_DIR, "engine.wal");
        
        WalManager wal = new WalManager(WORKING_DIR);
        wal.open();
        wal.truncateLog(); // Start with empty state

        long lsn1 = wal.logPageUpdate(3, new byte[] {1, 2});
        long lsn2 = wal.logTupleDelete(4, 5L);

        if (lsn1 != 1 || lsn2 != 2) {
            wal.close();
            throw new AssertionError("Page update operation broke LSN sequence progression.");
        }

        wal.close();

        if (targetWalFile.length() == 0) {
            throw new AssertionError("Page update entry was not flushed to disk.");
        }

        if (!targetWalFile.delete()) {
            targetWalFile.deleteOnExit();
        }
    }
}