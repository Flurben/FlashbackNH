package com.flurben.flashbacknh.export;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.flurben.flashbacknh.FlashbackNH;

/** Pipes raw frames into an external ffmpeg process. */
public final class FFmpegEncoder implements FrameEncoder {

    public enum Format {

        MP4("mp4", "MP4"),
        MKV("mkv", "MKV"),
        WEBM("webm", "VP9 (WebM)"),
        MOV_PRORES("mov", "ProRes 422 HQ (MOV)"),
        GIF("gif", "GIF"),
        PNG("png", "PNG sequence");

        public final String extension;
        public final String displayName;

        Format(String extension, String displayName) {
            this.extension = extension;
            this.displayName = displayName;
        }

        public static Format parse(String s) {
            try {
                return valueOf(s.toUpperCase());
            } catch (Exception e) {
                return MP4;
            }
        }
    }

    /** Where an MP4 keeps its index, which decides how the file is finished. */
    public enum Mp4Layout {

        /** Index at the end. Nothing is rewritten when the export ends, and every player and editor reads it. */
        STANDARD("Standard", ""),
        /**
         * Index in small pieces along the video. Nothing is rewritten, and the file stays playable if the game
         * crashes during the export, but some older editors read such files badly.
         */
        FRAGMENTED("Fragmented", "+frag_keyframe+empty_moov+default_base_moof"),
        /**
         * Index at the start, for playing in a browser before the whole file has downloaded. ffmpeg rewrites the whole
         * file once to get there, which takes a while for long exports.
         */
        FASTSTART("Fast start (web)", "+faststart");

        public final String displayName;
        final String movflags;

        Mp4Layout(String displayName, String movflags) {
            this.displayName = displayName;
            this.movflags = movflags;
        }

        public static Mp4Layout parse(String s) {
            try {
                return valueOf(s.toUpperCase());
            } catch (Exception e) {
                return STANDARD;
            }
        }
    }

    private static final byte[] POISON = new byte[0];

