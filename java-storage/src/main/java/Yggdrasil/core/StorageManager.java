package Yggdrasil.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import Yggdrasil.gui.EngineTelemetry;

/**
 * StorageManager handles page allocation, memory pin/unpin caching,
 * page LRU eviction, and encrypted disk persistence.
 */
public class StorageManager {

    private static final Logger LOGGER = Logger.getLogger(StorageManager.class.getName());

    public static final String DATA = "btree_data";
    public static final String DATABASE = "storage.db";
    private static final int MAX_CACHE_SIZE = 1000;

    // Structural Page Types
    public static final byte TYPE_INTERNAL = 0x01;
    public static final byte TYPE_LEAF = 0x02;
    public static final byte TYPE_SLOTTED_DATA = 0x03;

    private final byte[] secretKey;
    private final File dbFile;
    private final Metadata metadata;
    
    private RandomAccessFile raf;
    private WalManager walManager;

    // Active pinned pages preventing eviction
    private final Map<Integer, Page> pinnedPages = new HashMap<>();

    // LRU Cache holding unpinned pages in memory
    private final Map<Integer, Page> pageCache = new LinkedHashMap<Integer, Page>(MAX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Page> eldest) {
            if (size() > MAX_CACHE_SIZE) {
                Page page = eldest.getValue();
                // Only evict if page is completely unpinned
                if (page != null && page.getPinCount() == 0) {
                    try {
                        if (page.isDirty() && raf != null) {
                            page.PageToDisk(raf, secretKey);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to flush evicted dirty page " + page.getPageNumber(), e);
                    }
                    return true;
                }
            }
            return false;
        }
    };

    public StorageManager(byte[] masterKey, String dataDir) {
        if (masterKey == null || masterKey.length != 16) {
            throw new IllegalArgumentException("Ascon-128 key must be exactly 16 bytes.");
        }
        this.secretKey = masterKey.clone();
        
        File dir = new File(dataDir != null ? dataDir : DATA);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        this.dbFile = new File(dir, DATABASE);
        this.metadata = new Metadata();
    }

    public StorageManager(byte[] masterKey) {
        this(masterKey, DATA);
    }

    public StorageManager() {
        this(new byte[16], DATA);
    }

    /**
     * Initializes physical disk structures and reads Page 0 metadata.
     */
    public void boot() throws IOException {
        LOGGER.fine("Booting Storage Engine");
        this.raf = new RandomAccessFile(dbFile, "rw");
        metadata.pageZero(raf, secretKey);
        LOGGER.fine("Storage Engine booted successfully");
    }

    public boolean isNewDatabase() throws IOException {
        return raf.length() <= Page.PAGE_SIZE;
    }

    /**
     * Allocates a new structural page on disk and pins it in memory.
     */
    public synchronized Page allocateNewPage(byte type) throws IOException {
        int pageNumber = metadata.nextAvailablePage(this.raf, secretKey);
        int pageID = pageNumber;

        Page page;
        if (type == TYPE_INTERNAL) {
            page = new InternalNode(pageID, pageNumber);
        } else if (type == TYPE_LEAF) {
            page = new LeafNode(pageID, pageNumber);
        } else if (type == TYPE_SLOTTED_DATA) {
            page = new SlottedPage(pageID, pageNumber);
        } else {
            throw new IllegalArgumentException("Unknown structural page type flag specified: " + type);
        }

        page.setDirty(true);
        if (type == TYPE_SLOTTED_DATA) {
            metadata.addDataPageNumber(this.raf, secretKey, pageNumber);
        }

        page.pin();
        pinnedPages.put(pageNumber, page);
        pageCache.put(pageNumber, page);

        return page;
    }

    /**
     * Retrieves a page from the active pin set, LRU cache, or reads/decrypts it from disk.
     */
    public synchronized Page getPage(int pageID, int pageNumber) throws IOException {
        int effectivePageID = (pageID == -1) ? pageNumber : pageID;

        // 1. Check pinned pages
        if (pinnedPages.containsKey(pageNumber)) {
            Page page = pinnedPages.get(pageNumber);
            page.pin();
            EngineTelemetry.recordCacheHit();
            return page;
        }

        // 2. Check LRU cache
        if (pageCache.containsKey(pageNumber)) {
            Page page = pageCache.get(pageNumber);
            if (page != null) {
                page.pin();
                pinnedPages.put(pageNumber, page);
                EngineTelemetry.recordCacheHit();
                return page;
            }
        }

        // 3. Disk Read & Decrypt
        EngineTelemetry.recordCacheMiss();
        long offset = (long) pageNumber * Page.PAGE_SIZE;
        if (offset >= raf.length()) {
            throw new IOException("Fetch error: Requested disk block address out of bounds: " + pageNumber);
        }

        raf.seek(offset);
        byte[] diskBuffer = new byte[Page.PAGE_SIZE];
        raf.readFully(diskBuffer);
        EngineTelemetry.recordDiskRead(Page.PAGE_SIZE);

        byte[] payloadBuffer = PageFactory.extractPayload(diskBuffer, this.secretKey, effectivePageID);
        byte typeIndicator = payloadBuffer[Page.OFFSET_PAGE_TYPE];

        Page loadedPage;
        if (typeIndicator == TYPE_INTERNAL || typeIndicator == TYPE_LEAF) {
            loadedPage = PageFactory.deserialize(effectivePageID, pageNumber, payloadBuffer);
        } else if (typeIndicator == TYPE_SLOTTED_DATA) {
            loadedPage = SlottedPage.deserialize(effectivePageID, pageNumber, payloadBuffer);
        } else {
            throw new IOException("Corrupted block state: Unknown payload header validation type: " + typeIndicator);
        }

        loadedPage.pin();
        pinnedPages.put(pageNumber, loadedPage);
        pageCache.put(pageNumber, loadedPage);

        return loadedPage;
    }

