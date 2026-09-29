package Yggdrasil.core;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * HeapFile class provides a simple heap-based storage mechanism for managing records.
 * It allows insertion, deletion, and retrieval of records using a slotted page structure.
 * Each record is identified by a packed 64-bit TupleID that encodes the page number and slot index.
 *
 * <p><b>Insert-path scaling.</b> Earlier versions scanned <i>every</i> data page ever
 * allocated on each insert, looking for one with free space. That made insert cost grow
 * with the total size of the heap (O(n) in page count) \u2014 harmless at a few hundred pages,
 * but exactly the kind of thing that would make an engine sized for terabytes of data grind
 * to a halt long before it got there. Insert now only probes a small, bounded set of
 * recently-allocated pages ({@link #MAX_INSERT_CANDIDATES}) before falling back to allocating
 * a fresh page, so per-insert cost stays O(1) regardless of how large the heap grows.
 *
 * <p>Trade-off, stated plainly: a page that fell out of the candidate window and later had
 * space freed up by a delete won't be reused by future inserts. That's a deliberate trade of
 * some space-reuse efficiency for bounded insert latency at scale. A production system would
 * replace this with a real free-space map; this is the pragmatic middle ground for a
 * hackathon-scale engine that still needs to survive growing past a few thousand pages.
 *
 * <p>The signed page-number ceiling is {@link Metadata#MAX_PAGE_NUMBER}; with 4 KiB
 * physical pages, the Page 0-inclusive address span is 8 TiB. This is an address-space
 * limit, not a guarantee of 8 TiB of user records because metadata, index, WAL, and
 * filesystem overhead consume capacity.
 */
public class HeapFile {

    /** Maximum number of recently-allocated pages probed for free space before allocating a new one. */
    private static final int MAX_INSERT_CANDIDATES = 8;

    private final StorageManager storageManager;
    private final Deque<Integer> recentPages; // most-recently-allocated last; bounded to MAX_INSERT_CANDIDATES
    private boolean seeded = false;

    public HeapFile(StorageManager storageManager) {
        this.storageManager = storageManager;
        this.recentPages = new ArrayDeque<>(MAX_INSERT_CANDIDATES);
    }

    /**
     * Seeds the bounded recent-page candidate window from the persisted directory after a
     * restart. Only the tail (most recently allocated) pages are loaded \u2014 not the full
     * directory \u2014 so this stays cheap even once the heap has grown far past
     * MAX_INSERT_CANDIDATES pages.
     */
    private void seedRecentPages() throws IOException {
        if (seeded) return;
        seeded = true;

        List<Integer> all = storageManager.getMetadata().getDataPageNumbers();
        int start = Math.max(0, all.size() - MAX_INSERT_CANDIDATES);
        for (int i = start; i < all.size(); i++) {
            recentPages.addLast(all.get(i));
        }
    }

    private void trackRecentPage(int pageNum) {
        recentPages.remove((Integer) pageNum); // avoid duplicate/stale entries
        recentPages.addLast(pageNum);
        while (recentPages.size() > MAX_INSERT_CANDIDATES) {
            recentPages.removeFirst();
        }
    }

    /**
     * Inserts a record payload into the first available slotted page (from the bounded
     * recent-page candidate window) that has space, or allocates a brand new page.
     * Returns a packed 64-bit TupleID pointing to the exact page and slot index.
     */
    public synchronized long insertRecord(byte[] recordData) throws IOException {
        seedRecentPages();

        // 1. Probe the bounded set of recently-allocated pages for an open slot cavity.
        for (int pageNum : recentPages) {
            if (!storageManager.pageExists(pageNum)) continue;
            SlottedPage page = (SlottedPage) storageManager.getPage(-1, pageNum);
            try {
                long tid = page.insertRecord(recordData);
                if (tid != -1L) {
                    trackRecentPage(pageNum);
                    return tid; // Success! Return packed TupleID directly
                }
            } finally {
                storageManager.unpinPage(pageNum);
            }
        }

        // 2. Allocate a brand new data page if none of the recent candidates has space.
        SlottedPage newPage = (SlottedPage) storageManager.allocateNewPage(StorageManager.TYPE_SLOTTED_DATA);
        int pageNum = newPage.getPageNumber();
        trackRecentPage(pageNum);

        try {
            long tid = newPage.insertRecord(recordData);
            if (tid == -1L) {
                throw new IOException("Critical System Limit: Record size exceeds maximum allowed single slotted page capacity boundary.");
            }
            return tid;
        } finally {
            storageManager.unpinPage(pageNum);
        }
    }

    /** Replays a WAL insert at its original page and slot. */
    public synchronized long insertRecordAt(long tupleID, byte[] recordData) throws IOException {
        int pageNumber = TupleID.getPageNumber(tupleID);
        int slotIndex = TupleID.getSlotOffset(tupleID);
        SlottedPage page;
        if (!storageManager.pageExists(pageNumber)) {
            page = new SlottedPage(pageNumber, pageNumber);
            page.setDirty(true);
            page.pin();
            storageManager.cachePage(page);
        } else {
            page = (SlottedPage) storageManager.getPage(-1, pageNumber);
        }
        try {
            trackRecentPage(pageNumber);
            byte[] existing = page.getRecord(slotIndex);
            if (existing != null) {
                if (!Arrays.equals(existing, recordData)) {
                    throw new IOException("WAL tuple location contains different record data: " + tupleID);
                }
                return tupleID;
            }

            long restoredID = page.insertRecordAt(slotIndex, recordData);
            if (restoredID != tupleID) {
                throw new IOException("Unable to restore WAL tuple at its original location: " + tupleID);
            }
            return restoredID;
        } finally {
            storageManager.unpinPage(pageNumber);
        }
    }

    /**
     * Logically deletes a record by its 64-bit packed TupleID.
     */
    public synchronized boolean deleteRecord(long tupleID) throws IOException {
        int pageNum = TupleID.getPageNumber(tupleID);
        int slotIndex = TupleID.getSlotOffset(tupleID);

        SlottedPage page = (SlottedPage) storageManager.getPage(-1, pageNum);
        try {
            return page.deleteRecord(slotIndex);
        } finally {
            storageManager.unpinPage(pageNum); // Always unpin cleanly
        }
    }

    /**
     * Resolves a packed long pointer, pins the targeted page, extracts the bytes, and unpins it cleanly.
     */
    public synchronized byte[] selectRecord(long tupleID) throws IOException {
        int pageNum = TupleID.getPageNumber(tupleID);
        int slotIndex = TupleID.getSlotOffset(tupleID);

        SlottedPage page = (SlottedPage) storageManager.getPage(-1, pageNum);
        try {
            return page.getRecord(slotIndex);
        } finally {
            storageManager.unpinPage(pageNum);
        }
    }

    /** Backward-compatible name for resolving a packed TupleID. */
    public synchronized byte[] getRecord(long tupleID) throws IOException {
        return selectRecord(tupleID);
    }

    /** True only when the exact WAL after-image is already present at its logged location. */
    public synchronized boolean hasRecord(long tupleID, byte[] expected) throws IOException {
        int pageNumber = TupleID.getPageNumber(tupleID);
        if (!storageManager.pageExists(pageNumber)) return false;
        try {
            SlottedPage page = (SlottedPage) storageManager.getPage(-1, pageNumber);
            try {
                byte[] actual = page.getRecord(TupleID.getSlotOffset(tupleID));
                return expected == null ? actual != null : Arrays.equals(actual, expected);
            } finally {
                storageManager.unpinPage(pageNumber);
            }
        } catch (PageAuthenticationException e) {
            return false;
        }
    }
}