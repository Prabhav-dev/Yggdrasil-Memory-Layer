package Yggdrasil;

import java.nio.ByteBuffer;
import Yggdrasil.core.BPlusTree;
import Yggdrasil.core.HeapFile;
import Yggdrasil.core.RecordManager;

import java.util.Objects;

/**
 * RequestDispatcher decodes incoming WireProtocol frames, delegates requests to
 * the core
 * database components (BPlusTree, HeapFile), and formats WireProtocol response
 * buffers.
 */
public class RequestDispatcher {

    private final BPlusTree bTree;
    private final HeapFile heapFile;
    private final RecordManager recordManager;

    @Deprecated
    public RequestDispatcher(BPlusTree bTree, HeapFile heapFile) {
        throw new IllegalArgumentException(
                "RequestDispatcher requires a WAL-backed RecordManager");
    }

    /**
     * Preferred network entry point; all storage mutations use the WAL-aware
     * manager.
     */
    public RequestDispatcher(RecordManager recordManager) {
        this.bTree = null;
        this.heapFile = null;
        this.recordManager = Objects.requireNonNull(recordManager, "recordManager");
    }

    /** Preferred constructor: routes mutations through WAL-aware RecordManager. */
    public RequestDispatcher(RecordManager recordManager, BPlusTree bTree, HeapFile heapFile) {
        this.bTree = bTree;
        this.heapFile = heapFile;
        this.recordManager = Objects.requireNonNull(recordManager, "recordManager");
    }

    /**
     * Processes a parsed WireProtocol Frame and generates an encoded response
     * ByteBuffer.
     *
     * @param frame Parsed input request frame.
     * @return Encoded response ByteBuffer ready to be queued into
     *         ConnectionContext.
     */
    public ByteBuffer dispatch(WireProtocol.Frame frame) {
        long requestId = frame.getRequestId();
        byte opCode = frame.getOpCode();
        byte[] payload = frame.getPayload();

        try {

            switch (opCode) {

                case WireProtocol.OP_PUT:
                    return handlePut(requestId, payload);

                case WireProtocol.OP_GET:
                    return handleGet(requestId, payload);

                case WireProtocol.OP_DELETE:
                    return handleDelete(requestId, payload);

                case WireProtocol.OP_AUDIT_CHECK:
                    return handleAuditCheck(requestId, payload);

                case WireProtocol.OP_APPEND:
                    return handleAppend(requestId, payload);

                case WireProtocol.OP_SCAN:
                    return handleScan(requestId, payload);

                default:
                    return WireProtocol.encodeErrorResponse(
                            requestId,
                            WireProtocol.STATUS_BAD_REQUEST,
                            "Unsupported opcode: 0x"
                                    + String.format("%02X", opCode & 0xFF));
            }

        } catch (Yggdrasil.core.PageAuthenticationException e) {

            return WireProtocol.encodeErrorResponse(
                    requestId,
                    WireProtocol.STATUS_AUTH_TAMPER_ERROR,
                    "Page authentication failed.");

        } catch (Exception e) {
            e.printStackTrace();
            return WireProtocol.encodeErrorResponse(
                    requestId,
                    WireProtocol.STATUS_SERVER_ERROR,
                    "Execution error."
            );
        }
    }

