package Yggdrasil.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import Yggdrasil.gui.EngineTelemetry;
import Yggdrasil.crypto.Ascon128Engine;

/**
 * Manages physical Page 0, which holds system-level metadata, allocations,
 * and encrypted storage configuration state.
 *
 * <p><b>Data-page directory scaling.</b> Earlier versions stored every data-page
 * number inline inside Page 0's single 4064-byte encrypted payload, which hard-capped
 * the engine at ~1,012 data pages (~4 MB of usable storage) regardless of the
 * positive signed 32-bit page-number address space (2^31 - 1 page numbers; the
 * complete address span including Page 0 is 2^43 bytes = 8 TiB).
 *
 * <p>Page 0 now stores only a small inline directory plus a pointer to an
 * <b>overflow chain</b>: additional encrypted, authenticated pages (type
 * {@link #OVERFLOW_MAGIC}) that each hold ~1,013 more page numbers and a "next"
 * pointer. When the inline directory fills up, a new overflow page is allocated
 * and linked onto the tail of the chain instead of throwing. This removes the
    * hard cap entirely, so the data-page directory can grow to the positive signed
    * 32-bit page-number limit.
 *
 * <p><b>Honest limitation:</b> directory reconstruction on {@link #pageZero} still
 * walks the full overflow chain once at boot, so boot time and in-memory directory
 * size both scale linearly with total data-page count. That's an acceptable trade
 * for a hackathon-scale engine; a production system would replace this with a
 * proper free-space bitmap instead of a page-number list. See {@link HeapFile} for
 * the companion change that stops the per-insert cost from also scaling with the
 * total number of data pages.
 */
public class Metadata {

    /** Highest page number representable by the signed page-number fields. */
    public static final int MAX_PAGE_NUMBER = Integer.MAX_VALUE;

    private static final int MAGIC_NUMBER = 0x4153434F;
    private static final int OVERFLOW_MAGIC = 0x4153434F ^ 0x4F564C46; // "ASCO" ^ "OVLF"

    // --- Page 0 layout ---
    private static final int OFFSET_MAGIC = 0;
    private static final int OFFSET_AVAILABLE = 4;
    private static final int OFFSET_ROOT = 8;
    private static final int OFFSET_DATA_COUNT = 12;      // TOTAL entries (inline + overflow)
    private static final int OFFSET_OVERFLOW_HEAD = 16;   // -1 if no overflow chain yet
    private static final int OFFSET_DATA_PAGES = 20;       // inline entries start here

    // --- Overflow page layout (separate encrypted pages, chained via NEXT) ---
    private static final int OVF_OFFSET_MAGIC = 0;
    private static final int OVF_OFFSET_NEXT = 4;   // -1 if this is the tail
    private static final int OVF_OFFSET_COUNT = 8;  // entries stored in *this* overflow page
    private static final int OVF_OFFSET_ENTRIES = 12;

    private static final int INLINE_CAPACITY = (Page.PAYLOAD_SIZE - OFFSET_DATA_PAGES) / 4;      // 1,011
    private static final int OVERFLOW_CAPACITY = (Page.PAYLOAD_SIZE - OVF_OFFSET_ENTRIES) / 4;    // 1,013

    private static final SecureRandom RANDOM = new SecureRandom();

    private int availablePage = 1;
    private int rootPageID = -1;
    private int overflowHead = -1;
    private int overflowTail = -1; // in-memory only; recomputed by walking the chain if unknown
    private final List<Integer> dataPageNumbers = new ArrayList<>();

    public synchronized void pageZero(RandomAccessFile file) throws IOException {
        pageZero(file, new byte[16]);
    }

    public synchronized void pageZero(RandomAccessFile file, byte[] secretKey) throws IOException {
        if (file.length() == 0) {
            write(file, secretKey);
        } else {
            if (file.length() < Page.PAGE_SIZE) {
                throw new IOException("Truncated encrypted metadata page.");
            }
            read(file, secretKey);
        }
    }

