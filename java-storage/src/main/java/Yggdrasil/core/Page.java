package Yggdrasil.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import Yggdrasil.crypto.Ascon128Engine;

/**
 * Base abstract class for all 4KB page types in the Yggdrasil storage engine and some important functionality.
 * Supports zero-knowledge blind storage framing (ASCON-128 AEAD) and
 * provides common buffer management, pinning, and LSN tracking.
 * 
 * 0B                       16B                                            4080B           4096B
 *   +────────────────────────┬──────────────────────────────────────────────┬───────────────────+
    | Nonce / IV             | Encrypted Ciphertext Payload                 | Auth Tag          |
    | (16 Bytes)             | (4048 Bytes)                                 | (16 Bytes)        |
    +────────────────────────┴──────────────────────────────────────────────┴───────────────────+
    │                                                                                           │
    │◄──────────────────────── Inner Decrypted Payload (4064 Bytes) ───────────────────────────►│
 */
public abstract class Page {

    private static final SecureRandom NONCE_GENERATOR = new SecureRandom();

    // --- Physical Frame Dimensions ---
    public static final int PAGE_SIZE = 4096; // Standard 4KB block size

    // --- Cryptographic Layout Specs ---
    public static final int NONCE_SIZE = 16;  // 16 Bytes Public Nonce
    public static final int TAG_SIZE = 16;    // 16 Bytes AEAD Auth Tag
    public static final int CRYPTO_HEADER_SIZE = NONCE_SIZE + TAG_SIZE; // 32 Bytes overhead

    // --- Inner Decrypted Payload Capacity (4064 Bytes) ---
    public static final int PAYLOAD_SIZE = PAGE_SIZE - CRYPTO_HEADER_SIZE;

    // --- Outer Frame Offset Locations ---
    public static final int OFFSET_NONCE = 0;                          // Bytes 0..15
    public static final int OFFSET_CIPHERTEXT = NONCE_SIZE;            // Bytes 16..4079
    public static final int OFFSET_TAG = PAGE_SIZE - TAG_SIZE;         // Bytes 4080..4095

    // --- Common Inner Payload Header Offsets (Relative to Decrypted Payload) ---
    public static final int OFFSET_PAGE_TYPE = 0;   // 1 Byte
    public static final int OFFSET_KEY_COUNT = 1;   // 2 Bytes (Short)
    public static final int OFFSET_PARENT_ID = 3;   // 4 Bytes (Int)
    public static final int COMMON_HEADER_SIZE = 7; 

    // --- Page Attributes ---
    private final int pageID;
    private final int pageNumber;
    private int pinCount = 0; 
    private long lsn = 0; // WAL sequence tracking
    private boolean isDirty = false; // State flag tracking unsaved in-memory mutations

    public Page(int pageID, int pageNumber) {
        this.pageID = pageID;
        this.pageNumber = pageNumber;
    }

    // --- Buffer Pool Latch / Pin Control ---
    public synchronized void pin() {
        this.pinCount++;
    }

    public synchronized void unpin() {
        if (this.pinCount > 0) {
            this.pinCount--;
        }
    }

    public synchronized int getPinCount() {
        return this.pinCount;
    }

    // --- Dirty Flag Tracking ---
    public synchronized boolean isDirty() {
        return this.isDirty;
    }

    public synchronized void setDirty(boolean dirty) {
        this.isDirty = dirty;
    }

    // --- WAL Sequence Management ---
    public synchronized long getLsn() {
        return this.lsn;
    }

    public synchronized void setLsn(long lsn) {
        this.lsn = lsn;
        this.isDirty = true;
    }

    // --- Address Calculations ---
    public long getPhysicalLocation() {
        return (long) this.pageNumber * PAGE_SIZE;
    }

    public int getPageID() { 
        return this.pageID;
    }

    public int getPageNumber() {
        return this.pageNumber;
    }

    // --- Cryptographic & Serialization Contracts ---

    /**
     * Dumps the inner 4064-byte decrypted page contents into a raw byte array
     * prior to ASCON-128 encryption and disk flush.
     */
    public abstract byte[] getPayloadBytes();

    /**
     * Reconstructs the internal page state from a 4064-byte decrypted payload array.
     */
    public abstract void parsePayloadBytes(byte[] payload) throws IOException;

    /**
     * Triggers page serialization and writes the encrypted page frame to disk.
     */
    public void PageToDisk(RandomAccessFile raf, byte[] secretKey) throws IOException {
        if (!isDirty) {
            return; // Skip I/O if the frame hasn't been modified
        }

        // 1. Serialize the internal page structure into a 4064-byte payload
        byte[] payload = getPayloadBytes();

        // 2. Encrypt payload and flush to disk
        writeToDisk(raf, payload, secretKey);

        this.isDirty = false;
    }

    /**
     * Encrypts raw payload using ASCON-128 AEAD with a SecureRandom nonce and writes frame to disk.
     */
    public void writeToDisk(RandomAccessFile raf, byte[] rawPayload, byte[] secretKey) throws IOException {
        
        if (rawPayload == null || rawPayload.length != PAYLOAD_SIZE) 
        {
            throw new IllegalArgumentException("Payload size must be exactly " + PAYLOAD_SIZE + " bytes.");
        }

        byte[] nonce = new byte[NONCE_SIZE];
        NONCE_GENERATOR.nextBytes(nonce);

        byte[] encResult = Ascon128Engine.encryptPayload(secretKey, nonce, associatedData(pageID), rawPayload);
        byte[] diskFrame = new byte[PAGE_SIZE];

        System.arraycopy(nonce, 0, diskFrame, OFFSET_NONCE, NONCE_SIZE);
        System.arraycopy(encResult, 0, diskFrame, OFFSET_CIPHERTEXT, encResult.length);

        FileChannel channel = raf.getChannel();
        ByteBuffer frame = ByteBuffer.wrap(diskFrame);
        
        long position = getPhysicalLocation();

        while (frame.hasRemaining()) 
        {
            position += channel.write(frame, position);
        }

        Yggdrasil.gui.EngineTelemetry.recordDiskWrite(PAGE_SIZE);
        Yggdrasil.gui.EngineTelemetry.firePageFlush(getPageNumber(), diskFrame);
    }

    public static byte[] associatedData(int pageID) 
    {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
        buffer.putInt(pageID);
        return buffer.array();
    }
}
