package Yggdrasil.core;

import java.nio.ByteBuffer;

/**
 * SlottedPage class manages variable-length records within a 4064-byte decrypted payload.
 * Coordinates with TupleID to return 64-bit record locators (Page ID + Slot Offset).
 */
public class SlottedPage extends Page {

    private short slotCount;
    private short freeSpacePointer; // Grows upward from byte 4064
    private final byte[] payloadBuffer;

    private static final int OFFSET_SLOT_COUNT = 1;
    private static final int OFFSET_FREE_POINTER = 3;
    private static final int OFFSET_FREE_SPACE = 5;
    private static final int HEADER_END = 7;

    public SlottedPage(int pageID, int pageNumber) {
        super(pageID, pageNumber);
        this.payloadBuffer = new byte[PAYLOAD_SIZE];
        this.slotCount = 0;
        this.freeSpacePointer = (short) PAYLOAD_SIZE;
        
        payloadBuffer[OFFSET_PAGE_TYPE] = StorageManager.TYPE_SLOTTED_DATA;
        updateHeader();
    }

    private SlottedPage(int pageID, int pageNumber, byte[] payload) {
        super(pageID, pageNumber);
        this.payloadBuffer = payload;
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        this.slotCount = buffer.getShort(OFFSET_SLOT_COUNT);
        this.freeSpacePointer = buffer.getShort(OFFSET_FREE_POINTER);
    }

    public static SlottedPage deserialize(int pageID, int pageNumber, byte[] payload) {
        int effectivePageID = (pageID == -1) ? pageNumber : pageID;
        return new SlottedPage(effectivePageID, pageNumber, payload);
    }

    public synchronized long insertRecord(byte[] recordData) {
        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            if (recordLength(slotIndex) < 0) {
                return insertRecordAt(slotIndex, recordData);
            }
        }
        return insertRecordAt(slotCount, recordData);
    }

    public synchronized long insertRecordAt(int slotIndex, byte[] recordData) {
        if (recordData == null) {
            throw new IllegalArgumentException("Record data cannot be null.");
        }
        if (slotIndex < 0) {
            return -1L;
        }

        boolean reusingSlot = slotIndex < slotCount;
        if (reusingSlot && recordLength(slotIndex) >= 0) {
            return -1L;
        }

        int targetSlotCount = Math.max(slotCount, slotIndex + 1);
        int requiredSpace = recordData.length + (targetSlotCount - slotCount) * 4;
        int slotDirectoryEnd = directoryEnd(targetSlotCount);
        int availableSpace = freeSpacePointer - slotDirectoryEnd;

        if (availableSpace < requiredSpace) {
            compact();
            availableSpace = freeSpacePointer - slotDirectoryEnd;
            if (availableSpace < requiredSpace) {
                return -1L; // Out of space
            }
        }

        // Allocate from bottom up
        freeSpacePointer = (short) (freeSpacePointer - recordData.length);
        System.arraycopy(recordData, 0, payloadBuffer, freeSpacePointer, recordData.length);

        // Append slot entry
        int slotEntryOffset = HEADER_END + (slotIndex * 4);

        ByteBuffer buffer = ByteBuffer.wrap(payloadBuffer);
        buffer.putShort(slotEntryOffset, freeSpacePointer);
        buffer.putShort(slotEntryOffset + 2, (short) recordData.length);

        slotCount = (short) targetSlotCount;
        updateHeader();
        setDirty(true);

        return TupleID.pack(getPageNumber(), slotIndex);
    }

    public synchronized byte[] getRecord(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slotCount) return null;

        int slotEntryOffset = HEADER_END + (slotIndex * 4);
        ByteBuffer buffer = ByteBuffer.wrap(payloadBuffer);
        
        short recordOffset = buffer.getShort(slotEntryOffset);
        short recordLength = recordLength(slotIndex);

        if (recordLength < 0) return null; // Deleted record
        if (recordOffset < directoryEnd(slotCount) || recordOffset > PAYLOAD_SIZE
                || recordLength > PAYLOAD_SIZE - recordOffset) {
            return null;
        }

        byte[] record = new byte[recordLength];
        System.arraycopy(payloadBuffer, recordOffset, record, 0, recordLength);
        return record;
    }

    public synchronized boolean deleteRecord(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slotCount) return false;

        int slotEntryOffset = HEADER_END + (slotIndex * 4);
        ByteBuffer buffer = ByteBuffer.wrap(payloadBuffer);
        
        short recordLength = recordLength(slotIndex);
        if (recordLength < 0) return false;

        buffer.putShort(slotEntryOffset + 2, (short) -1); // Tombstone flag
        updateHeader();
        setDirty(true);
        return true;
    }

    public synchronized void compact() {
        byte[] tempHeap = new byte[PAYLOAD_SIZE];
        short newFreePointer = (short) PAYLOAD_SIZE;
        ByteBuffer buffer = ByteBuffer.wrap(payloadBuffer);

        for (int i = 0; i < slotCount; i++) {
            int slotEntryOffset = HEADER_END + (i * 4);
            short oldOffset = buffer.getShort(slotEntryOffset);
            short recordLength = buffer.getShort(slotEntryOffset + 2);

            if (recordLength >= 0) {
                newFreePointer -= recordLength;
                System.arraycopy(payloadBuffer, oldOffset, tempHeap, newFreePointer, recordLength);
                buffer.putShort(slotEntryOffset, newFreePointer);
            }
        }

        System.arraycopy(tempHeap, newFreePointer, payloadBuffer, newFreePointer, PAYLOAD_SIZE - newFreePointer);
        this.freeSpacePointer = newFreePointer;
        updateHeader();
        setDirty(true);
    }

    private void updateHeader() {
        ByteBuffer buffer = ByteBuffer.wrap(payloadBuffer);
        buffer.put(OFFSET_PAGE_TYPE, StorageManager.TYPE_SLOTTED_DATA);
        buffer.putShort(OFFSET_SLOT_COUNT, slotCount);
        buffer.putShort(OFFSET_FREE_POINTER, freeSpacePointer);
        buffer.putShort(OFFSET_FREE_SPACE, (short) (freeSpacePointer - directoryEnd(slotCount)));
    }

    private int directoryEnd(int numberOfSlots) {
        return HEADER_END + (numberOfSlots * 4);
    }

    public short getSlotCount() { return slotCount; }
    public short getFreeSpacePointer() { return freeSpacePointer; }
    
    public synchronized short recordLength(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slotCount) return -1;
        int slotEntryOffset = HEADER_END + (slotIndex * 4);
        ByteBuffer buffer = ByteBuffer.wrap(payloadBuffer);
        return buffer.getShort(slotEntryOffset + 2);
    }

    @Override
    public byte[] getPayloadBytes() {
        updateHeader();
        return payloadBuffer;
    }

    @Override
    public void parsePayloadBytes(byte[] payload) {
        System.arraycopy(payload, 0, this.payloadBuffer, 0, PAYLOAD_SIZE);
        ByteBuffer buffer = ByteBuffer.wrap(payloadBuffer);
        this.slotCount = buffer.getShort(OFFSET_SLOT_COUNT);
        this.freeSpacePointer = buffer.getShort(OFFSET_FREE_POINTER);
    }
}
