package Yggdrasil;

import java.io.File;
import java.io.RandomAccessFile;
import Yggdrasil.crypto.Ascon128Engine;
import Yggdrasil.core.*;

public final class StorageSecurityTest {

    private StorageSecurityTest() {
    }

    public static void main(String[] args) throws Exception {
        File tempDbFile = File.createTempFile("yggdrasil-security-", ".db");
        byte[] encryptionKey = new byte[Ascon128Engine.KEY_LEN];
        
        try {
            verifyMetadataHeader(tempDbFile);
            verifyPageIdentityBinding(tempDbFile, encryptionKey);
            System.out.println("[SUCCESS] Storage security checks passed cleanly.");
        } finally {
            if (!tempDbFile.delete()) {
                tempDbFile.deleteOnExit();
            }
        }
    }

    private static void verifyMetadataHeader(File targetFile) throws Exception {
        if (!targetFile.delete()) {
            throw new IllegalStateException("Failed to purge existing temp metadata file.");
        }
        
        Metadata metadata = new Metadata();
        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
            metadata.pageZero(raf);
            if (raf.length() != Page.PAGE_SIZE) {
                throw new AssertionError("Metadata setup produced an invalid page size.");
            }

            raf.seek(0);
            raf.writeInt(0); // Invalidate magic header bytes
            
            try {
                metadata.pageZero(raf);
                throw new AssertionError("Corrupted metadata magic header was accepted.");
            } catch (java.io.IOException expected) {
                // Expected rejection route
            }
        }
    }

    private static void verifyPageIdentityBinding(File targetFile, byte[] key) throws Exception {
        SlottedPage testPage = new SlottedPage(7, 1);
        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
            testPage.writeToDisk(raf, testPage.getPayloadBytes(), key);
        }

        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "r")) {
            raf.seek(Page.PAGE_SIZE);
            byte[] pageFrame = new byte[Page.PAGE_SIZE];
            raf.readFully(pageFrame);
            
            if (PageFactory.extractPayload(pageFrame, key, 7) == null) {
                throw new AssertionError("Authenticated storage page could not be decrypted.");
            }
            
            try {
                PageFactory.extractPayload(pageFrame, key, 8); // Wrong page index binding
                throw new AssertionError("Storage engine accepted swapped page identity frame.");
            } catch (java.io.IOException expected) {
                // Expected MAC verification failure
            }

            byte originalTypeByte = pageFrame[Page.OFFSET_NONCE];
            pageFrame[Page.OFFSET_NONCE] = StorageManager.TYPE_LEAF; // Mutate metadata flags
            
            try {
                if (PageFactory.extractPayload(pageFrame, key, 7) != null) {
                    throw new AssertionError("Storage engine accepted modified page header flags.");
                }
            } catch (java.io.IOException expected) {
                // Expected MAC verification failure
            }
            pageFrame[Page.OFFSET_NONCE] = originalTypeByte;
        }
    }
}