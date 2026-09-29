package Yggdrasil.core;

/**
 * TupleID class represents a unique identifier for a tuple in the database.
 * It consists of a page number and a slot offset within that page.
 */
public class TupleID {

    /**
     * Packs a 32-bit page number and a 32-bit slot offset into a single 64-bit long primitive.
     */
    public static long pack(int pageNumber, int slotOffset) {
        if (pageNumber < 0 || slotOffset < 0) {
            throw new IllegalArgumentException("Page number and slot offset must be non-negative: " 
                + pageNumber + ", " + slotOffset);
        }
        return ((long) pageNumber << 32) | (slotOffset & 0xFFFFFFFFL);
    }

    /**
     * Extracts the 32-bit page number from a packed 64-bit TID.
     */
    public static int getPageNumber(long tid) {
        return (int) (tid >>> 32);
    }

    /**
     * Extracts the 32-bit slot offset from a packed 64-bit TID.
     * Direct narrowing primitive cast cleanly discards the upper 32 bits.
     */
    public static int getSlotOffset(long tid) {
        return (int) tid;
    }

    public static int getSlotIndex(long tid) {
        return getSlotOffset(tid);
    }
}
