package Yggdrasil.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * InternalNode class represents an internal node in a B+ tree.
 * It stores routing keys and child page pointers.
 */
public class InternalNode extends BTreeNode {

    private final int[] keys;
    private final int[] childPageNumbers; 

    public static final int MAX_KEYS = 506;
    private static final int OFFSET_KEYS = COMMON_HEADER_SIZE; // Offset 7
    private static final int OFFSET_CHILDREN = COMMON_HEADER_SIZE + (MAX_KEYS * 4); // Offset 2047

    public InternalNode(int pageID, int pageNumber) {
        super(pageID, pageNumber, StorageManager.TYPE_INTERNAL);
        this.keys = new int[MAX_KEYS];
        this.childPageNumbers = new int[MAX_KEYS + 1];
        
        Arrays.fill(this.childPageNumbers, -1);
    }

    public int findChildPageNumber(int targetKey) {
        short count = getKeyCount();
        if (count == 0) return childPageNumbers[0];

        int low = 0;
        int high = count - 1;
        int index = 0;

        while (low <= high) {
            int mid = (low + high) >>> 1;

            if (keys[mid] <= targetKey) {
                index = mid + 1; 
                low = mid + 1;  
            } else {
                high = mid - 1; 
            }
        }
        return childPageNumbers[index];
    }

    public int findChildPageID(int targetKey) {
        return findChildPageNumber(targetKey);
    }

    public boolean insert(int key, int childPageNum) {
        short count = getKeyCount();
        if (count >= MAX_KEYS) {
            return false;
        }

        int i = count - 1;
        while (i >= 0 && keys[i] > key) {
            keys[i + 1] = keys[i];
            childPageNumbers[i + 2] = childPageNumbers[i + 1];
            i--;
        }
        
        keys[i + 1] = key;
        childPageNumbers[i + 2] = childPageNum;
        
        setKeyCount((short) (count + 1));
        return true;
    }

    @Override
    public byte[] getPayloadBytes() {
        byte[] payload = new byte[PAYLOAD_SIZE]; // 4064 Bytes
        ByteBuffer buffer = ByteBuffer.wrap(payload);

        // 1. Common Header (PageType, KeyCount, ParentID)
        serializeCommonHeader(buffer);

        // 2. Write active Keys (0 to keyCount - 1)
        buffer.position(OFFSET_KEYS);
        for (int i = 0; i < getKeyCount(); i++) {
            buffer.putInt(keys[i]);
        }

        // 3. Write active Child Page Numbers (0 to keyCount)
        buffer.position(OFFSET_CHILDREN);
        for (int i = 0; i <= getKeyCount(); i++) {
            buffer.putInt(childPageNumbers[i]);
        }

        return payload;
    }

    @Override
    public void parsePayloadBytes(byte[] payload) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(payload);

        setKeyCount(buffer.getShort(OFFSET_KEY_COUNT));
        setParentPageID(buffer.getInt(OFFSET_PARENT_ID));

        int keyCount = getKeyCount();
        if (keyCount < 0 || keyCount > MAX_KEYS) {
            throw new IOException("Invalid internal-node key count: " + keyCount);
        }
        buffer.position(OFFSET_KEYS);
        for (int i = 0; i < keyCount; i++) {
            this.keys[i] = buffer.getInt();
        }

        buffer.position(OFFSET_CHILDREN);
        Arrays.fill(this.childPageNumbers, -1);
        for (int i = 0; i <= keyCount; i++) {
            this.childPageNumbers[i] = buffer.getInt();
        }
    }

    public int[] getChildPageNumbers() { return childPageNumbers; }
    public int[] getChildPageIDs() { return childPageNumbers; }
    public int[] getKeys() { return keys; }
}
