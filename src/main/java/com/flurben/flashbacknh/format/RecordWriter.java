package com.flurben.flashbacknh.format;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/** Append-only writer for the replay tick stream. Only the recording's writer thread uses it while recording. */
public final class RecordWriter implements AutoCloseable {

    /** Small packets deflate well in the archive anyway, and a reference is not much smaller than they end up. */
    private static final int MIN_REPEAT_SIZE = 512;

    private final OutputStream out;
    private final File file;
    private final Map<ByteBuffer, Long> written = new HashMap<>();
    private final MessageDigest digest;
    private int ticks;
    private long bytes;
    private boolean closed;

    public RecordWriter(File file, boolean append) throws IOException {
        this.file = file;
        try {
            this.digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        boolean writeHeader = !append || !file.exists() || file.length() == 0;
        this.out = new BufferedOutputStream(new FileOutputStream(file, append), 1 << 16);
        if (writeHeader) {
            DataOutputStream dos = new DataOutputStream(out);
            dos.writeInt(ReplayFormat.MAGIC);
            dos.writeInt(ReplayFormat.VERSION);
            bytes = 8;
        } else {
            bytes = file.length();
        }
    }

    public File getFile() {
        return file;
    }

    public synchronized int getTicks() {
        return ticks;
    }

    public synchronized long getBytes() {
        return bytes;
    }

    public synchronized void setTicks(int ticks) {
        this.ticks = ticks;
    }

    /** Packets large enough to matter that were written before become a reference to the earlier copy. */
    public synchronized void writeRecord(byte type, byte[] data) throws IOException {
        if (closed) return;
        if (type == ReplayFormat.REC_PACKET && data != null && data.length >= MIN_REPEAT_SIZE) {
            ByteBuffer key = ByteBuffer.wrap(digest.digest(data));
            Long earlier = written.get(key);
            if (earlier != null) {
                writeRecord(ReplayFormat.REC_REPEAT, encodeLong(earlier), 0, 8);
                return;
            }
            written.put(key, bytes);
        }
        writeRecord(type, data, 0, data == null ? 0 : data.length);
    }

    private static byte[] encodeLong(long value) {
        return ByteBuffer.allocate(8)
            .putLong(value)
            .array();
    }

    public synchronized void writeRecord(byte type, byte[] data, int off, int len) throws IOException {
        if (closed) return;
        out.write(type);
        bytes += 1 + writeVarInt(out, len);
        if (len > 0) {
            out.write(data, off, len);
            bytes += len;
        }
    }

    public synchronized void writeTick() throws IOException {
        if (closed) return;
        out.write(ReplayFormat.REC_TICK);
        out.write(0);
        bytes += 2;
        ticks++;
    }

    public synchronized void flush() throws IOException {
        if (!closed) out.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        out.flush();
        out.close();
    }

    public static int writeVarInt(OutputStream out, int value) throws IOException {
        int n = 0;
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
            n++;
        }
        out.write(value);
        return n + 1;
    }
}
