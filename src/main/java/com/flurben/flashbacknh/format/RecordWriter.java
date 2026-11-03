package com.flurben.flashbacknh.format;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Append-only, thread-safe writer for the replay tick stream. Packets arrive on the netty thread while ticks and
 * synthetic records are written from the client thread, so every write is synchronized to keep records intact and
 * ordered.
 */
public final class RecordWriter implements AutoCloseable {

    private final OutputStream out;
    private final File file;
    private int ticks;
    private long bytes;
    private boolean closed;

    public RecordWriter(File file, boolean append) throws IOException {
        this.file = file;
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

    public synchronized void writeRecord(byte type, byte[] data) throws IOException {
        writeRecord(type, data, 0, data == null ? 0 : data.length);
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
