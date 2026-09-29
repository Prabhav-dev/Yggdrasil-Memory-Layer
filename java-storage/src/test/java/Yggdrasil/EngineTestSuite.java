package Yggdrasil;

import Yggdrasil.core.*;
import java.io.File;

public class EngineTestSuite {
    protected static final String TEST_DATA_DIR = "btree_data_test";
    protected StorageManager storageManager;
    protected WalManager walManager;
    protected BPlusTree tree;
    protected RecordManager recordManager;

    @FunctionalInterface
    protected interface TestMethod {
        void run() throws Exception;
    }

    protected void setUp() throws Exception {
        deleteDir(new File(TEST_DATA_DIR));
        
        storageManager = new StorageManager(new byte[16], TEST_DATA_DIR);
        storageManager.boot();
        
        walManager = new WalManager(TEST_DATA_DIR);
        walManager.open();
        
        tree = new BPlusTree(storageManager);
        recordManager = new RecordManager(storageManager, tree, walManager);
    }

    protected void rebootEngine() throws Exception {
        storageManager = new StorageManager(new byte[16], TEST_DATA_DIR);
        storageManager.boot();

        walManager = new WalManager(TEST_DATA_DIR);
        walManager.open();

        tree = new BPlusTree(storageManager);
        recordManager = new RecordManager(storageManager, tree, walManager);
    }

    protected void shutdownEngine() {
        try {
            if (walManager != null) {
                walManager.close();
                walManager = null;
            }
            if (storageManager != null) {
                // Close underlying RandomAccessFile to free file locks without flushing dirty pages
                if (storageManager.getRaf() != null) {
                    storageManager.getRaf().close();
                }
                storageManager.close();
                storageManager = null;
            }
            tree = null;
            recordManager = null;
        } catch (Exception ignored) {}
    }

    protected void tearDown() {
        shutdownEngine();
        deleteDir(new File(TEST_DATA_DIR));
    }

    protected void runTest(String testName, TestMethod test) throws Exception {
        System.out.print("Running: " + testName + " ... ");
        try {
            setUp();
            test.run();
            System.out.println("PASSED");
        } catch (Exception e) {
            System.out.println("FAILED");
            throw e;
        } finally {
            tearDown();
        }
    }

    private void deleteDir(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDir(child);
                }
            }
        }
        file.delete();
    }
}