    public synchronized int nextAvailablePage(RandomAccessFile file, byte[] key) throws IOException {
        int assignedPage = allocatePageNumber();
        write(file, key);
        return assignedPage;
    }

    public synchronized int getRootPageID() {
        return rootPageID;
    }

    public synchronized void setRootPageID(RandomAccessFile file, byte[] key, int root) throws IOException {
        this.rootPageID = root;
        write(file, key);
    }

    public synchronized int getAvailablePage() {
        return availablePage;
    }

    /**
     * Registers a newly-allocated data page in the directory. No longer throws once
     * the inline directory (~1,011 entries) is full \u2014 instead it transparently spills
     * into the overflow chain, so growth is bounded only by the 32-bit page-number
     * address space (~8.7 TB at 4 KB/page), not by Page 0's single-frame capacity.
     */
    public synchronized void addDataPageNumber(RandomAccessFile file, byte[] key, int page) throws IOException {
        if (dataPageNumbers.contains(page)) {
            return;
        }

        if (dataPageNumbers.size() < INLINE_CAPACITY) {
            dataPageNumbers.add(page);
            write(file, key);
            return;
        }

        appendToOverflow(file, key, page);
        dataPageNumbers.add(page);
        write(file, key); // always persist Page 0's updated total count (+ availablePage/overflowHead if changed)
    }

    public synchronized List<Integer> getDataPageNumbers() {
        return new ArrayList<>(dataPageNumbers);
    }

    // ------------------------------------------------------------------
    // Overflow chain management
    // ------------------------------------------------------------------

    private void appendToOverflow(RandomAccessFile file, byte[] key, int page) throws IOException {
        FileChannel channel = file.getChannel();

        if (overflowHead == -1) {
            // First overflow page ever needed.
            int newPageNum = allocatePageNumber();
            writeOverflowPage(channel, key, newPageNum, -1, new int[]{page});
            overflowHead = newPageNum;
            overflowTail = newPageNum;
            return;
        }

        if (overflowTail == -1) {
            overflowTail = findTail(channel, key);
        }

        int[] tailEntries = readOverflowEntries(channel, key, overflowTail);
        int tailNext = readOverflowNext(channel, key, overflowTail);

        if (tailEntries.length < OVERFLOW_CAPACITY) {
            int[] grown = new int[tailEntries.length + 1];
            System.arraycopy(tailEntries, 0, grown, 0, tailEntries.length);
            grown[tailEntries.length] = page;
            writeOverflowPage(channel, key, overflowTail, tailNext, grown);
            return;
        }

        // Tail is full: allocate a new overflow page and link it on.
        int newPageNum = allocatePageNumber();
        writeOverflowPage(channel, key, newPageNum, -1, new int[]{page});
        writeOverflowPage(channel, key, overflowTail, newPageNum, tailEntries); // relink old tail's NEXT
        overflowTail = newPageNum;
    }

    private int allocatePageNumber() throws IOException {
        if (availablePage == 0 || availablePage > MAX_PAGE_NUMBER) {
            throw new IOException("Storage page-number limit reached: " + MAX_PAGE_NUMBER + ".");
        }
        int assignedPage = availablePage;
        availablePage = assignedPage == MAX_PAGE_NUMBER ? 0 : assignedPage + 1;
        return assignedPage;
    }

    private int findTail(FileChannel channel, byte[] key) throws IOException {
        int current = overflowHead;
        int next = readOverflowNext(channel, key, current);
        while (next != -1) {
            current = next;
            next = readOverflowNext(channel, key, current);
        }
        return current;
    }

    /** Walks the overflow chain from {@code overflowHead}, appending every entry in order. */
    private void loadOverflowChain(FileChannel channel, byte[] key) throws IOException {
        int current = overflowHead;
        int guard = 0;
        while (current != -1) {
            if (++guard > 50_000_000) {
                throw new IOException("Metadata overflow chain appears cyclic or corrupt.");
            }
            int[] entries = readOverflowEntries(channel, key, current);
            for (int e : entries) {
                dataPageNumbers.add(e);
            }
            int next = readOverflowNext(channel, key, current);
            if (next == -1) {
                overflowTail = current;
            }
            current = next;
        }
    }

