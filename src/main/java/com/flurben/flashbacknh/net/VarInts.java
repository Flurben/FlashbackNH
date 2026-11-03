package com.flurben.flashbacknh.net;

/** VarInt helpers for raw packet bytes (no Minecraft dependencies). */
public final class VarInts {

    private VarInts() {}

    /** The packet id encoded as the leading varint, or -1 if malformed. */
    public static int peekId(byte[] data) {
        int value = 0;
        int shift = 0;
        for (int i = 0; i < data.length && i < 5; i++) {
            int b = data[i] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
        }
        return -1;
    }

    /** Number of bytes taken by the leading varint. */
    public static int idLength(byte[] data) {
        for (int i = 0; i < data.length && i < 5; i++) {
            if ((data[i] & 0x80) == 0) return i + 1;
        }
        return data.length;
    }
}
