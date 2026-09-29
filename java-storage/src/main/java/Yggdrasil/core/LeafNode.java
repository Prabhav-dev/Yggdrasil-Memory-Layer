package Yggdrasil.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * LeafNode class represents a leaf node in a B+ tree.
 * It stores keys and their corresponding values (TupleIDs).
 * The class provides methods for insertion, splitting, and serialization to disk.
 */
public class LeafNode extends BTreeNode {

    private static final Logger LOGGER = Logger.getLogger(LeafNode.class.getName());
    
    private int nextLeafPageID;
    private final int[] keys;
    private final long[] values; 
    
    // Recalculated for long primitives: 11 + (337 * 4) + (337 * 8) = 4055 bytes (Fits inside 4064B payload)
    public static final int MAX_KEYS = 337; 
    private static final int OFFSET_NEXT_LEAF = 7;
    private static final int OFFSET_KEYS = 11;
    private static final int OFFSET_VALUES = 11 + (MAX_KEYS * 4); // 1359

    public LeafNode(int pageID, int pageNumber) {
        super(pageID, pageNumber, StorageManager.TYPE_LEAF);
        this.nextLeafPageID = -1;
        this.keys = new int[MAX_KEYS];
        this.values = new long[MAX_KEYS];
    }

    public boolean insert(int key, long tid) {
        short count = getKeyCount();
        if (count >= MAX_KEYS) {
            return false;
        }

        for (int i = 0; i < count; i++) {
            if (keys[i] == key) {
                LOGGER.fine(() -> "Insertion aborted. Duplicate key detected: " + key);
                return false; 
            }
        }

        int i = count - 1;
        while (i >= 0 && keys[i] > key) {
            keys[i + 1] = keys[i];
            values[i + 1] = values[i];
            i--;
        }
        keys[i + 1] = key;
        values[i + 1] = tid;
        setKeyCount((short) (count + 1));
        return true;
    }

    @Override
    public byte[] getPayloadBytes() {
        byte[] payloadBuffer = new byte[PAYLOAD_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(payloadBuffer);

        buffer.put(OFFSET_PAGE_TYPE, getPageType());
        buffer.putShort(OFFSET_KEY_COUNT, getKeyCount());
        buffer.putInt(OFFSET_PARENT_ID, getParentPageID());
        buffer.putInt(OFFSET_NEXT_LEAF, nextLeafPageID);

        buffer.position(OFFSET_KEYS);
        for (int i = 0; i < getKeyCount(); i++) {
            buffer.putInt(keys[i]);
        }

        buffer.position(OFFSET_VALUES);
        for (int i = 0; i < getKeyCount(); i++) {
            buffer.putLong(values[i]);
        }

        return payloadBuffer;
    }

    @Override
    public void parsePayloadBytes(byte[] payload) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(payload);

        // Read page type byte to advance position, or verify type
        byte type = buffer.get(OFFSET_PAGE_TYPE);
        if (type != StorageManager.TYPE_LEAF) {
            throw new IOException("Invalid page type for LeafNode: " + type);
        }

        setKeyCount(buffer.getShort(OFFSET_KEY_COUNT));
        setParentPageID(buffer.getInt(OFFSET_PARENT_ID));
        this.nextLeafPageID = buffer.getInt(OFFSET_NEXT_LEAF);

        buffer.position(OFFSET_KEYS);
        for (int i = 0; i < getKeyCount(); i++) {
            this.keys[i] = buffer.getInt();
        }

        buffer.position(OFFSET_VALUES);
        for (int i = 0; i < getKeyCount(); i++) {
            this.values[i] = buffer.getLong();
        }
    }

    public int getNextLeafPageID() { return nextLeafPageID; }
    public void setNextLeafPageID(int nextLeafPageID) { this.nextLeafPageID = nextLeafPageID; }
    public int[] getKeys() { return keys; }
    public long[] getValues() { return values; }
}
