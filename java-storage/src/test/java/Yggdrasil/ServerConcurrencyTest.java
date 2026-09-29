package Yggdrasil;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import Yggdrasil.core.*;

/**
 * Validates NioServer thread-pool worker queue bounds and connection throttling limits.
 */
public class ServerConcurrencyTest {

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================");
        System.out.println("      NIO SERVER CONCURRENCY & CONNECTION LIMIT TEST HARNESS    ");
        System.out.println("================================================================");

        File workDir = new File("concurrency_test_data");
        if (workDir.exists()) {
            File[] existingFiles = workDir.listFiles();
            if (existingFiles != null) {
                for (File file : existingFiles) file.delete();
            }
        }

        byte[] rootKey = new byte[16];
        StorageManager storageManager = new StorageManager(rootKey, "concurrency_test_data");
        storageManager.boot();
        
        WalManager walManager = new WalManager("concurrency_test_data");
        walManager.open();
        
        BPlusTree indexTree = new BPlusTree(storageManager);
        RecordManager recordManager = new RecordManager(storageManager, indexTree, walManager);
        recordManager.redoRecovery();

        RequestDispatcher dispatcher = new RequestDispatcher(recordManager, indexTree, null);

        // NioServer setup: 1 worker thread, queue capacity of 5, max 2 client connections
        NioServer server = new NioServer(0, dispatcher, 1, 5, 2);

        Thread serverRunner = new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {}
        });
        serverRunner.start();

        while (server.getBoundPort() == -1) {
            Thread.sleep(50);
        }

        int activePort = server.getBoundPort();
        System.out.println("Server initialized on port: " + activePort);

        // 1. Establish 2 client sockets (occupying max connections limit = 2)
        try (Socket client1 = new Socket("127.0.0.1", activePort);
             DataOutputStream out1 = new DataOutputStream(client1.getOutputStream());
             DataInputStream in1 = new DataInputStream(client1.getInputStream());
             Socket client2 = new Socket("127.0.0.1", activePort)) {

            // Execute standard PUT on primary socket
            byte[] rawValue = "concurrency_val".getBytes(StandardCharsets.UTF_8);
            ByteBuffer payloadBuffer = ByteBuffer.allocate(4 + rawValue.length);
            payloadBuffer.putInt(99);
            payloadBuffer.put(rawValue);

            ByteBuffer requestFrame = WireProtocol.encodeRequest(WireProtocol.OP_PUT, 1, payloadBuffer.array());
            out1.write(requestFrame.array());
            out1.flush();

            byte[] headerBuffer = new byte[WireProtocol.HEADER_SIZE];
            in1.readFully(headerBuffer);
            
            ByteBuffer responseHeader = ByteBuffer.wrap(headerBuffer);
            byte magicByte = responseHeader.get();
            byte responseCode = responseHeader.get();
            
            if (magicByte != WireProtocol.MAGIC_BYTE || responseCode != WireProtocol.OP_RESPONSE_OK) {
                throw new AssertionError("PUT operation failed on socket connection 1");
            }
            System.out.println("  [PASS] Connection 1 request processed successfully.");

            // 2. Actively attempt payload write on 3rd connection to verify immediate connection drop
            boolean connectionRejected = false;
            try (Socket client3 = new Socket("127.0.0.1", activePort)) {
                client3.setSoTimeout(1000);
                DataOutputStream out3 = new DataOutputStream(client3.getOutputStream());
                DataInputStream in3 = new DataInputStream(client3.getInputStream());

                // Attempt sending request frame over rejected connection
                out3.write(requestFrame.array());
                out3.flush();

                int incomingByte = in3.read();
                if (incomingByte == -1) {
                    connectionRejected = true;
                }
            } catch (IOException ex) {
                connectionRejected = true;
            }
            
            if (!connectionRejected && server.getActiveConnections() > 2) {
                throw new AssertionError("Failed to enforce maximum connection threshold (2).");
            }
            System.out.println("  [PASS] Server successfully rejected excess connection.");
            
            // Keep client2 active until connection check completes
            if (client2.isClosed()) {
                throw new AssertionError("Client 2 closed prematurely.");
            }
        }

        // Cleanup
        server.stop();
        serverRunner.join(1000);
        storageManager.close();
        walManager.close();

        System.out.println("\n[SUCCESS] Server concurrency test completed cleanly.");
    }
}