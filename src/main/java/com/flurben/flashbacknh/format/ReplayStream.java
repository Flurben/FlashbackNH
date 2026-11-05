package com.flurben.flashbacknh.format;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Random access reader for an extracted tick stream. On open the whole file is scanned once to build an index of where
 * each tick starts, which makes seeking to any tick O(1) for the file position, and a {@link ReplayIndex}.
 */
public final class ReplayStream implements AutoCloseable {

    public static final class Record {

        public final byte type;
        public final byte[] data;
        /** Where this record is in the stream. A repeated record has the position of the repeat. */
        public final long position;

        Record(byte type, byte[] data, long position) {
            this.type = type;
            this.data = data;
            this.position = position;
        }
    }

    public interface RecordConsumer {

        void accept(Record record) throws IOException;
    }

    private final RandomAccessFile raf;
    private final long[] tickStarts;
    private final long end;
    private final ReplayIndex index;
    private final byte[] buffer = new byte[1 << 16];
    private long bufferStart = -1;
    private int bufferLen = 0;

    private ReplayStream(RandomAccessFile raf, long[] tickStarts, long end, ReplayIndex index) {
        this.raf = raf;
        this.tickStarts = tickStarts;
        this.end = end;
        this.index = index;
    }

    public static ReplayStream open(File file) throws IOException {
        long[] starts = new long[1024];
        int count = 0;
        long validEnd;
        Indexer indexer = new Indexer();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            if (in.readInt() != ReplayFormat.MAGIC) throw new IOException("Not a FlashbackNH stream");
            int version = in.readInt();
            if (version > ReplayFormat.VERSION) {
                throw new IOException(
                    "Replay stream version " + version + " is newer than supported " + ReplayFormat.VERSION);
            }
            long pos = 8;
            starts[count++] = pos;
            validEnd = pos;
            long fileLen = file.length();
            int[] varLen = new int[1];
            while (pos < fileLen) {
                int type = in.read();
                if (type < 0) break;
                int len;
                try {
                    len = readVarInt(in, varLen);
                } catch (EOFException e) {
                    break;
                }
                long recordEnd = pos + 1 + varLen[0] + len;
                if (len < 0 || recordEnd > fileLen) break; // truncated record
                indexer.record(in, type, len, count - 1, pos);
                pos = recordEnd;
                validEnd = pos;
                if (type == ReplayFormat.REC_TICK) {
                    if (count == starts.length) starts = Arrays.copyOf(starts, count * 2);
                    starts[count++] = pos;
                }
            }
        }
        // drop a trailing empty tick
        if (count > 1 && starts[count - 1] >= validEnd) count--;
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        return new ReplayStream(raf, Arrays.copyOf(starts, count), validEnd, indexer.index);
    }

    public ReplayIndex getIndex() {
        return index;
    }

    /** Number of ticks in the stream. Valid tick indices are 0 .. getTickCount()-1. */
    public int getTickCount() {
        return tickStarts.length;
    }

    public synchronized void readTick(int tick, RecordConsumer consumer) throws IOException {
        if (tick < 0 || tick >= tickStarts.length) return;
        readTickFrom(tick, tickStarts[tick], consumer);
    }

    /** Reads the rest of a tick, starting at the record at the given position. */
    public synchronized void readTickFrom(int tick, long from, RecordConsumer consumer) throws IOException {
        if (tick < 0 || tick >= tickStarts.length) return;
        long pos = from;
        long stop = tick + 1 < tickStarts.length ? tickStarts[tick + 1] : end;
        while (pos < stop) {
            long start = pos;
            int type = byteAt(pos++);
            int len = 0;
            int shift = 0;
            while (true) {
                int b = byteAt(pos++);
                len |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) throw new IOException("VarInt too big");
            }
            if (type == ReplayFormat.REC_TICK) {
                pos += len;
                continue;
            }
            byte[] data = new byte[len];
            read(pos, data);
            long payloadStart = pos;
            pos += len;
            if (type == ReplayFormat.REC_REPEAT) {
                consumer.accept(readRepeated(data, payloadStart, start));
            } else {
                consumer.accept(new Record((byte) type, data, start));
            }
        }
    }

    private Record readRepeated(byte[] reference, long from, long position) throws IOException {
        long pos = ByteBuffer.wrap(reference)
            .getLong();
        if (pos < 8 || pos >= from) throw new IOException("Bad repeat reference " + pos + " at " + from);
        int type = byteAt(pos++);
        int len = 0;
        int shift = 0;
        while (true) {
            int b = byteAt(pos++);
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt too big");
        }
        byte[] data = new byte[len];
        read(pos, data);
        return new Record((byte) type, data, position);
    }

    private int byteAt(long pos) throws IOException {
        if (pos < bufferStart || pos >= bufferStart + bufferLen) fill(pos);
        return buffer[(int) (pos - bufferStart)] & 0xFF;
    }

    private void read(long pos, byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            if (pos < bufferStart || pos >= bufferStart + bufferLen) {
                if (dst.length - off >= buffer.length) {
                    raf.seek(pos);
                    raf.readFully(dst, off, dst.length - off);
                    return;
                }
                fill(pos);
            }
            int avail = (int) (bufferStart + bufferLen - pos);
            int n = Math.min(avail, dst.length - off);
            System.arraycopy(buffer, (int) (pos - bufferStart), dst, off, n);
            off += n;
            pos += n;
        }
    }

    private void fill(long pos) throws IOException {
        raf.seek(pos);
        int n = raf.read(buffer, 0, buffer.length);
        if (n <= 0) throw new EOFException();
        bufferStart = pos;
        bufferLen = n;
    }

    @Override
    public synchronized void close() throws IOException {
        raf.close();
    }

    public static int readVarInt(InputStream in, int[] bytesRead) throws IOException {
        int value = 0;
        int shift = 0;
        int n = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            n++;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt too big");
        }
        if (bytesRead != null) bytesRead[0] = n;
        return value;
    }

    /** Builds the {@link ReplayIndex} from the records going by, reading only those it needs. */
    private static final class Indexer {

        private static final int JOIN_GAME = 0x01;
        private static final int RESPAWN = 0x07;
        private static final int CHANGE_GAME_STATE = 0x2B;
        private static final int SCOREBOARD_FIRST = 0x3B;
        private static final int SCOREBOARD_LAST = 0x3E;

        final ReplayIndex index = new ReplayIndex();
        private final Map<Long, long[]> chunkRecords = new HashMap<>();
        private byte[] data = new byte[1 << 16];
        private int dimension = Integer.MIN_VALUE;

        void record(InputStream in, int type, int length, int tick, long position) throws IOException {
            if (type == ReplayFormat.REC_CLEAR_WORLD) {
                index.addBoundary(tick, position, true);
            } else if (type == ReplayFormat.REC_REPEAT && length == Long.BYTES) {
                readData(in, length);
                long[] chunks = chunkRecords.get(
                    ByteBuffer.wrap(data)
                        .getLong());
                if (chunks != null) index.addChunkData(tick, chunks);
                return;
            } else if (type == ReplayFormat.REC_PACKET && length > 0) {
                packet(in, length, tick, position);
                return;
            }
            skipFully(in, length);
        }

        private void packet(InputStream in, int length, int tick, long position) throws IOException {
            int id = in.read();
            if (id < 0) throw new EOFException();
            if (id == JOIN_GAME || id == RESPAWN || PacketChunks.hasChunkData(id) || id == PacketChunks.EXPLOSION) {
                readRest(in, id, length);
                if (id == JOIN_GAME) {
                    // after the id: int entity id, byte game mode, byte dimension
                    if (length >= 7) dimension = data[6];
                    index.addBoundary(tick, position, true);
                } else if (id == RESPAWN) {
                    // respawning in the same dimension leaves the world as it is
                    int next = length >= 5 ? ByteBuffer.wrap(data, 1, 4)
                        .getInt() : dimension;
                    if (next != dimension) index.addBoundary(tick, position, false);
                    dimension = next;
                } else if (id == PacketChunks.EXPLOSION) {
                    index.addSharedRecord(tick, PacketChunks.of(Arrays.copyOf(data, length)));
                } else {
                    long[] chunks = PacketChunks.of(Arrays.copyOf(data, length));
                    index.addChunkData(tick, chunks);
                    if (chunks != null) chunkRecords.put(position, chunks);
                    if (chunks != null && chunks.length == 1 && PacketChunks.isUnload(Arrays.copyOf(data, length))) {
                        index.addChunkUnload(tick, chunks[0]);
                    }
                }
                return;
            }
            if (id == CHANGE_GAME_STATE || (id >= SCOREBOARD_FIRST && id <= SCOREBOARD_LAST)) {
                index.addGlobalChange(tick);
            }
            skipFully(in, length - 1);
        }

        private void readRest(InputStream in, int first, int length) throws IOException {
            if (data.length < length) data = new byte[length * 2];
            data[0] = (byte) first;
            readFully(in, 1, length - 1);
        }

        private void readData(InputStream in, int length) throws IOException {
            if (data.length < length) data = new byte[length * 2];
            readFully(in, 0, length);
        }

        private void readFully(InputStream in, int offset, int length) throws IOException {
            int done = 0;
            while (done < length) {
                int n = in.read(data, offset + done, length - done);
                if (n < 0) throw new EOFException();
                done += n;
            }
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException();
                s = 1;
            }
            n -= s;
        }
    }
}