    /**
     * Decrements a page pin count. Unpinned pages remain cached safely in memory.
     */
    public synchronized void unpinPage(int pageNumber) {
        Page page = pinnedPages.get(pageNumber);
        if (page != null) {
            page.unpin();
            if (page.getPinCount() == 0) {
                pinnedPages.remove(pageNumber);
            }
        }
    }

    public synchronized void evictPageFromCache(int pageNumber) {
        pinnedPages.remove(pageNumber);
        pageCache.remove(pageNumber);
    }

    synchronized void cachePage(Page page) {
        pinnedPages.put(page.getPageNumber(), page);
        pageCache.put(page.getPageNumber(), page);
    }

    /**
     * Calculates total physical page allocation count (used during WAL recovery).
     */
    public synchronized int getTotalPages() throws IOException {
        if (raf == null) return 0;
        return (int) (raf.length() / Page.PAGE_SIZE);
    }

    public synchronized boolean pageExists(int pageNumber) throws IOException {
        return pageNumber > 0 && (long) pageNumber * Page.PAGE_SIZE < raf.length();
    }

    public synchronized void setRootPageID(int rootPageID) throws IOException {
        metadata.setRootPageID(raf, secretKey, rootPageID);
    }

    /**
     * Applies an unencrypted payload image update during crash recovery replays.
     */
    public synchronized void applyPageUpdate(int pageNumber, byte[] payload) throws IOException {
        if (payload == null || payload.length != Page.PAYLOAD_SIZE) {
            throw new IOException("Invalid WAL page after-image.");
        }

        byte typeIndicator = payload[Page.OFFSET_PAGE_TYPE];
        Page page;

        if (typeIndicator == TYPE_INTERNAL || typeIndicator == TYPE_LEAF) {
            page = PageFactory.deserialize(-1, pageNumber, payload);
        } else if (typeIndicator == TYPE_SLOTTED_DATA) {
            page = SlottedPage.deserialize(-1, pageNumber, payload);
        } else {
            throw new IOException("Corrupted WAL after-image: Unknown type " + typeIndicator);
        }

        page.setDirty(true);
        page.pin();
        pinnedPages.put(pageNumber, page);
        pageCache.put(pageNumber, page);
        unpinPage(pageNumber);
    }

    /**
     * Forces dirty page flushing to physical storage without generating WAL records.
     */
    public synchronized void flushAllDirtyPagesNoWal() throws IOException {
        for (Map.Entry<Integer, Page> entry : new ArrayList<>(pageCache.entrySet())) {
            Page page = entry.getValue();
            if (page != null && page.isDirty()) {
                page.PageToDisk(this.raf, this.secretKey);
            }
        }

        for (Map.Entry<Integer, Page> entry : new ArrayList<>(pinnedPages.entrySet())) {
            Page page = entry.getValue();
            if (page != null && page.isDirty()) {
                page.PageToDisk(this.raf, this.secretKey);
            }
        }

        if (raf != null) {
            raf.getFD().sync();
        }
    }

    /**
     * Performs physical storage size alignment and page header validation checks.
     */
    public synchronized void auditIntegrity() throws IOException {
        if (raf == null) {
            throw new IOException("Storage engine is not booted.");
        }

        long fileLength = raf.length();
        if (fileLength % Page.PAGE_SIZE != 0) {
            throw new IOException("Storage file size is unaligned to Page.PAGE_SIZE (" + Page.PAGE_SIZE + " bytes).");
        }

        int totalPages = (int) (fileLength / Page.PAGE_SIZE);
        if (totalPages == 0) return;

        metadata.pageZero(raf, secretKey);

        byte[] diskBuffer = new byte[Page.PAGE_SIZE];
        for (int i = 1; i < totalPages; i++) {
            raf.seek((long) i * Page.PAGE_SIZE);
            raf.readFully(diskBuffer);

            byte[] payloadBuffer = PageFactory.extractPayload(diskBuffer, secretKey, i);
            byte typeIndicator = payloadBuffer[Page.OFFSET_PAGE_TYPE];

            if (typeIndicator != TYPE_INTERNAL && typeIndicator != TYPE_LEAF && typeIndicator != TYPE_SLOTTED_DATA) {
                throw new IOException("Corrupted page header: Unknown type flag " + typeIndicator + " on Page " + i);
            }
        }
    }

    /**
     * Gracefully flushes dirty buffers to disk and closes file descriptors.
     */
    public synchronized void close() throws IOException {
        if (!pinnedPages.isEmpty()) {
            System.err.println("Warning: Shutting down with active page pins leaking: " + pinnedPages.keySet());
            for (Page page : pinnedPages.values()) {
                if (page.isDirty()) {
                    page.PageToDisk(this.raf, this.secretKey);
                }
            }
            pinnedPages.clear();
        }

        for (Page page : pageCache.values()) {
            if (page != null && page.isDirty()) {
                page.PageToDisk(this.raf, this.secretKey);
            }
        }
        pageCache.clear();

        if (raf != null) {
            raf.getFD().sync();
            raf.close();
            LOGGER.fine("Database system turned off safely");
        }
    }

    // --- WAL & Management Accessors ---
    public Metadata getMetadata() { return metadata; }
    public RandomAccessFile getRaf() { return raf; }
    public byte[] getSecretKey() { return secretKey.clone(); }

    public synchronized void setWalManager(WalManager walManager) { 
        this.walManager = walManager; 
    }

    synchronized WalManager suspendWal() {
        WalManager previous = walManager;
        walManager = null;
        return previous;
    }

    synchronized void restoreWal(WalManager walManager) { 
        this.walManager = walManager; 
    }
}