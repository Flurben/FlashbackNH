package com.flurben.flashbacknh.format;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a replay's records do to the world, gathered when the stream is opened. It answers two questions a seek has:
 * which records no longer matter because the world was replaced later on, and which chunks differ between two ticks.
 */
public final class ReplayIndex {

    /** A record that replaces the whole world: nothing before it can still be seen after it. */
    public static final class Boundary {

        public final int tick;
        public final long position;
        /** Everything after a join or a cleared world rebuilds the world's state. A dimension change keeps some. */
        public final boolean rebuildsAll;

        Boundary(int tick, long position, boolean rebuildsAll) {
            this.tick = tick;
            this.position = position;
            this.rebuildsAll = rebuildsAll;
        }
    }

    /** How to get from one tick back to an earlier one without replacing the world. */
    public static final class Rewind {

        public final Boundary boundary;
        /** The chunks to throw away and restore from the records. All others are the same at both ticks. */
        public final Set<Long> chunks;

        Rewind(Boundary boundary, Set<Long> chunks) {
            this.boundary = boundary;
            this.chunks = chunks;
        }
    }

    private static final class SharedRecord {

        final int tick;
        final long[] chunks;

        SharedRecord(int tick, long[] chunks) {
            this.tick = tick;
            this.chunks = chunks;
        }
    }

    private final List<Boundary> boundaries = new ArrayList<>();
    private final List<SharedRecord> sharedRecords = new ArrayList<>();
    private int[] globalChangeTicks = new int[16];
    private int globalChanges;
    private int[] eventTicks = new int[1024];
    private long[] eventChunks = new long[1024];
    private int events;
    private int[] unloadTicks = new int[64];
    private long[] unloadChunks = new long[64];
    private int unloads;
    private boolean reliable = true;

    public static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }

    void addBoundary(int tick, long position, boolean rebuildsAll) {
        boundaries.add(new Boundary(tick, position, rebuildsAll));
    }

    /** A packet changing state a dimension change keeps: the weather or the scoreboard. */
    void addGlobalChange(int tick) {
        if (globalChanges == globalChangeTicks.length) {
            globalChangeTicks = Arrays.copyOf(globalChangeTicks, globalChanges * 2);
        }
        globalChangeTicks[globalChanges++] = tick;
    }

    /** A record with chunk data for these chunks. Null for one that could not be read: see {@link #reliable}. */
    void addChunkData(int tick, long[] chunks) {
        if (chunks == null) {
            reliable = false;
            return;
        }
        for (long chunk : chunks) {
            if (events == eventTicks.length) {
                eventTicks = Arrays.copyOf(eventTicks, events * 2);
                eventChunks = Arrays.copyOf(eventChunks, events * 2);
            }
            eventTicks[events] = tick;
            eventChunks[events++] = chunk;
        }
        addSharedRecord(tick, chunks);
    }

    /** A record that unloads this chunk. */
    void addChunkUnload(int tick, long chunk) {
        if (unloads == unloadTicks.length) {
            unloadTicks = Arrays.copyOf(unloadTicks, unloads * 2);
            unloadChunks = Arrays.copyOf(unloadChunks, unloads * 2);
        }
        unloadTicks[unloads] = tick;
        unloadChunks[unloads++] = chunk;
    }

    /** A record delivered as a whole that can affect these chunks. */
    void addSharedRecord(int tick, long[] chunks) {
        if (chunks == null) reliable = false;
        else if (chunks.length > 1) sharedRecords.add(new SharedRecord(tick, chunks));
    }

    /** The last record at or before this tick that replaced the world, or null. */
    public Boundary boundaryAt(int tick) {
        Boundary found = null;
        for (Boundary b : boundaries) {
            if (b.tick > tick) break;
            found = b;
        }
        return found;
    }

    /**
     * Plans going back from one tick to an earlier one while keeping the world, or returns null when that cannot give
     * the same result as loading the earlier tick from scratch.
     *
     * @param changed the chunks whose blocks changed after the earlier tick
     */
    public Rewind planRewind(int from, int to, Collection<Long> changed) {
        if (!reliable) return null;
        Boundary boundary = boundaryAt(to);
        if (boundary == null || boundary != boundaryAt(from)) return null;
        if (!boundary.rebuildsAll && hasGlobalChange(to, from)) return null;
        Set<Long> chunks = new HashSet<>();
        for (long key : changed) {
            // a changed block also changes the light around it, which reaches into the neighbouring chunks
            int x = chunkX(key), z = chunkZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) chunks.add(chunkKey(x + dx, z + dz));
            }
        }
        for (int i = firstEventAfter(to); i < events && eventTicks[i] <= from; i++) chunks.add(eventChunks[i]);
        // An unloading chunk takes its entities with it, and they only are in the chunk if it is restored as well.
        for (int i = 0; i < unloads && unloadTicks[i] <= to; i++) {
            if (unloadTicks[i] >= boundary.tick) chunks.add(unloadChunks[i]);
        }
        includeSharedRecords(chunks, boundary.tick, to);
        return new Rewind(boundary, chunks);
    }

    private boolean hasGlobalChange(int after, int upTo) {
        for (int i = 0; i < globalChanges; i++) {
            if (globalChangeTicks[i] > after && globalChangeTicks[i] <= upTo) return true;
        }
        return false;
    }

    private int firstEventAfter(int tick) {
        int low = 0, high = events;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (eventTicks[middle] > tick) high = middle;
            else low = middle + 1;
        }
        return low;
    }

    /**
     * A record is delivered as a whole, so restoring one of its chunks restores the others as well, and those must
     * have been thrown away too or they would go back to an older state.
     */
    private void includeSharedRecords(Set<Long> chunks, int fromTick, int toTick) {
        boolean grown = !chunks.isEmpty();
        while (grown) {
            grown = false;
            for (SharedRecord record : sharedRecords) {
                if (record.tick < fromTick || record.tick > toTick) continue;
                boolean any = false, all = true;
                for (long chunk : record.chunks) {
                    if (chunks.contains(chunk)) any = true;
                    else all = false;
                }
                if (any && !all) {
                    for (long chunk : record.chunks) chunks.add(chunk);
                    grown = true;
                }
            }
        }
    }
}
