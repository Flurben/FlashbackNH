package com.flurben.flashbacknh.export;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import com.flurben.flashbacknh.FlashbackNH;

/** Writes each frame as a numbered PNG file. Needs no external tools. */
public final class PngSequenceEncoder implements FrameEncoder {

    private final File dir;
    private final ExecutorService pool;
    private final Semaphore inFlight = new Semaphore(8);
    private volatile String error;
    private int index;

    public PngSequenceEncoder(File dir) throws IOException {
        this.dir = dir;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Unable to create " + dir);
        int threads = Math.max(
            1,
            Math.min(
                4,
                Runtime.getRuntime()
                    .availableProcessors() / 2));
        this.pool = new ThreadPoolExecutor(threads, threads, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "FlashbackNH PNG writer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void encode(ByteBuffer rgba, int width, int height) throws IOException {
        if (error != null) throw new IOException(error);
        final int size = width * height * 4;
        final byte[] data = new byte[size];
        rgba.position(0);
        rgba.get(data, 0, size);
        final File file = new File(dir, String.format("frame_%06d.png", ++index));
        try {
            inFlight.acquire();
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
        pool.execute(() -> {
            try {
                BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                int[] row = new int[width];
                for (int y = 0; y < height; y++) {
                    int src = (height - 1 - y) * width * 4;
                    for (int x = 0; x < width; x++) {
                        int i = src + x * 4;
                        row[x] = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
                    }
                    img.setRGB(0, y, width, 1, row, 0, width);
                }
                ImageIO.write(img, "png", file);
            } catch (Throwable t) {
                if (error == null) error = "Unable to write " + file + ": " + t.getMessage();
                FlashbackNH.LOG.error("PNG export failed", t);
            } finally {
                inFlight.release();
            }
        });
    }

    @Override
    public void close() throws IOException {
        pool.shutdown();
        try {
            pool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
        if (error != null) throw new IOException(error);
    }

    @Override
    public void abort() {
        pool.shutdownNow();
    }

    @Override
    public String getError() {
        return error;
    }
}