    private final Process process;
    private final File output;
    private static final int QUEUED = 6;
    /** Frames waiting for ffmpeg. */
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUED);
    /**
     * Frame buffers ffmpeg is done with. All buffers of an export are made once: the queued ones, the one being
     * written and the one being filled.
     */
    private final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(QUEUED + 2);
    private int buffers;
    private final Thread writer;
    private final StringBuilder log = new StringBuilder();
    private volatile String error;
    private volatile long idleNanos, writeNanos;
    private volatile int written;

    public FFmpegEncoder(ExportSettings s) throws IOException {
        String ffmpeg = s.ffmpegPath;
        Format format = s.format;
        int width = s.width, height = s.height, fps = s.fps, crf = s.crf;
        String preset = s.preset;
        this.output = s.output;
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("warning");
        cmd.add("-f");
        cmd.add("rawvideo");
        cmd.add("-pix_fmt");
        cmd.add("rgb24");
        cmd.add("-s");
        cmd.add(width + "x" + height);
        cmd.add("-r");
        cmd.add(String.valueOf(fps));
        cmd.add("-i");
        cmd.add("-");
        switch (format) {
            case WEBM:
                cmd.add("-vf");
                cmd.add("vflip");
                cmd.add("-c:v");
                cmd.add("libvpx-vp9");
                cmd.add("-crf");
                cmd.add(String.valueOf(crf));
                cmd.add("-b:v");
                cmd.add("0");
                cmd.add("-row-mt");
                cmd.add("1");
                cmd.add("-pix_fmt");
                cmd.add("yuv420p");
                break;
            case MOV_PRORES:
                cmd.add("-vf");
                cmd.add("vflip");
                cmd.add("-c:v");
                cmd.add("prores_ks");
                cmd.add("-profile:v");
                cmd.add("3");
                cmd.add("-pix_fmt");
                cmd.add("yuv422p10le");
                break;
            case GIF:
                cmd.add("-vf");
                cmd.add(
                    "vflip,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=3");
                cmd.add("-loop");
                cmd.add("0");
                break;
            default:
                cmd.add("-vf");
                cmd.add("vflip");
                VideoEncoders.Choice codec = s.resolvedEncoder != null ? s.resolvedEncoder
                    : VideoEncoders.Choice.LIBX264;
                cmd.addAll(VideoEncoders.args(codec, crf, preset, s.encoderThreads));
                // the tag Apple players and editors want for HEVC in MP4
                if (codec == VideoEncoders.Choice.HEVC_NVENC && format == Format.MP4) {
                    cmd.add("-tag:v");
                    cmd.add("hvc1");
                }
                if (format == Format.MP4 && !s.mp4Layout.movflags.isEmpty()) {
                    cmd.add("-movflags");
                    cmd.add(s.mp4Layout.movflags);
                }
                break;
        }
        cmd.add(output.getAbsolutePath());
        FlashbackNH.LOG.info("Starting ffmpeg: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            this.process = pb.start();
        } catch (IOException e) {
            throw new IOException(
                "Unable to start ffmpeg ('" + ffmpeg + "'). Install ffmpeg or set its path in the FlashbackNH config.",
                e);
        }
        Thread logReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    FlashbackNH.LOG.info("[ffmpeg] {}", line);
                    synchronized (log) {
                        if (log.length() < 8000) log.append(line)
                            .append('\n');
                    }
                }
            } catch (IOException ignored) {}
        }, "FlashbackNH ffmpeg log");
        logReader.setDaemon(true);
        logReader.start();

        this.writer = new Thread(() -> {
            try (OutputStream out = process.getOutputStream()) {
                while (true) {
                    long t0 = System.nanoTime();
                    byte[] frame = queue.take();
                    long t1 = System.nanoTime();
                    if (frame == POISON) break;
                    out.write(frame);
                    free.offer(frame);
                    idleNanos += t1 - t0;
                    writeNanos += System.nanoTime() - t1;
                    written++;
                }
            } catch (Throwable t) {
                if (error == null) error = "ffmpeg stopped accepting frames: " + t.getMessage() + "\n" + lastLog();
            }
        }, "FlashbackNH ffmpeg writer");
        writer.setDaemon(true);
        writer.start();
    }

    private String lastLog() {
        synchronized (log) {
            String s = log.toString()
                .trim();
            return s.length() > 600 ? s.substring(s.length() - 600) : s;
        }
    }

    @Override
    public void encode(ByteBuffer rgb, int width, int height) throws IOException {
        if (error != null) throw new IOException(error);
        int size = width * height * BYTES_PER_PIXEL;
        byte[] frame;
        try {
            frame = buffer(size);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
        rgb.position(0);
        rgb.get(frame, 0, size);
        try {
            while (!queue.offer(frame, 200, TimeUnit.MILLISECONDS)) {
                if (error != null) throw new IOException(error);
                if (!process.isAlive()) throw new IOException("ffmpeg exited unexpectedly\n" + lastLog());
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            queue.put(POISON);
            writer.join();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroy();
                throw new IOException("ffmpeg did not finish");
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg failed with exit code " + process.exitValue() + "\n" + lastLog());
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
        if (error != null) throw new IOException(error);
    }

    @Override
    public void abort() {
        queue.clear();
        queue.offer(POISON);
        process.destroy();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        if (output.exists() && !output.delete()) output.deleteOnExit();
    }

    @Override
    public String stats() {
        double n = Math.max(1, written);
        return String.format(
            java.util.Locale.ROOT,
            "%d frames piped; per frame %.2f ms writing to ffmpeg (ffmpeg busy), %.2f ms waiting for frames",
            written,
            writeNanos / n / 1e6,
            idleNanos / n / 1e6);
    }

    @Override
    public String getError() {
        return error;
    }

    /**
     * A free frame buffer: a new one while there are fewer than the queue needs, else the next one ffmpeg is done with.
     */
    private byte[] buffer(int size) throws IOException, InterruptedException {
        while (true) {
            byte[] b = free.poll();
            if (b == null && buffers < QUEUED + 2) {
                buffers++;
                return new byte[size];
            }
            if (b == null) b = free.poll(200, TimeUnit.MILLISECONDS);
            if (b != null && b.length == size) return b;
            if (b != null) buffers--;
            if (error != null) throw new IOException(error);
            if (!process.isAlive()) throw new IOException("ffmpeg exited unexpectedly\n" + lastLog());
        }
    }

    /** Checks whether ffmpeg can be started. Returns its version line or null. */
    public static String probe(String ffmpeg) {
        try {
            Process p = new ProcessBuilder(ffmpeg, "-version").redirectErrorStream(true)
                .start();
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String first = r.readLine();
                while (r.readLine() != null) {
                    // drain
                }
                p.waitFor(5, TimeUnit.SECONDS);
                return first;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
