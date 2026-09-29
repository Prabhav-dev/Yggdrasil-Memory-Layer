package Yggdrasil;

import Yggdrasil.core.Metadata;
import Yggdrasil.core.Page;
import Yggdrasil.core.TupleID;

/** Verifies the storage address-space arithmetic without allocating a huge file. */
public final class StorageCapacityTest {

    private StorageCapacityTest() {
    }

    public static void main(String[] args) {
        long expectedMaximumPage = (1L << 31) - 1;
        if (Metadata.MAX_PAGE_NUMBER != expectedMaximumPage) {
            throw new AssertionError("Maximum page number is not 2^31 - 1.");
        }

        long addressableFileSpan = (expectedMaximumPage + 1) * Page.PAGE_SIZE;
        if (addressableFileSpan != (1L << 43)) {
            throw new AssertionError("Page address space does not span exactly 8 TiB.");
        }

        int actualPayloadSize = new byte[Page.PAYLOAD_SIZE].length;
        int actualPageSize = new byte[Page.PAGE_SIZE].length;
        if (actualPayloadSize != 4064 || actualPageSize != 4096) {
            throw new AssertionError("Page geometry changed unexpectedly.");
        }

        long tupleID = TupleID.pack(Metadata.MAX_PAGE_NUMBER, 0);
        if (TupleID.getPageNumber(tupleID) != Metadata.MAX_PAGE_NUMBER) {
            throw new AssertionError("Maximum page number cannot round-trip through TupleID.");
        }

        System.out.println("[PASS] 2^31 - 1 page limit and 8 TiB address-space arithmetic verified.");
    }
}