    private int readOverflowNext(FileChannel channel, byte[] key, int pageNumber) throws IOException {
        byte[] payload = readEncryptedPage(channel, key, pageNumber);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        if (buffer.getInt(OVF_OFFSET_MAGIC) != OVERFLOW_MAGIC) {
            throw new IOException("Corrupt metadata overflow page " + pageNumber + ": bad magic.");
        }
        return buffer.getInt(OVF_OFFSET_NEXT);
    }

    private int[] readOverflowEntries(FileChannel channel, byte[] key, int pageNumber) throws IOException {
        byte[] payload = readEncryptedPage(channel, key, pageNumber);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        if (buffer.getInt(OVF_OFFSET_MAGIC) != OVERFLOW_MAGIC) {
            throw new IOException("Corrupt metadata overflow page " + pageNumber + ": bad magic.");
        }
        int count = buffer.getInt(OVF_OFFSET_COUNT);
        if (count < 0 || count > OVERFLOW_CAPACITY) {
            throw new IOException("Corrupt metadata overflow page " + pageNumber + ": bad entry count.");
        }
        int[] entries = new int[count];
        for (int i = 0; i < count; i++) {
            entries[i] = buffer.getInt(OVF_OFFSET_ENTRIES + (i * 4));
        }
        return entries;
    }

