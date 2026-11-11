package com.flurben.flashbacknh.export;

import com.flurben.flashbacknh.FlashbackNH;

/**
 * Where the time of one export goes, logged when it ends. The client thread's time is split into rendering (the game's
 * frame up to the end of the render tick), readback (pixels from the GPU), handing frames to the encoder (waiting
 * when the encoder is behind), the rest of the frame (buffer swap and anything the game does outside the render tick)
 * and frames that were rendered but not saved (warm-up, chunk waits, extra frames).
 */
final class ExportStats {

    private final long started = System.nanoTime();
    private long frameStart = -1, lastEnd = -1;

    long render, readback, encode, other, skipped;
    int savedFrames, warmupFrames, chunkWaitFrames, extraFrames;
    long chunkWait;
    /** Drawing the progress box, part of {@link #other} for saved frames. */
    long progress;
    long finish = -1;

    /** Start of the game's render tick. */
    void frameStart() {
        long now = System.nanoTime();
        if (lastEnd >= 0) other += now - lastEnd;
        frameStart = now;
    }

    /** End of the render tick, before the exporter's own work. Returns the time of the call. */
    long frameRendered() {
        long now = System.nanoTime();
        if (frameStart >= 0) render += now - frameStart;
        return now;
    }

    /** End of the exporter's work for a frame that was not saved. */
    void skipped(long renderedAt, int kind) {
        long now = System.nanoTime();
        long frame = frameStart >= 0 ? now - frameStart : now - renderedAt;
        // a skipped frame's render time does not count as rendering of saved frames
        if (frameStart >= 0) render -= renderedAt - frameStart;
        skipped += frame;
        if (kind == 0) warmupFrames++;
        else if (kind == 1) {
            chunkWaitFrames++;
            chunkWait += frame;
        } else extraFrames++;
        lastEnd = now;
        frameStart = -1;
    }

    void saved(long renderedAt, long readDone, long encodeDone) {
        readback += readDone - renderedAt;
        encode += encodeDone - readDone;
        savedFrames++;
        lastEnd = encodeDone;
        frameStart = -1;
    }

    void log(String result, String encoderStats) {
        double total = (System.nanoTime() - started) / 1e9;
        double frames = Math.max(1, savedFrames);
        FlashbackNH.LOG.info(
            "Export {}: {} frames in {} s ({} fps){}",
            result,
            savedFrames,
            fmt(total),
            fmt(savedFrames / Math.max(1e-9, total - Math.max(0, finish) / 1e9)),
            finish >= 0 ? ", finishing the file took " + fmt(finish / 1e9) + " s" : "");
        FlashbackNH.LOG.info(
            "Export time per saved frame (ms): render {}, readback {}, encoder hand-off {}, swap/other {} (progress box {})",
            ms(render / frames),
            ms(readback / frames),
            ms(encode / frames),
            ms(other / frames),
            ms(progress / frames));
        FlashbackNH.LOG.info(
            "Export frames not saved: {} warm-up, {} waiting for chunks ({} s), {} extra; {} s in total",
            warmupFrames,
            chunkWaitFrames,
            fmt(chunkWait / 1e9),
            extraFrames,
            fmt(skipped / 1e9));
        if (encoderStats != null) FlashbackNH.LOG.info("Export encoder: {}", encoderStats);
    }

    static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    static String ms(double nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1e6);
    }
}
