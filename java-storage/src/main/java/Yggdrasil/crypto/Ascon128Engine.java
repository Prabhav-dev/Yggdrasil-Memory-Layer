package Yggdrasil.crypto;
public class Ascon128Engine {

    public static final int KEY_LEN = 16;
    public static final int NONCE_LEN = 16;
    public static final int TAG_LEN = 16;

    private static long packBytes(byte[] buffer, int off) {
        long out = 0;
        for (int i = 0; i < 8; i++) {
            out = (out << 8) | (buffer[off + i] & 0xFFL);
        }
        return out;
    }

    private static void unpackBytes(byte[] buffer, int off, long val) {
        for (int i = 7; i >= 0; i--) {
            buffer[off + i] = (byte) (val & 0xFF);
            val >>>= 8;
        }
    }

    private static long rotateRight(long val, int shift) {
        return Long.rotateRight(val, shift);
    }

    private static void transformState(long[] st, int rounds) {
        for (int r = 12 - rounds; r < 12; r++) {
            st[2] ^= (((0xFL - r) << 4) | r);

            st[0] ^= st[4]; st[4] ^= st[3]; st[2] ^= st[1];
            long v0 = ~st[0] & st[1];
            long v1 = ~st[1] & st[2];
            long v2 = ~st[2] & st[3];
            long v3 = ~st[3] & st[4];
            long v4 = ~st[4] & st[0];
            st[0] ^= v1; st[1] ^= v2; st[2] ^= v3; st[3] ^= v4; st[4] ^= v0;
            st[1] ^= st[0]; st[0] ^= st[4]; st[3] ^= st[2]; st[2] = ~st[2];

            st[0] ^= rotateRight(st[0], 19) ^ rotateRight(st[0], 28);
            st[1] ^= rotateRight(st[1], 61) ^ rotateRight(st[1], 39);
            st[2] ^= rotateRight(st[2], 1)  ^ rotateRight(st[2], 6);
            st[3] ^= rotateRight(st[3], 10) ^ rotateRight(st[3], 17);
            st[4] ^= rotateRight(st[4], 7)  ^ rotateRight(st[4], 41);
        }
    }

    public static byte[] encryptPayload(byte[] secretKey, byte[] publicNonce, byte[] assocData, byte[] rawData) {
        long[] st = new long[5];

        st[0] = 0x80400c0600000000L;
        st[1] = packBytes(secretKey, 0);
        st[2] = packBytes(secretKey, 8);
        st[3] = packBytes(publicNonce, 0);
        st[4] = packBytes(publicNonce, 8);

        transformState(st, 12);
        st[3] ^= packBytes(secretKey, 0);
        st[4] ^= packBytes(secretKey, 8);

        if (assocData != null && assocData.length > 0) {
            int len = assocData.length;
            int ptr = 0;
            while (len >= 8) {
                st[0] ^= packBytes(assocData, ptr);
                transformState(st, 6);
                ptr += 8;
                len -= 8;
            }
            long padBlock = 0;
            for (int i = 0; i < len; i++) {
                padBlock |= ((long) (assocData[ptr + i] & 0xFF)) << (56 - i * 8);
            }
            padBlock |= (0x80L << (56 - len * 8));
            st[0] ^= padBlock;
            transformState(st, 6);
        }
        st[4] ^= 1L;

        byte[] encResult = new byte[rawData.length + TAG_LEN];
        int remLen = rawData.length;
        int dataPtr = 0;

        while (remLen >= 8) {
            st[0] ^= packBytes(rawData, dataPtr);
            unpackBytes(encResult, dataPtr, st[0]);
            transformState(st, 6);
            dataPtr += 8;
            remLen -= 8;
        }

        long tailBlock = 0;
        for (int i = 0; i < remLen; i++) {
            tailBlock |= ((long) (rawData[dataPtr + i] & 0xFF)) << (56 - i * 8);
        }
        tailBlock |= (0x80L << (56 - remLen * 8));
        st[0] ^= tailBlock;

        for (int i = 0; i < remLen; i++) {
            encResult[dataPtr + i] = (byte) (st[0] >>> (56 - i * 8));
        }

        st[1] ^= packBytes(secretKey, 0);
        st[2] ^= packBytes(secretKey, 8);
        transformState(st, 12);
        st[3] ^= packBytes(secretKey, 0);
        st[4] ^= packBytes(secretKey, 8);

        unpackBytes(encResult, rawData.length, st[3]);
        unpackBytes(encResult, rawData.length + 8, st[4]);

        return encResult;
    }

    public static byte[] decryptPayload(byte[] secretKey, byte[] publicNonce, byte[] assocData, byte[] encData) {
        if (encData == null || encData.length < TAG_LEN) return null;

        int rawLen = encData.length - TAG_LEN;
        long[] st = new long[5];

        st[0] = 0x80400c0600000000L;
        st[1] = packBytes(secretKey, 0);
        st[2] = packBytes(secretKey, 8);
        st[3] = packBytes(publicNonce, 0);
        st[4] = packBytes(publicNonce, 8);

        transformState(st, 12);
        st[3] ^= packBytes(secretKey, 0);
        st[4] ^= packBytes(secretKey, 8);

        if (assocData != null && assocData.length > 0) {
            int len = assocData.length;
            int ptr = 0;
            while (len >= 8) {
                st[0] ^= packBytes(assocData, ptr);
                transformState(st, 6);
                ptr += 8;
                len -= 8;
            }
            long padBlock = 0;
            for (int i = 0; i < len; i++) {
                padBlock |= ((long) (assocData[ptr + i] & 0xFF)) << (56 - i * 8);
            }
            padBlock |= (0x80L << (56 - len * 8));
            st[0] ^= padBlock;
            transformState(st, 6);
        }
        st[4] ^= 1L;

        byte[] rawOutput = new byte[rawLen];
        int remLen = rawLen;
        int dataPtr = 0;

        while (remLen >= 8) {
            long chunk = packBytes(encData, dataPtr);
            unpackBytes(rawOutput, dataPtr, st[0] ^ chunk);
            st[0] = chunk;
            transformState(st, 6);
            dataPtr += 8;
            remLen -= 8;
        }

        long tailChunk = 0;
        for (int i = 0; i < remLen; i++) {
            tailChunk |= ((long) (encData[dataPtr + i] & 0xFF)) << (56 - i * 8);
        }

        for (int i = 0; i < remLen; i++) {
            rawOutput[dataPtr + i] = (byte) ((st[0] >>> (56 - i * 8)) ^ (encData[dataPtr + i] & 0xFF));
        }

        if (remLen > 0) {
            long mask = 0xFFFFFFFFFFFFFFFFL << (56 - remLen * 8);
            st[0] = (st[0] & ~mask) | tailChunk;
            st[0] ^= (0x80L << (56 - remLen * 8));
        } else {
            st[0] ^= (0x80L << 56);
        }

        st[1] ^= packBytes(secretKey, 0);
        st[2] ^= packBytes(secretKey, 8);
        transformState(st, 12);
        st[3] ^= packBytes(secretKey, 0);
        st[4] ^= packBytes(secretKey, 8);

        long checkTag1 = st[3];
        long checkTag2 = st[4];
        long inputTag1 = packBytes(encData, rawLen);
        long inputTag2 = packBytes(encData, rawLen + 8);

        if (((checkTag1 ^ inputTag1) | (checkTag2 ^ inputTag2)) != 0) {
            return null;
        }

        return rawOutput;
    }
}