    private ByteBuffer handlePut(long requestId, byte[] payload) throws Exception {
        if (payload == null || payload.length < 4) {
            return WireProtocol.encodeErrorResponse(
                    requestId,
                    WireProtocol.STATUS_BAD_REQUEST,
                    "Execution error."
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int key = buffer.getInt();

        byte[] value = new byte[payload.length - 4];
        buffer.get(value);

        if (recordManager != null) {
            recordManager.insert(key, value);
        } else if (heapFile != null && bTree != null) {
            long tid = heapFile.insertRecord(value);
            if (tid == -1L) {
                return WireProtocol.encodeErrorResponse(
                        requestId,
                        WireProtocol.STATUS_SERVER_ERROR,
                        "Execution error."
                );
            }
            bTree.insert(key, tid);
        }

        return WireProtocol.encodeSuccessResponse(requestId, new byte[0]);
    }

    private ByteBuffer handleGet(long requestId, byte[] payload) throws Exception {
        if (payload == null || payload.length != 4) {
            return WireProtocol.encodeErrorResponse(
                    requestId,
                    WireProtocol.STATUS_BAD_REQUEST,
                    "Execution error."
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int key = buffer.getInt();

        byte[] record;
        if (recordManager != null) {
            record = recordManager.find(key);
        } else if (bTree != null && heapFile != null) {
            Long tid = bTree.search(key);
            record = (tid == null) ? null : heapFile.getRecord(tid);
        } else {
            record = null;
        }

        if (record == null) {
            return WireProtocol.encodeErrorResponse(
                    requestId,
                    WireProtocol.STATUS_NOT_FOUND,
                    "Key not found."
            );
        }

        return WireProtocol.encodeSuccessResponse(requestId, record);
    }

    private ByteBuffer handleDelete(long requestId, byte[] payload) throws Exception {
        if (payload == null || payload.length != 4) {
            return WireProtocol.encodeErrorResponse(
                    requestId,
                    WireProtocol.STATUS_BAD_REQUEST,
                    "Execution error."
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int key = buffer.getInt();

        if (recordManager != null) {
            if (!recordManager.containsKey(key)) {
                return WireProtocol.encodeErrorResponse(
                        requestId,
                        WireProtocol.STATUS_NOT_FOUND,
                        "Key not found."
                );
            }
            recordManager.delete(key);
            return WireProtocol.encodeSuccessResponse(requestId, new byte[0]);
        } else if (bTree != null && heapFile != null) {
            Long tid = bTree.search(key);
            if (tid == null) {
                return WireProtocol.encodeErrorResponse(
                        requestId,
                        WireProtocol.STATUS_NOT_FOUND,
                        "Key not found."
                );
            }
            heapFile.deleteRecord(tid);
            bTree.delete(key);
            return WireProtocol.encodeSuccessResponse(requestId, new byte[0]);
        }

        return WireProtocol.encodeErrorResponse(
                requestId,
                WireProtocol.STATUS_NOT_FOUND,
                "Key not found."
        );
    }

    private ByteBuffer handleAuditCheck(long requestId, byte[] payload) throws Exception {
        if (payload != null && payload.length > 0) {
            return WireProtocol.encodeErrorResponse(
                    requestId,
                    WireProtocol.STATUS_BAD_REQUEST,
                    "Execution error."
            );
        }

        if (recordManager != null) {
            recordManager.auditIntegrity();
        }

        return WireProtocol.encodeSuccessResponse(requestId, new byte[0]);
    }

    private ByteBuffer handleAppend(long requestId, byte[] payload) throws Exception {
        if (payload == null || payload.length > WireProtocol.MAX_PAYLOAD_SIZE - 12) {
            return WireProtocol.encodeErrorResponse(
                    requestId,
                    WireProtocol.STATUS_BAD_REQUEST,
                    "Append payload is too large.");
        }

        int key = recordManager.append(payload);
        ByteBuffer response = ByteBuffer.allocate(Integer.BYTES);
        response.putInt(key);
        return WireProtocol.encodeSuccessResponse(requestId, response.array());
    }

    private ByteBuffer handleScan(long requestId, byte[] payload) throws Exception {
        if (payload == null || payload.length != Long.BYTES + Integer.BYTES) {
            return WireProtocol.encodeErrorResponse(
                    requestId,
                    WireProtocol.STATUS_BAD_REQUEST,
                    "Invalid scan request.");
        }

        ByteBuffer request = ByteBuffer.wrap(payload);
        long afterKey = request.getLong();
        int limit = request.getInt();
        if (limit < 1 || limit > 256) {
            return WireProtocol.encodeErrorResponse(
                    requestId,
                    WireProtocol.STATUS_BAD_REQUEST,
                    "Scan limit must be between 1 and 256.");
        }

        var records = recordManager.scanAfter(afterKey, limit);
        int responseSize = Integer.BYTES;
        int recordCount = 0;
        for (RecordManager.StoredRecord record : records) {
            int entrySize = Integer.BYTES * 2 + record.value().length;
            if (responseSize + entrySize > WireProtocol.MAX_PAYLOAD_SIZE) {
                break;
            }
            responseSize += entrySize;
            recordCount++;
        }

        ByteBuffer response = ByteBuffer.allocate(responseSize);
        response.putInt(recordCount);
        for (int i = 0; i < recordCount; i++) {
            RecordManager.StoredRecord record = records.get(i);
            response.putInt(record.key());
            response.putInt(record.value().length);
            response.put(record.value());
        }
        return WireProtocol.encodeSuccessResponse(requestId, response.array());
    }
}
