package Yggdrasil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * WireProtocol defines the binary frame format, operation codes, status codes,
 * and serialization/deserialization routines for the Yggdrasil NIO network
 * engine.
 *
 * Header Format (14 Bytes Total):
 * +------------------+------------------+------------------+----------------------+
 * | Magic Byte (1B) | OpCode (1B) | Request ID (8B) | Payload Length (4B) |
 * +------------------+------------------+------------------+----------------------+
 * | 0x59 ('Y') | byte | long | int |
 * +------------------+------------------+------------------+----------------------+
 *
 * Followed immediately by [Payload Length] bytes of binary payload.
 */
public class WireProtocol {

    public static final byte MAGIC_BYTE = (byte) 0x59; // 'Y' in ASCII
    public static final int HEADER_SIZE = 14; // 1 + 1 + 8 + 4 bytes
    public static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024; // 16 MB max payload limit

    // --- Request Opcodes ---
    public static final byte OP_PUT = 0x01;
    public static final byte OP_GET = 0x02;
    public static final byte OP_DELETE = 0x03;
    public static final byte OP_AUDIT_CHECK = 0x04;
    public static final byte OP_APPEND = 0x05;
    public static final byte OP_SCAN = 0x06;

    // --- Response Opcodes ---
    public static final byte OP_RESPONSE_OK = (byte) 0x80;
    public static final byte OP_RESPONSE_ERR = (byte) 0x81;

    // --- Response Status Codes ---
    public static final byte STATUS_SUCCESS = 0x00;
    public static final byte STATUS_NOT_FOUND = 0x01;
    public static final byte STATUS_AUTH_TAMPER_ERROR = 0x02;
    public static final byte STATUS_BAD_REQUEST = 0x03;
    public static final byte STATUS_SERVER_ERROR = 0x04;
    public static final byte STATUS_SERVER_BUSY = 0x05;

    /**
     * Represents a parsed binary frame header.
     */
    public static class Header {
        private final byte magic;
        private final byte opCode;
        private final long requestId;
        private final int payloadLength;

        public Header(byte magic, byte opCode, long requestId, int payloadLength) {
            this.magic = magic;
            this.opCode = opCode;
            this.requestId = requestId;
            this.payloadLength = payloadLength;
        }

        public byte getMagic() {
            return magic;
        }

        public byte getOpCode() {
            return opCode;
        }

        public long getRequestId() {
            return requestId;
        }

        public int getPayloadLength() {
            return payloadLength;
        }

        public boolean isValidMagic() {
            return magic == MAGIC_BYTE;
        }

        @Override
        public String toString() {
            return String.format("Header[Magic=0x%02X, OpCode=0x%02X, RequestID=%d, PayloadLen=%d]",
                    magic, opCode, requestId, payloadLength);
        }
    }

    /**
     * Represents a complete network message payload with header metadata.
     */
    public static class Frame {
        private final Header header;
        private final byte[] payload;

        public Frame(Header header, byte[] payload) {
            this.header = header;
            this.payload = payload != null ? payload : new byte[0];
        }

        public Header getHeader() {
            return header;
        }

        public byte[] getPayload() {
            return payload;
        }

        public byte getOpCode() {
            return header.getOpCode();
        }

        public long getRequestId() {
            return header.getRequestId();
        }
    }

    /**
     * Encodes a request frame into a heap byte buffer.
     *
     * @param opCode    Operation code.
     * @param requestId Unique identifier for the client request tracking.
     * @param payload   Optional payload byte array (can be null).
     * @return ByteBuffer framed and ready to be written out to a SocketChannel.
     */
    public static ByteBuffer encodeRequest(byte opCode, long requestId, byte[] payload) {
        int payloadLen = (payload != null) ? payload.length : 0;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payloadLen);

        buffer.put(MAGIC_BYTE);
        buffer.put(opCode);
        buffer.putLong(requestId);
        buffer.putInt(payloadLen);

        if (payloadLen > 0) {
            buffer.put(payload);
        }

        buffer.flip();
        return buffer;
    }

    /**
     * Encodes a success response frame.
     */
    public static ByteBuffer encodeSuccessResponse(long requestId, byte[] payload) {
        return encodeRequest(OP_RESPONSE_OK, requestId, payload);
    }

    /**
     * Encodes an error response frame with a status code and an explicit error
     * string message.
     */
    public static ByteBuffer encodeErrorResponse(long requestId, byte statusCode, String errorMessage) {
        byte[] msgBytes = (errorMessage != null) ? errorMessage.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteBuffer payload = ByteBuffer.allocate(1 + msgBytes.length);
        payload.put(statusCode);
        payload.put(msgBytes);

        return encodeRequest(OP_RESPONSE_ERR, requestId, payload.array());
    }

    /**
     * Attempts to parse a Frame Header from the given ByteBuffer without consuming
     * bytes unless complete.
     *
     * @param buffer Input buffer containing network bytes.
     * @return Header instance if at least 14 bytes are available, otherwise null.
     * @throws IllegalArgumentException if invalid magic byte or exceeded max
     *                                  payload size.
     */
    public static Header parseHeader(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            return null; // Partial header, wait for more network data
        }

        buffer.mark();
        byte magic = buffer.get();
        if (magic != MAGIC_BYTE) {
            buffer.reset();
            throw new IllegalArgumentException(
                    String.format("Invalid Wire Protocol Magic Byte: 0x%02X (Expected 0x%02X)", magic, MAGIC_BYTE));
        }

        byte opCode = buffer.get();

        if (!isValidRequestOpcode(opCode)) {
            buffer.reset();
            throw new IllegalArgumentException(
                    String.format(
                            "Unsupported request opcode: 0x%02X",
                            opCode & 0xFF));
        }

        long requestId = buffer.getLong();
        int payloadLength = buffer.getInt();

        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE) {
            buffer.reset();
            throw new IllegalArgumentException(
                    "Payload length out of bounds: " + payloadLength + " (Max: " + MAX_PAYLOAD_SIZE + ")");
        }

        return new Header(magic, opCode, requestId, payloadLength);
    }

    /**
     * Decodes a complete Frame from a ByteBuffer if enough bytes for header and
     * full body are available.
     * Advance the buffer position upon success.
     *
     * @param buffer Channel socket read buffer.
     * @return Complete Frame object, or null if full frame is not yet available in
     *         the buffer.
     */
    public static Frame parseFrame(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            return null;
        }

        buffer.mark();
        Header header;
        try {
            header = parseHeader(buffer);
        } catch (IllegalArgumentException e) {
            // Unrecoverable frame alignment error
            throw e;
        }

        if (header == null) {
            return null;
        }

        if (buffer.remaining() < header.getPayloadLength()) {
            // Full body not available yet; reset position back to before header read
            buffer.reset();
            return null;
        }

        byte[] payload = new byte[header.getPayloadLength()];
        if (header.getPayloadLength() > 0) {
            buffer.get(payload);
        }

        return new Frame(header, payload);
    }

    public static boolean isValidRequestOpcode(byte opCode) {
        return opCode == OP_PUT
                || opCode == OP_GET
                || opCode == OP_DELETE
            || opCode == OP_AUDIT_CHECK
            || opCode == OP_APPEND
            || opCode == OP_SCAN;
    }
}