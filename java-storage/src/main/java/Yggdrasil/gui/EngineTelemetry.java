package Yggdrasil.gui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe global counter and event bus registry for tracking engine operational statistics.
 */
public class EngineTelemetry {

    private static final List<EngineObserver> observerList = new CopyOnWriteArrayList<>();

    private static final AtomicLong cacheHits = new AtomicLong(0);
    private static final AtomicLong cacheMisses = new AtomicLong(0);
    private static final AtomicLong diskBytesRead = new AtomicLong(0);
    private static final AtomicLong diskBytesWritten = new AtomicLong(0);
    private static final AtomicLong ioOperations = new AtomicLong(0);
    private static final AtomicLong pageFlushCount = new AtomicLong(0);

    public static void registerObserver(EngineObserver observer) {
        if (observer != null && !observerList.contains(observer)) {
            observerList.add(observer);
        }
    }

    public static void unregisterObserver(EngineObserver observer) {
        observerList.remove(observer);
    }

    public static void firePageFlush(int pageNumber, byte[] encryptedFrame) {
        pageFlushCount.incrementAndGet();
        for (EngineObserver listener : observerList) {
            try {
                listener.onPageFlush(pageNumber, encryptedFrame);
            } catch (Exception ex) {
                System.err.println("Error invoking onPageFlush: " + ex.getMessage());
            }
        }
    }

    public static void firePageFetch(int pageNumber, byte[] decryptedPayload) {
        for (EngineObserver listener : observerList) {
            try {
                listener.onPageFetch(pageNumber, decryptedPayload);
            } catch (Exception ex) {
                System.err.println("Error invoking onPageFetch: " + ex.getMessage());
            }
        }
    }

    public static void fireAuthFailure(int pageNumber, String errorMessage) {
        for (EngineObserver listener : observerList) {
            try {
                listener.onAuthFailure(pageNumber, errorMessage);
            } catch (Exception ex) {
                System.err.println("Error invoking onAuthFailure: " + ex.getMessage());
            }
        }
    }

    public static void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    public static void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    public static void recordDiskRead(int bytes) {
        diskBytesRead.addAndGet(bytes);
        ioOperations.incrementAndGet();
    }

    public static void recordDiskWrite(int bytes) {
        diskBytesWritten.addAndGet(bytes);
        ioOperations.incrementAndGet();
    }

    public static long getCacheHits() { return cacheHits.get(); }
    public static long getCacheMisses() { return cacheMisses.get(); }
    public static long getDiskBytesRead() { return diskBytesRead.get(); }
    public static long getDiskBytesWritten() { return diskBytesWritten.get(); }
    public static long getBytesWritten() { return diskBytesWritten.get(); }
    public static long getIoOperations() { return ioOperations.get(); }
    public static long getPageFlushCount() { return pageFlushCount.get(); }

    public static double getCacheHitRatio() {
        long hits = cacheHits.get();
        long total = hits + cacheMisses.get();
        return total == 0 ? 1.0 : (double) hits / total;
    }

    public static void resetStats() {
        cacheHits.set(0);
        cacheMisses.set(0);
        diskBytesRead.set(0);
        diskBytesWritten.set(0);
        ioOperations.set(0);
        pageFlushCount.set(0);
    }
}