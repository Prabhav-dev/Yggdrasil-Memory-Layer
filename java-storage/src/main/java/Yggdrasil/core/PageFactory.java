package Yggdrasil.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import Yggdrasil.crypto.Ascon128Engine;

/**
 * Factory class for reading, decrypting, and deserializing disk frames
 * into concrete Page objects.
 */
public final class PageFactory {

    private PageFactory() {
        // Prevent instantiation
    }

    public static BTreeNode deserialize(int providedPageID, int pageNumber, byte[] payloadBuffer) throws IOException {
        int targetPageID = (providedPageID == -1) ? pageNumber : providedPageID;
        byte nodeType = payloadBuffer[Page.OFFSET_PAGE_TYPE];

        if (nodeType == StorageManager.TYPE_LEAF) {
            LeafNode leaf = new LeafNode(targetPageID, pageNumber);
            leaf.parsePayloadBytes(payloadBuffer);
            return leaf;
        }
        
        if (nodeType == StorageManager.TYPE_INTERNAL) {
            InternalNode internal = new InternalNode(targetPageID, pageNumber);
            internal.parsePayloadBytes(payloadBuffer);
            return internal;
        }

        throw new IOException("Unrecognized or invalid B-Tree node type: " + nodeType);
    }

    /**
     * Reads a raw page frame from disk, decrypts its payload with ASCON-128,
     * and instantiates the appropriate Page subtype.
     */
    public static Page readFromDisk(RandomAccessFile raf, int providedPageID, int pageNumber, byte[] secretKey) throws IOException {
        long byteOffset = (long) pageNumber * Page.PAGE_SIZE;
        if (byteOffset >= raf.length()) {
            throw new IOException("Page " + pageNumber + " out of bounds for current database file.");
        }

        raf.seek(byteOffset);
        byte[] frameBuffer = new byte[Page.PAGE_SIZE];
        raf.readFully(frameBuffer);

        int targetPageID = (providedPageID == -1) ? pageNumber : providedPageID;
        byte[] payload = extractPayload(frameBuffer, secretKey, targetPageID);
        byte pageType = payload[Page.OFFSET_PAGE_TYPE];

        // Instantiate the page based on the type flag
        if (pageType == StorageManager.TYPE_SLOTTED_DATA) {
            return SlottedPage.deserialize(targetPageID, pageNumber, payload);
        } 
        
        if (pageType == StorageManager.TYPE_LEAF) {
            LeafNode leaf = new LeafNode(targetPageID, pageNumber);
            leaf.parsePayloadBytes(payload);
            return leaf;
        } 
        
        if (pageType == StorageManager.TYPE_INTERNAL) {
            InternalNode internal = new InternalNode(targetPageID, pageNumber);
            internal.parsePayloadBytes(payload);
            return internal;
        }

        throw new IOException("Corrupted header: Unknown page type " + pageType + " on Page " + pageNumber);
    }

    /**
     * Unpacks the nonce and encrypted payload from a raw disk frame and decrypts it.
     */
    public static byte[] extractPayload(byte[] diskFrame, byte[] secretKey, int pageID) throws IOException {
        if (diskFrame == null || diskFrame.length != Page.PAGE_SIZE) {
            throw new IllegalArgumentException("Expected disk frame of size " + Page.PAGE_SIZE + " bytes.");
        }

        if (secretKey == null || secretKey.length != 16) {
            throw new IllegalArgumentException("Master key must be exactly 16 bytes for ASCON-128.");
        }

        if (pageID < 0) {
            throw new IllegalArgumentException("A valid positive page ID is required for decryption.");
        }

        // Separate the 16-byte Nonce from the remaining ciphertext frame
        byte[] nonce = new byte[Page.NONCE_SIZE];
        System.arraycopy(diskFrame, Page.OFFSET_NONCE, nonce, 0, Page.NONCE_SIZE);

        int cipherLength = Page.PAGE_SIZE - Page.NONCE_SIZE;
        byte[] ciphertext = new byte[cipherLength];
        System.arraycopy(diskFrame, Page.OFFSET_CIPHERTEXT, ciphertext, 0, cipherLength);

        // Perform authenticated decryption
        byte[] associatedData = Page.associatedData(pageID);
        byte[] payload = Ascon128Engine.decryptPayload(secretKey, nonce, associatedData, ciphertext);

        if (payload == null) {
            Yggdrasil.gui.EngineTelemetry.fireAuthFailure(pageID, "ASCON-128 authentication check failed for Page " + pageID);
            throw new PageAuthenticationException("Authentication failed for Page " + pageID + ". The frame may be corrupted or tampered with.");
        }

        Yggdrasil.gui.EngineTelemetry.firePageFetch(pageID, payload);
        return payload;
    }
}