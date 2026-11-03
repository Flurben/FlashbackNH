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

        MP4("mp4", "H.264 (MP4)"),
        MKV("mkv", "H.264 (MKV)"),
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

    private static final byte[] POISON = new byte[0];

    private final Process process;
    private final File output;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(6);
    private final Thread writer;
    private final StringBuilder log = new StringBuilder();
    private volatile String error;
    private byte[] scratch;

    public FFmpegEncoder(String ffmpeg, Format format, File output, int width, int height, int fps, int crf,
        String preset) throws IOException {
        this.output = output;
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("warning");
        cmd.add("-f");
        cmd.add("rawvideo");
        cmd.add("-pix_fmt");
        cmd.add("rgba");
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
                cmd.add("-c:v");
                cmd.add("libx264");
                cmd.add("-preset");
                cmd.add(preset == null || preset.isEmpty() ? "medium" : preset);
                cmd.add("-crf");
                cmd.add(String.valueOf(crf));
                cmd.add("-pix_fmt");
                cmd.add("yuv420p");
                if (format == Format.MP4) {
                    cmd.add("-movflags");
                    cmd.add("+faststart");
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
                    byte[] frame = queue.take();
                    if (frame == POISON) break;
                    out.write(frame);
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
    public void encode(ByteBuffer rgba, int width, int height) throws IOException {
        if (error != null) throw new IOException(error);
        int size = width * height * 4;
        byte[] frame = new byte[size];
        rgba.position(0);
        rgba.get(frame, 0, size);
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
    public String getError() {
        return error;
    }

    @SuppressWarnings("unused")
    private byte[] scratch(int size) {
        if (scratch == null || scratch.length != size) scratch = new byte[size];
        return scratch;
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
