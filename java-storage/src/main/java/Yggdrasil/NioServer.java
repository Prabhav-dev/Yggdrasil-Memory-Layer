package Yggdrasil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NioServer implements a non-blocking Reactor pattern for the Yggdrasil engine.
 * Decouples socket I/O events (Accept/Read/Write) from storage engine query processing
 * using an offloaded bounded worker thread pool executor.
 */
public class NioServer {

    private final int port;
    private final String bindHost;
    private final RequestDispatcher dispatcher;
    private final ThreadPoolExecutor queryWorkerPool;
    private final int maxConnections;
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private volatile boolean running = false;

    private volatile int boundPort = -1;

    public NioServer(int port, RequestDispatcher dispatcher, int workerThreads) {
        this("0.0.0.0", port, dispatcher, workerThreads, 1000, 10000);
    }

    public NioServer(int port, RequestDispatcher dispatcher, int workerThreads, int queueCapacity, int maxConnections) {
        this("0.0.0.0", port, dispatcher, workerThreads, queueCapacity, maxConnections);
    }

    public NioServer(String bindHost, int port, RequestDispatcher dispatcher, int workerThreads) {
        this(bindHost, port, dispatcher, workerThreads, 1000, 10000);
    }

    public NioServer(String bindHost, int port, RequestDispatcher dispatcher, int workerThreads, int queueCapacity, int maxConnections) {
        this.bindHost = bindHost;
        this.port = port;
        this.dispatcher = dispatcher;
        this.maxConnections = maxConnections;
        this.queryWorkerPool = new ThreadPoolExecutor(
                workerThreads, workerThreads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(bindHost, port));
        boundPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;
        System.out.println("--- Yggdrasil NIO Engine Server Listening on Port " + boundPort + " ---");

        while (running) {
            try {
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();

                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
            } catch (ClosedSelectorException e) {
                break;
            } catch (IOException e) {
                System.err.println("Selector loop exception: " + e.getMessage());
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = server.accept();
        if (clientChannel != null) {
            if (activeConnections.get() >= maxConnections) {
                // Reject connection when max connections limit reached
                clientChannel.close();
                return;
            }
            clientChannel.configureBlocking(false);
            SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
            ConnectionContext context = new ConnectionContext(clientChannel, clientKey);
            clientKey.attach(context);
            activeConnections.incrementAndGet();
        }
    }

    private void handleRead(SelectionKey key) {
        ConnectionContext context = (ConnectionContext) key.attachment();
        SocketChannel channel = context.getChannel();
        FrameAccumulator accumulator = context.getAccumulator();

        try {
            accumulator.prepareForRead();
            ByteBuffer readBuf = accumulator.getReadBuffer();
            int bytesRead = channel.read(readBuf);

            if (bytesRead == -1) {
                closeClient(key);
                return;
            }

            if (bytesRead > 0) {
                Queue<WireProtocol.Frame> frames = accumulator.processReadBuffer();
                for (WireProtocol.Frame frame : frames) {
                    try {
                        // Offload storage engine query execution to backend worker pool
                        queryWorkerPool.submit(() -> {
                            ByteBuffer responseBuf = dispatcher.dispatch(frame);
                            synchronized (context) {
                                accumulator.enqueueResponse(responseBuf);
                                context.setWriteInterest(true);
                            }
                            selector.wakeup(); // Wake selector to handle OP_WRITE
                        });
                    } catch (RejectedExecutionException e) {
                        // Return STATUS_SERVER_BUSY when worker queue is full
                        ByteBuffer busyResp = WireProtocol.encodeErrorResponse(
                                frame.getRequestId(),
                                WireProtocol.STATUS_SERVER_BUSY,
                                "Server busy: Worker queue capacity reached"
                        );
                        synchronized (context) {
                            accumulator.enqueueResponse(busyResp);
                            context.setWriteInterest(true);
                        }
                        selector.wakeup();
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Client read error: " + e.getMessage());
            closeClient(key);
        }
    }

    private void handleWrite(SelectionKey key) {
        ConnectionContext context = (ConnectionContext) key.attachment();
        SocketChannel channel = context.getChannel();
        FrameAccumulator accumulator = context.getAccumulator();

        try {
            synchronized (context) {
                boolean allFlushed = accumulator.writePendingResponses(channel);
                if (allFlushed) {
                    context.setWriteInterest(false);
                }
            }
        } catch (IOException e) {
            System.err.println("Client write error: " + e.getMessage());
            closeClient(key);
        }
    }

    private void closeClient(SelectionKey key) {
        try {
            key.cancel();
            key.channel().close();
        } catch (IOException ignored) {
        } finally {
            activeConnections.decrementAndGet();
        }
    }

    public synchronized void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
            try {
                selector.close();
            } catch (IOException ignored) {}
        }
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {}
        }
        queryWorkerPool.shutdown();
        System.out.println("Yggdrasil NIO Engine Server stopped.");
    }

    public int getBoundPort() {
        return boundPort;
    }

    public int getActiveConnections() {
        return Math.max(0, activeConnections.get());
    }

    public int getQueueSize() {
        return queryWorkerPool.getQueue().size();
    }
}