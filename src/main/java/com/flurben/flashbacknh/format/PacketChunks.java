package com.flurben.flashbacknh.format;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Reads which chunks a recorded packet changes, from its wire format and without decoding it. Packet ids here are the
 * clientbound play ids, which all fit the single byte of the id's varint.
 */
public final class PacketChunks {

    public static final int CHUNK = 0x21;
    public static final int MULTI_BLOCK = 0x22;
    public static final int BLOCK = 0x23;
    public static final int BULK = 0x26;
    public static final int EXPLOSION = 0x27;

    private static final int CHUNK_ENTRY_SIZE = 12;

    private PacketChunks() {}

    public static boolean changesChunks(int id) {
        return id == CHUNK || id == MULTI_BLOCK || id == BLOCK || id == BULK || id == EXPLOSION;
    }

    public static boolean hasChunkData(int id) {
        return id == CHUNK || id == BULK;
    }

    /** Whether a chunk packet unloads its chunk, which is one that replaces all columns and has no sections. */
    public static boolean isUnload(byte[] packet) {
        return packet.length >= 12 && packet[0] == CHUNK && packet[9] != 0 && packet[10] == 0 && packet[11] == 0;
    }

    /**
     * The chunks a packet of one of the {@link #changesChunks} kinds changes, or null when it does not have the
     * expected layout. A mod that changed the layout then costs the optimizations that need this, not correctness.
     */
    public static long[] of(byte[] packet) {
        try {
            ByteBuffer in = ByteBuffer.wrap(packet);
            int id = in.get();
            switch (id) {
                case CHUNK:
                case MULTI_BLOCK:
                    return new long[] { ReplayIndex.chunkKey(in.getInt(), in.getInt()) };
                case BLOCK: {
                    int x = in.getInt();
                    in.get();
                    return new long[] { ReplayIndex.chunkKey(x >> 4, in.getInt() >> 4) };
                }
                case BULK:
                    return bulk(in);
                case EXPLOSION:
                    return explosion(in);
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long[] bulk(ByteBuffer in) {
        int count = in.getShort();
        int dataLength = in.getInt();
        in.get();
        if (count < 0 || dataLength < 0 || dataLength > in.remaining()) return null;
        in.position(in.position() + dataLength);
        if (in.remaining() != count * CHUNK_ENTRY_SIZE) return null;
        long[] chunks = new long[count];
        for (int i = 0; i < count; i++) {
            chunks[i] = ReplayIndex.chunkKey(in.getInt(), in.getInt());
            in.getInt();
        }
        return chunks;
    }

    /** An explosion's blocks are bytes relative to its center. */
    private static long[] explosion(ByteBuffer in) {
        int centerX = (int) in.getFloat();
        in.getFloat();
        int centerZ = (int) in.getFloat();
        in.getFloat();
        int count = in.getInt();
        if (count < 0 || count * 3 > in.remaining()) return null;
        long[] chunks = new long[count + 1];
        chunks[0] = ReplayIndex.chunkKey(centerX >> 4, centerZ >> 4);
        for (int i = 1; i <= count; i++) {
            int x = centerX + in.get();
            in.get();
            chunks[i] = ReplayIndex.chunkKey(x >> 4, (centerZ + in.get()) >> 4);
        }
        Arrays.sort(chunks);
        int unique = 0;
        for (int i = 0; i < chunks.length; i++) {
            if (i == 0 || chunks[i] != chunks[i - 1]) chunks[unique++] = chunks[i];
        }
        return Arrays.copyOf(chunks, unique);
    }
}
