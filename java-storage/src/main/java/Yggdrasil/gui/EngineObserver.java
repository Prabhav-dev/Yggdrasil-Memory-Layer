package Yggdrasil.gui;

/**
 * Event notification interface for monitoring physical disk flushes, page fetches,
 * and cryptographic integrity events emitted by the storage core.
 */
public interface EngineObserver {
    
    /** Triggered after a physical page is serialized, encrypted, and written to storage. */
    void onPageFlush(int pageNumber, byte[] encryptedFrame);

    /** Triggered when a physical page is read into memory and successfully decrypted. */
    void onPageFetch(int pageNumber, byte[] decryptedPayload);

    /** Triggered when tag verification or cryptographic validation fails during a read. */
    void onAuthFailure(int pageNumber, String errorMessage);
}