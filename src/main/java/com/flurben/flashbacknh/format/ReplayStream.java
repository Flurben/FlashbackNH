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

/**
 * Random access reader for an extracted tick stream. On open the whole file is scanned once to build an index of where
 * each tick starts, which makes seeking to any tick O(1) for the file position.
 */
public final class ReplayStream implements AutoCloseable {

    public static final class Record {

        public final byte type;
        public final byte[] data;

        Record(byte type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    public interface RecordConsumer {

        void accept(Record record) throws IOException;
    }

    private final RandomAccessFile raf;
    private final long[] tickStarts;
    private final long end;
    private final byte[] buffer = new byte[1 << 16];
    private long bufferStart = -1;
    private int bufferLen = 0;

    private ReplayStream(RandomAccessFile raf, long[] tickStarts, long end) {
        this.raf = raf;
        this.tickStarts = tickStarts;
        this.end = end;
    }

    public static ReplayStream open(File file) throws IOException {
        long[] starts = new long[1024];
        int count = 0;
        long validEnd;
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
                skipFully(in, len);
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
        return new ReplayStream(raf, Arrays.copyOf(starts, count), validEnd);
    }

    /** Number of ticks in the stream. Valid tick indices are 0 .. getTickCount()-1. */
    public int getTickCount() {
        return tickStarts.length;
    }

    public synchronized void readTick(int tick, RecordConsumer consumer) throws IOException {
        if (tick < 0 || tick >= tickStarts.length) return;
        long pos = tickStarts[tick];
        long stop = tick + 1 < tickStarts.length ? tickStarts[tick + 1] : end;
        while (pos < stop) {
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
                consumer.accept(readRepeated(data, payloadStart));
            } else {
                consumer.accept(new Record((byte) type, data));
            }
        }
    }

    private Record readRepeated(byte[] reference, long from) throws IOException {
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
        return new Record((byte) type, data);
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
