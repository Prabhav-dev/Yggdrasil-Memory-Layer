package Yggdrasil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import Yggdrasil.core.BPlusTree;
import Yggdrasil.core.RecordManager;
import Yggdrasil.core.StorageManager;
import Yggdrasil.core.WalManager;

public final class MemoryStorageServer {

    private MemoryStorageServer() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: MemoryStorageServer <port> <data-directory>");
        }

        String keyHex = System.getenv("YGGDRASIL_ROOT_KEY_HEX");
        if (keyHex == null || !keyHex.matches("(?i)[0-9a-f]{32}")) {
            throw new IllegalStateException("Set YGGDRASIL_ROOT_KEY_HEX to 32 hexadecimal characters.");
        }

        int port = Integer.parseInt(args[0]);
        Path dataDirectory = Path.of(args[1]);
        Files.createDirectories(dataDirectory);
        byte[] rootKey = decodeHex(keyHex);

        StorageManager storageManager = new StorageManager(rootKey, dataDirectory.toString());
        storageManager.boot();
        WalManager walManager = new WalManager(dataDirectory.toString());
        walManager.open();

        BPlusTree indexTree = new BPlusTree(storageManager);
        RecordManager recordManager = new RecordManager(storageManager, indexTree, walManager);
        recordManager.redoRecovery();

        NioServer server = new NioServer("127.0.0.1", port,
                new RequestDispatcher(recordManager, indexTree, null), 2);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        try {
            server.start();
        } finally {
            server.stop();
            storageManager.close();
            walManager.close();
        }
    }

    private static byte[] decodeHex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}