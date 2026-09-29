package Yggdrasil;

import java.util.Arrays;
import Yggdrasil.crypto.Ascon128Engine;

/** 
 * Verification suite running known answer tests (KAT) against 
 * the Ascon-128 cryptographic engine.
 */
public final class AsconNistVectorTest {

    private AsconNistVectorTest() {
        // Utility class constructor
    }

    public static void main(String[] args) {
        byte[] secretKey = parseHex("000102030405060708090A0B0C0D0E0F");
        byte[] ivNonce = parseHex("101112131415161718191A1B1C1D1E1F");
        byte[] expectedCipher = parseHex("38CCA290D1F2EF3DF9C8531946499037");

        byte[] emptyBuffer = new byte[0];
        byte[] encryptedOutput = Ascon128Engine.encryptPayload(secretKey, ivNonce, emptyBuffer, emptyBuffer);

        if (!Arrays.equals(expectedCipher, encryptedOutput)) {
            throw new AssertionError("Ascon-128 KAT encryption mismatch! Expected: " 
                    + formatHex(expectedCipher) + ", but got: " + formatHex(encryptedOutput));
        }

        byte[] decryptedOutput = Ascon128Engine.decryptPayload(secretKey, ivNonce, emptyBuffer, encryptedOutput);
        if (decryptedOutput == null) {
            throw new AssertionError("Ascon-128 KAT decryption verification failed (returned null).");
        }

        System.out.println("Ascon-128 NIST KAT verification passed successfully.");
    }

    private static byte[] parseHex(String hexString) {
        int length = hexString.length();
        byte[] data = new byte[length / 2];
        
        for (int i = 0; i < length; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                                 + Character.digit(hexString.charAt(i + 1), 16));
        }
        return data;
    }

    private static String formatHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}