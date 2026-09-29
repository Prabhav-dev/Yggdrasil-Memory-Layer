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
 * Full loop TCP socket wire protocol integration test.
 */
public class TcpIntegrationTest {

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================");
        System.out.println("           YGGDRASIL FULL TCP SOCKET INTEGRATION TEST           ");
        System.out.println("================================================================");

        File workDir = new File("tcp_test_data");
        if (workDir.exists()) {
            File[] files = workDir.listFiles();
            if (files != null) {
                for (File file : files) file.delete();
            }
        }

        byte[] rootKey = new byte[16];
        StorageManager storageManager = new StorageManager(rootKey, "tcp_test_data");
        storageManager.boot();
        
        WalManager walManager = new WalManager("tcp_test_data");
        walManager.open();
        
        BPlusTree indexTree = new BPlusTree(storageManager);
        RecordManager recordManager = new RecordManager(storageManager, indexTree, walManager);
        recordManager.redoRecovery();

        RequestDispatcher dispatcher = new RequestDispatcher(recordManager, indexTree, null);
        NioServer server = new NioServer(0, dispatcher, 2);

        Thread serverRunner = new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {}
        });
        serverRunner.start();

        while (server.getBoundPort() == -1) {
            Thread.sleep(50);
        }

        int serverPort = server.getBoundPort();
        System.out.println("Server listening on dynamic port: " + serverPort);

        try (Socket clientSocket = new Socket("127.0.0.1", serverPort);
             DataOutputStream networkOut = new DataOutputStream(clientSocket.getOutputStream());
             DataInputStream networkIn = new DataInputStream(clientSocket.getInputStream())) {

            long sequenceId = 1001;

            // 1. PUT key 42, value "hello"
            byte[] valuePayload = "hello".getBytes(StandardCharsets.UTF_8);
            ByteBuffer putBuffer = ByteBuffer.allocate(4 + valuePayload.length);
            putBuffer.putInt(42);
            putBuffer.put(valuePayload);

            writeWireRequest(networkOut, WireProtocol.OP_PUT, sequenceId++, putBuffer.array());
            byte[] putResponse = readWireResponse(networkIn);
            assertResponseCode(putResponse, WireProtocol.STATUS_SUCCESS, "PUT Key 42");

            // 2. GET key 42 -> verify string value
            ByteBuffer getBuffer = ByteBuffer.allocate(4);
            getBuffer.putInt(42);
            
            writeWireRequest(networkOut, WireProtocol.OP_GET, sequenceId++, getBuffer.array());
            byte[] getResponse = readWireResponse(networkIn);
            assertResponseCode(getResponse, WireProtocol.STATUS_SUCCESS, "GET Key 42");
            
            byte[] returnedData = unpackResponsePayload(getResponse);
            String unpackedString = new String(returnedData, StandardCharsets.UTF_8);
            if (!"hello".equals(unpackedString)) {
                throw new AssertionError("GET expected 'hello', received: " + unpackedString);
            }
            System.out.println("  [PASS] GET returned matching string value: 'hello'");

            byte[] firstAppend = "memory-revision-1".getBytes(StandardCharsets.UTF_8);
            writeWireRequest(networkOut, WireProtocol.OP_APPEND, sequenceId++, firstAppend);
            byte[] firstAppendResponse = readWireResponse(networkIn);
            assertResponseCode(firstAppendResponse, WireProtocol.STATUS_SUCCESS, "APPEND first record");
            int firstAppendKey = ByteBuffer.wrap(unpackResponsePayload(firstAppendResponse)).getInt();

            byte[] secondAppend = "memory-revision-2".getBytes(StandardCharsets.UTF_8);
            writeWireRequest(networkOut, WireProtocol.OP_APPEND, sequenceId++, secondAppend);
            byte[] secondAppendResponse = readWireResponse(networkIn);
            assertResponseCode(secondAppendResponse, WireProtocol.STATUS_SUCCESS, "APPEND second record");
            int secondAppendKey = ByteBuffer.wrap(unpackResponsePayload(secondAppendResponse)).getInt();
            if (secondAppendKey != firstAppendKey + 1) {
                throw new AssertionError("APPEND keys must increase monotonically.");
            }

            long afterKey = Integer.MIN_VALUE - 1L;
            for (int expectedKey : new int[] { 42, firstAppendKey, secondAppendKey }) {
                ByteBuffer scanRequest = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
                scanRequest.putLong(afterKey);
                scanRequest.putInt(1);
                writeWireRequest(networkOut, WireProtocol.OP_SCAN, sequenceId++, scanRequest.array());
                byte[] scanResponse = readWireResponse(networkIn);
                assertResponseCode(scanResponse, WireProtocol.STATUS_SUCCESS, "SCAN page after " + afterKey);

                ByteBuffer scanPayload = ByteBuffer.wrap(unpackResponsePayload(scanResponse));
                if (scanPayload.getInt() != 1) {
                    throw new AssertionError("Expected exactly one record in the bounded scan page.");
                }
                int actualKey = scanPayload.getInt();
                int valueLength = scanPayload.getInt();
                byte[] actualValue = new byte[valueLength];
                scanPayload.get(actualValue);
                if (actualKey != expectedKey) {
                    throw new AssertionError("Expected scan key " + expectedKey + ", received " + actualKey);
                }
                String expectedValue = expectedKey == 42
                        ? "hello"
                        : expectedKey == firstAppendKey ? "memory-revision-1" : "memory-revision-2";
                if (!expectedValue.equals(new String(actualValue, StandardCharsets.UTF_8))) {
                    throw new AssertionError("SCAN returned the wrong value for key " + actualKey);
                }
                afterKey = actualKey;
            }

            // 3. AUDIT_CHECK -> status ok
            writeWireRequest(networkOut, WireProtocol.OP_AUDIT_CHECK, sequenceId++, new byte[0]);
            byte[] auditResponse = readWireResponse(networkIn);
            assertResponseCode(auditResponse, WireProtocol.STATUS_SUCCESS, "AUDIT_CHECK");

            // 4. DELETE key 42 -> status ok
            writeWireRequest(networkOut, WireProtocol.OP_DELETE, sequenceId++, getBuffer.array());
            byte[] deleteResponse = readWireResponse(networkIn);
            assertResponseCode(deleteResponse, WireProtocol.STATUS_SUCCESS, "DELETE Key 42");

            // 5. GET key 42 -> verify status STATUS_NOT_FOUND
            writeWireRequest(networkOut, WireProtocol.OP_GET, sequenceId++, getBuffer.array());
            byte[] deletedLookupResponse = readWireResponse(networkIn);
            assertResponseCode(deletedLookupResponse, WireProtocol.STATUS_NOT_FOUND, "GET Deleted Key 42");
        }

        // Cleanup
        server.stop();
        serverRunner.join(1000);
        storageManager.close();
        walManager.close();

        System.out.println("\n[SUCCESS] TCP network integration test completed cleanly.");
    }

    private static void writeWireRequest(DataOutputStream out, byte opCode, long sequenceId, byte[] payload) throws IOException {
        ByteBuffer encodedFrame = WireProtocol.encodeRequest(opCode, sequenceId, payload);
        out.write(encodedFrame.array());
        out.flush();
    }

    private static byte[] readWireResponse(DataInputStream in) throws IOException {
        byte[] headerBytes = new byte[WireProtocol.HEADER_SIZE];
        in.readFully(headerBytes);
        
        ByteBuffer headerReader = ByteBuffer.wrap(headerBytes);
        byte magicByte = headerReader.get();
        byte opCode = headerReader.get();
        long sequenceId = headerReader.getLong();
        int payloadLength = headerReader.getInt();

        if (magicByte != WireProtocol.MAGIC_BYTE) {
            throw new IOException("Invalid wire magic header byte: " + magicByte);
        }
        if (opCode != WireProtocol.OP_RESPONSE_OK && opCode != WireProtocol.OP_RESPONSE_ERR) {
            throw new IOException("Unexpected wire protocol opcode response: " + opCode);
        }
        if (sequenceId < 0) {
            throw new IOException("Negative wire sequence ID received: " + sequenceId);
        }

        byte[] payloadBytes = new byte[payloadLength];
        if (payloadLength > 0) {
            in.readFully(payloadBytes);
        }

        ByteBuffer fullFrame = ByteBuffer.allocate(WireProtocol.HEADER_SIZE + payloadLength);
        fullFrame.put(headerBytes);
        fullFrame.put(payloadBytes);
        return fullFrame.array();
    }

    private static void assertResponseCode(byte[] responseBytes, byte expectedStatus, String taskLabel) {
        ByteBuffer frameReader = ByteBuffer.wrap(responseBytes);
        byte magicByte = frameReader.get();
        byte responseOpCode = frameReader.get();
        long sequenceId = frameReader.getLong();
        int payloadLength = frameReader.getInt();

        if (magicByte != WireProtocol.MAGIC_BYTE || sequenceId < 0 || payloadLength < 0) {
            throw new AssertionError("Malformed header response frame in " + taskLabel);
        }

        byte actualStatus;
        if (responseOpCode == WireProtocol.OP_RESPONSE_OK) {
            actualStatus = WireProtocol.STATUS_SUCCESS;
        } else if (responseOpCode == WireProtocol.OP_RESPONSE_ERR) {
            actualStatus = frameReader.get();
        } else {
            throw new AssertionError("Invalid wire response opcode: 0x" + String.format("%02X", responseOpCode & 0xFF));
        }

        if (actualStatus != expectedStatus) {
            throw new AssertionError("[" + taskLabel + "] check failed: Expected status 0x"
                    + String.format("%02X", expectedStatus & 0xFF) + ", received 0x"
                    + String.format("%02X", actualStatus & 0xFF));
        }
        System.out.println("  [PASS] " + taskLabel + " -> Status 0x" + String.format("%02X", actualStatus & 0xFF));
    }

    private static byte[] unpackResponsePayload(byte[] responseBytes) {
        ByteBuffer frameReader = ByteBuffer.wrap(responseBytes);
        frameReader.position(WireProtocol.HEADER_SIZE);
        byte[] payloadData = new byte[frameReader.remaining()];
        frameReader.get(payloadData);
        return payloadData;
    }
}