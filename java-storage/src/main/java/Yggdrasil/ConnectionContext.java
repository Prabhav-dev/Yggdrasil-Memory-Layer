package Yggdrasil;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * ConnectionContext wraps a client SocketChannel, its associated SelectionKey,
 * and its dedicated FrameAccumulator for state tracking across non-blocking NIO operations.
 */
public class ConnectionContext {

    private final SocketChannel channel;
    private final SelectionKey selectionKey;
    private final FrameAccumulator accumulator;

    public ConnectionContext(SocketChannel channel, SelectionKey selectionKey) {
        this.channel = channel;
        this.selectionKey = selectionKey;
        this.accumulator = new FrameAccumulator();
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public SelectionKey getSelectionKey() {
        return selectionKey;
    }

    public FrameAccumulator getAccumulator() {
        return accumulator;
    }

    /**
     * Enables or disables OP_WRITE interest on the client channel's SelectionKey.
     * Must be invoked when new response frames are queued or fully flushed.
     *
     * @param enable true to register interest in OP_WRITE; false to clear interest.
     */
    public void setWriteInterest(boolean enable) {
        if (selectionKey != null && selectionKey.isValid()) {
            int ops = selectionKey.interestOps();
            if (enable) {
                selectionKey.interestOps(ops | SelectionKey.OP_WRITE);
            } else {
                selectionKey.interestOps(ops & ~SelectionKey.OP_WRITE);
            }
        }
    }
}