    private void writeOverflowPage(FileChannel channel, byte[] key, int pageNumber, int next, int[] entries) throws IOException {
        if (entries.length > OVERFLOW_CAPACITY) {
            throw new IOException("Overflow page entry count exceeds page capacity.");
        }
        byte[] payload = new byte[Page.PAYLOAD_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.putInt(OVF_OFFSET_MAGIC, OVERFLOW_MAGIC);
        buffer.putInt(OVF_OFFSET_NEXT, next);
        buffer.putInt(OVF_OFFSET_COUNT, entries.length);
        for (int i = 0; i < entries.length; i++) {
            buffer.putInt(OVF_OFFSET_ENTRIES + (i * 4), entries[i]);
        }
        writeEncryptedPage(channel, key, pageNumber, payload);
    }

    // ------------------------------------------------------------------
    // Generic encrypted-page I/O (shared by Page 0 and overflow pages)
    // ------------------------------------------------------------------

    private void writeEncryptedPage(FileChannel channel, byte[] key, int pageNumber, byte[] payload) throws IOException {
        validateKey(key);

        byte[] nonce = new byte[Page.NONCE_SIZE];
        RANDOM.nextBytes(nonce);

        byte[] encryptedPayload = Ascon128Engine.encryptPayload(key, nonce, Page.associatedData(pageNumber), payload);
        byte[] diskFrame = new byte[Page.PAGE_SIZE];

        System.arraycopy(nonce, 0, diskFrame, 0, nonce.length);
        System.arraycopy(encryptedPayload, 0, diskFrame, Page.OFFSET_CIPHERTEXT, encryptedPayload.length);

        long offset = (long) pageNumber * Page.PAGE_SIZE;
        writeFullyAt(channel, ByteBuffer.wrap(diskFrame), offset);
        channel.force(true);

        EngineTelemetry.recordDiskWrite(Page.PAGE_SIZE);
        EngineTelemetry.firePageFlush(pageNumber, diskFrame);
    }

    private byte[] readEncryptedPage(FileChannel channel, byte[] key, int pageNumber) throws IOException {
        validateKey(key);

        byte[] diskFrame = new byte[Page.PAGE_SIZE];
        long offset = (long) pageNumber * Page.PAGE_SIZE;
        readFullyAt(channel, ByteBuffer.wrap(diskFrame), offset);

        EngineTelemetry.recordDiskRead(Page.PAGE_SIZE);

        byte[] nonce = new byte[Page.NONCE_SIZE];
        System.arraycopy(diskFrame, 0, nonce, 0, nonce.length);

        int cipherLength = Page.PAGE_SIZE - Page.NONCE_SIZE;
        byte[] ciphertext = new byte[cipherLength];
        System.arraycopy(diskFrame, Page.OFFSET_CIPHERTEXT, ciphertext, 0, cipherLength);

        byte[] decryptedPayload = Ascon128Engine.decryptPayload(key, nonce, Page.associatedData(pageNumber), ciphertext);
        if (decryptedPayload == null) {
            EngineTelemetry.fireAuthFailure(pageNumber, "Metadata page authentication failed.");
            throw new PageAuthenticationException("Metadata page " + pageNumber + " authentication failed.");
        }

        EngineTelemetry.firePageFetch(pageNumber, decryptedPayload);
        return decryptedPayload;
    }

    // ------------------------------------------------------------------
    // Page 0 read/write
    // ------------------------------------------------------------------

    private void write(RandomAccessFile file, byte[] key) throws IOException {
        byte[] payload = new byte[Page.PAYLOAD_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(payload);

        buffer.putInt(OFFSET_MAGIC, MAGIC_NUMBER);
        buffer.putInt(OFFSET_AVAILABLE, availablePage);
        buffer.putInt(OFFSET_ROOT, rootPageID);
        buffer.putInt(OFFSET_DATA_COUNT, dataPageNumbers.size());
        buffer.putInt(OFFSET_OVERFLOW_HEAD, overflowHead);

        int inlineCount = Math.min(dataPageNumbers.size(), INLINE_CAPACITY);
        for (int i = 0; i < inlineCount; i++) {
            buffer.putInt(OFFSET_DATA_PAGES + (i * 4), dataPageNumbers.get(i));
        }

        writeEncryptedPage(file.getChannel(), key, 0, payload);
    }

    private void read(RandomAccessFile file, byte[] key) throws IOException {
        FileChannel channel = file.getChannel();
        byte[] decryptedPayload = readEncryptedPage(channel, key, 0);

        ByteBuffer buffer = ByteBuffer.wrap(decryptedPayload);
        if (buffer.getInt(OFFSET_MAGIC) != MAGIC_NUMBER) {
            throw new IOException("Invalid storage format.");
        }

        availablePage = buffer.getInt(OFFSET_AVAILABLE);
        rootPageID = buffer.getInt(OFFSET_ROOT);

        int totalCount = buffer.getInt(OFFSET_DATA_COUNT);
        overflowHead = buffer.getInt(OFFSET_OVERFLOW_HEAD);
        overflowTail = -1;

        if (availablePage < 0 || totalCount < 0) {
            throw new IOException("Corrupt metadata directory.");
        }

        dataPageNumbers.clear();
        int inlineCount = Math.min(totalCount, INLINE_CAPACITY);
        for (int i = 0; i < inlineCount; i++) {
            dataPageNumbers.add(buffer.getInt(OFFSET_DATA_PAGES + (i * 4)));
        }

        if (totalCount > INLINE_CAPACITY) {
            if (overflowHead == -1) {
                throw new IOException("Corrupt metadata directory: overflow entries expected but no overflow chain present.");
            }
            loadOverflowChain(channel, key);
        }

        if (dataPageNumbers.size() != totalCount) {
            throw new IOException("Corrupt metadata directory: expected " + totalCount
                    + " data pages but reconstructed " + dataPageNumbers.size() + ".");
        }
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length != 16) {
            throw new IllegalArgumentException("Invalid metadata key.");
        }
    }

    private static void writeFullyAt(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long currentPos = position;
        while (buffer.hasRemaining()) {
            currentPos += channel.write(buffer, currentPos);
        }
    }

    private static void readFullyAt(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long currentPos = position;
        while (buffer.hasRemaining()) {
            int bytesRead = channel.read(buffer, currentPos);
            if (bytesRead < 0) {
                throw new IOException("Unexpected end of metadata page.");
            }
            currentPos += bytesRead;
        }
    }
}