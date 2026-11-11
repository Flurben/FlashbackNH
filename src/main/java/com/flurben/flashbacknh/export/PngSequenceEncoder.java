package com.flurben.flashbacknh.export;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.flurben.flashbacknh.FlashbackNH;

/** Writes each frame as a numbered PNG file. Needs no external tools. */
public final class PngSequenceEncoder implements FrameEncoder {

    private final File dir;
    private final ExecutorService pool;
    private static final int IN_FLIGHT = 8;
    /** Frame buffers no writer is using; there are never more than {@link #IN_FLIGHT}, made as they are needed. */
    private final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(IN_FLIGHT);
    private int buffers;
    /** Each writer thread's image, reused for every frame it writes. */
    private final ThreadLocal<BufferedImage> images = new ThreadLocal<>();
    private volatile String error;
    private final int compression;
    private int index;

    /**
     * @param compression zlib level, 0 (none, fastest, largest) to 9 (smallest, slowest), or -1 for the Java
     *                    default
     */
    public PngSequenceEncoder(File dir, int compression) throws IOException {
        this.dir = dir;
        this.compression = compression;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Unable to create " + dir);
        // compressing PNGs is the slow part: all cores but one, which the game renders on
        int threads = Math.max(
            1,
            Runtime.getRuntime()
                .availableProcessors() - 1);
        this.pool = new ThreadPoolExecutor(threads, threads, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "FlashbackNH PNG writer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void encode(ByteBuffer rgb, int width, int height) throws IOException {
        if (error != null) throw new IOException(error);
        final int size = width * height * BYTES_PER_PIXEL;
        final byte[] data;
        try {
            data = buffer(size);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
        rgb.position(0);
        rgb.get(data, 0, size);
        final File file = new File(dir, String.format("frame_%06d.png", ++index));
        pool.execute(() -> {
            try {
                BufferedImage img = images.get();
                if (img == null || img.getWidth() != width || img.getHeight() != height) {
                    img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                    images.set(img);
                }
                int[] out = ((DataBufferInt) img.getRaster()
                    .getDataBuffer()).getData();
                for (int y = 0; y < height; y++) {
                    int src = (height - 1 - y) * width * 3;
                    int dst = y * width;
                    for (int x = 0; x < width; x++) {
                        int i = src + x * 3;
                        out[dst + x] = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
                    }
                }
                free.offer(data);
                write(img, file);
            } catch (Throwable t) {
                free.offer(data);
                if (error == null) error = "Unable to write " + file + ": " + t.getMessage();
                FlashbackNH.LOG.error("PNG export failed", t);
            }
        });
    }

    private void write(BufferedImage img, File file) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png")
            .next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file)) {
            if (out == null) throw new IOException("Unable to open " + file);
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (compression >= 0 && param.canWriteCompressed()) {
                // the PNG writer maps quality q to zlib level 9 * (1 - q)
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(1f - Math.min(9, compression) / 9f);
            }
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private byte[] buffer(int size) throws IOException, InterruptedException {
        while (true) {
            byte[] b = free.poll();
            if (b == null && buffers < IN_FLIGHT) {
                buffers++;
                return new byte[size];
            }
            if (b == null) b = free.poll(200, TimeUnit.MILLISECONDS);
            if (b != null && b.length == size) return b;
            if (b != null) buffers--;
            if (error != null) throw new IOException(error);
        }
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
