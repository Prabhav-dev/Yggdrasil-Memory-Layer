package Yggdrasil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * FrameAccumulator manages per-channel state for accumulating incoming raw TCP byte streams,
 * parsing binary WireProtocol frames, and queuing outbound response buffers for non-blocking NIO writes.
 */
public class FrameAccumulator {

    private static final int INITIAL_BUFFER_CAPACITY = 8192; // 8 KB read buffer
    private static final int MAX_BUFFER_CAPACITY = WireProtocol.HEADER_SIZE + WireProtocol.MAX_PAYLOAD_SIZE;

    private ByteBuffer readBuffer;
    private final Queue<ByteBuffer> writeQueue;

    public FrameAccumulator() {
        this.readBuffer = ByteBuffer.allocate(INITIAL_BUFFER_CAPACITY);
        this.writeQueue = new ArrayDeque<>();
    }

    /**
     * Compacts or resizes the read buffer to prepare for subsequent socket read operations.
     */
    public void prepareForRead() {
        if (!readBuffer.hasRemaining()) {
            if (readBuffer.position() == readBuffer.capacity()) {
                // Expand buffer capacity if full to accommodate larger incoming frames
                if (readBuffer.capacity() >= MAX_BUFFER_CAPACITY) {
                    throw new IllegalArgumentException("Inbound frame exceeds protocol maximum.");
                }
                int newCapacity = Math.min(MAX_BUFFER_CAPACITY, readBuffer.capacity() * 2);
                ByteBuffer expanded = ByteBuffer.allocate(newCapacity);
                readBuffer.flip();
                expanded.put(readBuffer);
                readBuffer = expanded;
            }
        }
    }

    /**
     * Reads pending bytes from the buffer and parses all complete WireProtocol frames available.
     * Compacts the internal buffer to retain partial frame data for the next socket read cycle.
     *
     * @return Queue of complete decoded frames.
     * @throws IllegalArgumentException if frame header validation fails.
     */
    public Queue<WireProtocol.Frame> processReadBuffer() {
        Queue<WireProtocol.Frame> frames = new ArrayDeque<>();
        readBuffer.flip();

        while (readBuffer.hasRemaining()) {
            readBuffer.mark();
            try {
                WireProtocol.Frame frame = WireProtocol.parseFrame(readBuffer);
                if (frame != null) {
                    frames.add(frame);
                } else {
                    // Partial frame detected; reset to mark and break loop to wait for more data
                    readBuffer.reset();
                    break;
                }
            } catch (IllegalArgumentException e) {
                readBuffer.reset();
                throw e; // Bubble protocol corruption up to close socket channel
            }
        }

        // Compact remaining unparsed bytes to the beginning of the buffer
        readBuffer.compact();
        return frames;
    }

    /**
     * Enqueues an encoded WireProtocol response buffer for non-blocking write back to the client socket.
     *
     * @param buffer Pre-encoded WireProtocol ByteBuffer.
     */
    public synchronized void enqueueResponse(ByteBuffer buffer) {
        if (buffer != null && buffer.hasRemaining()) {
            writeQueue.add(buffer);
        }
    }

    /**
     * Flushes enqueued outbound response buffers to the channel.
     *
     * @param channel Active non-blocking socket channel.
     * @return true if all queued response data was successfully written; false if write blocked.
     * @throws IOException If network I/O error occurs during write.
     */
    public synchronized boolean writePendingResponses(java.nio.channels.SocketChannel channel) throws IOException {
        while (!writeQueue.isEmpty()) {
            ByteBuffer head = writeQueue.peek();
            channel.write(head);

            if (head.hasRemaining()) {
                // Socket buffer full; cannot write complete buffer right now
                return false;
            } else {
                // Buffer fully written, remove from queue
                writeQueue.poll();
            }
        }
        return true;
    }

    /**
     * Checks whether there are pending outbound bytes waiting to be written.
     */
    public synchronized boolean hasPendingWrites() {
        return !writeQueue.isEmpty();
    }

    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }
}
