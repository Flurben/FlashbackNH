package com.flurben.flashbacknh.recording;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.format.RecordWriter;
import com.flurben.flashbacknh.format.ReplayFormat;
import com.flurben.flashbacknh.util.Notifications;

/**
 * Writes records to disk on its own thread, in the order they were added. A record may still be in the making: the
 * writer waits for it while everything added later stays queued, which lets the game carry on and keep recording while
 * a world snapshot is compressed in the background.
 */
final class RecordQueue {

    private static final long FLUSH_INTERVAL_MS = 1000;
    private static final Object END = new Object();
    private static final WorldSnapshot.Rec TICK = new WorldSnapshot.Rec(ReplayFormat.REC_TICK, null);

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final RecordWriter writer;
    private final Thread thread;
    private volatile int ticks;
    private int failures;

    RecordQueue(RecordWriter writer) {
        this.writer = writer;
        this.thread = new Thread(this::run, "FlashbackNH recording writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Number of ticks added so far, written or not. */
    int getTicks() {
        return ticks;
    }

    void add(byte type, byte[] data) {
        queue.add(new WorldSnapshot.Rec(type, data));
    }

    void addAll(List<WorldSnapshot.Rec> records) {
        queue.addAll(records);
    }

    /** Runs on the writer thread once everything added before it is on its way to disk. */
    void addAction(Runnable action) {
        queue.add(action);
    }

    /** Only one thread at a time may call this, the increment is not atomic. */
    void addTick() {
        ticks++;
        queue.add(TICK);
    }

    /** Waits for everything to be written, which takes as long as an unfinished snapshot needs, and closes the file. */
    void close() {
        queue.add(END);
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        try {
            writer.close();
        } catch (IOException e) {
            FlashbackNH.LOG.error("Failed to close recording", e);
        }
    }

    private void run() {
        long lastFlush = System.currentTimeMillis();
        while (true) {
            try {
                Object item = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (item == END) return;
                if (item != null) handle(item);
                long now = System.currentTimeMillis();
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    lastFlush = now;
                    writer.flush();
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                if (failures++ < 5) {
                    FlashbackNH.LOG.error("Failed to write recording", t);
                    if (failures == 1) Notifications.show("FlashbackNH", "Failed to write recording: " + t);
                }
            }
        }
    }

    private void handle(Object item) throws Exception {
        if (item instanceof Runnable) {
            ((Runnable) item).run();
            return;
        }
        WorldSnapshot.Rec rec = (WorldSnapshot.Rec) item;
        if (rec == TICK) {
            writer.writeTick();
        } else if (rec.later != null) {
            for (WorldSnapshot.Rec r : rec.later.get()) writer.writeRecord(r.type, r.data);
        } else {
            writer.writeRecord(rec.type, rec.data);
        }
    }
}
