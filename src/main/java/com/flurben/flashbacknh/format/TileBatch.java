package com.flurben.flashbacknh.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Compact encoding of the tile entities a snapshot has to restore from NBT. A GregTech world holds hundreds of
 * thousands of ore tile entities whose NBT differs only by position and ore type, so each distinct NBT (without its
 * coordinates) is stored once and a tile entity is just a position plus an index into that table.
 *
 * <p>
 * Payload: {@code [templates: varint]([length: varint][uncompressed NBT])*},
 * {@code [chunks: varint]([chunkX: zigzag varint][chunkZ: zigzag varint][count: varint])*}, then one position per tile
 * entity (varint difference from the previous {@code y << 8 | z << 4 | x} of its chunk, ascending), then one varint
 * template index per tile entity. Keeping positions and indices apart lets the archive's deflate compress each better.
 */
public final class TileBatch {

    private static final int NBT_INT = 3;

    /** Receives the decoded tile entities, chunk by chunk. */
    public interface Sink {

        /** Whether to make a tag for this tile entity, which is what takes time when there are very many. */
        boolean wants(int x, int y, int z);

        void tile(int x, int y, int z, NBTTagCompound tag);

        void chunkDone(int chunkX, int chunkZ, int minY, int maxY);
    }

    private static final class ChunkEntries {

        long[] entries = new long[16];
        int count;

        void add(long entry) {
            if (count == entries.length) entries = Arrays.copyOf(entries, count * 2);
            entries[count++] = entry;
        }
    }

    private final Map<ByteBuffer, Integer> templateIndex = new HashMap<>();
    private final List<byte[]> templates = new ArrayList<>();
    private final Map<Long, ChunkEntries> chunks = new LinkedHashMap<>();
    private final ByteArrayOutputStream nbtBytes = new ByteArrayOutputStream(256);
    private int size;

    public int size() {
        return size;
    }

    /**
     * Adds a tile entity. The tag loses its coordinates in the process.
     *
     * @return false when the position cannot be represented, in which case nothing was added
     */
    public boolean add(int x, int y, int z, NBTTagCompound tag) throws IOException {
        if (y < 0 || y > 255) return false;
        removeCoordinate(tag, "x", x);
        removeCoordinate(tag, "y", y);
        removeCoordinate(tag, "z", z);
        nbtBytes.reset();
        DataOutputStream dos = new DataOutputStream(nbtBytes);
        CompressedStreamTools.write(tag, dos);
        dos.flush();
        byte[] encoded = nbtBytes.toByteArray();
        ByteBuffer key = ByteBuffer.wrap(encoded);
        Integer index = templateIndex.get(key);
        if (index == null) {
            index = templates.size();
            templates.add(encoded);
            templateIndex.put(key, index);
        }
        long chunkKey = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
        ChunkEntries entries = chunks.get(chunkKey);
        if (entries == null) {
            entries = new ChunkEntries();
            chunks.put(chunkKey, entries);
        }
        int position = y << 8 | (z & 15) << 4 | (x & 15);
        entries.add((long) position << 32 | index);
        size++;
        return true;
    }

    /** A mod may use these names for something else, so only the real coordinates are left out. */
    private static void removeCoordinate(NBTTagCompound tag, String name, int value) {
        if (tag.func_150299_b(name) == NBT_INT && tag.getInteger(name) == value) tag.removeTag(name);
    }

    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        RecordWriter.writeVarInt(out, templates.size());
        for (byte[] template : templates) {
            RecordWriter.writeVarInt(out, template.length);
            out.write(template);
        }
        RecordWriter.writeVarInt(out, chunks.size());
        for (Map.Entry<Long, ChunkEntries> e : chunks.entrySet()) {
            RecordWriter.writeVarInt(out, zigzag((int) (e.getKey() >> 32)));
            RecordWriter.writeVarInt(out, zigzag((int) (long) e.getKey()));
            RecordWriter.writeVarInt(out, e.getValue().count);
            Arrays.sort(e.getValue().entries, 0, e.getValue().count);
        }
        for (ChunkEntries entries : chunks.values()) {
            int previous = 0;
            for (int i = 0; i < entries.count; i++) {
                int position = (int) (entries.entries[i] >>> 32);
                RecordWriter.writeVarInt(out, position - previous);
                previous = position;
            }
        }
        for (ChunkEntries entries : chunks.values()) {
            for (int i = 0; i < entries.count; i++) RecordWriter.writeVarInt(out, (int) entries.entries[i]);
        }
        return out.toByteArray();
    }

    /** Decodes a payload. Every tile entity gets its own tag, with its coordinates back in place. */
    public static void read(byte[] data, Sink sink) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        NBTTagCompound[] templates = new NBTTagCompound[ReplayStream.readVarInt(in, null)];
        for (int i = 0; i < templates.length; i++) {
            byte[] encoded = new byte[ReplayStream.readVarInt(in, null)];
            in.readFully(encoded);
            templates[i] = CompressedStreamTools.read(new DataInputStream(new ByteArrayInputStream(encoded)));
        }
        int chunkCount = ReplayStream.readVarInt(in, null);
        int[] chunkX = new int[chunkCount];
        int[] chunkZ = new int[chunkCount];
        int[] counts = new int[chunkCount];
        int total = 0;
        for (int i = 0; i < chunkCount; i++) {
            chunkX[i] = unzigzag(ReplayStream.readVarInt(in, null));
            chunkZ[i] = unzigzag(ReplayStream.readVarInt(in, null));
            counts[i] = ReplayStream.readVarInt(in, null);
            total += counts[i];
        }
        int[] positions = new int[total];
        int at = 0;
        for (int i = 0; i < chunkCount; i++) {
            int position = 0;
            for (int j = 0; j < counts[i]; j++) {
                position += ReplayStream.readVarInt(in, null);
                positions[at++] = position;
            }
        }
        at = 0;
        for (int i = 0; i < chunkCount; i++) {
            int minY = 255, maxY = 0;
            for (int j = 0; j < counts[i]; j++, at++) {
                int position = positions[at];
                int y = position >> 8;
                int x = chunkX[i] << 4 | (position & 15);
                int z = chunkZ[i] << 4 | (position >> 4 & 15);
                NBTTagCompound template = templates[ReplayStream.readVarInt(in, null)];
                if (!sink.wants(x, y, z)) continue;
                NBTTagCompound tag = (NBTTagCompound) template.copy();
                if (!tag.hasKey("x")) tag.setInteger("x", x);
                if (!tag.hasKey("y")) tag.setInteger("y", y);
                if (!tag.hasKey("z")) tag.setInteger("z", z);
                sink.tile(x, y, z, tag);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            if (minY <= maxY) sink.chunkDone(chunkX[i], chunkZ[i], minY, maxY);
        }
    }

    private static int zigzag(int value) {
        return (value << 1) ^ (value >> 31);
    }

    private static int unzigzag(int value) {
        return (value >>> 1) ^ -(value & 1);
    }
}
