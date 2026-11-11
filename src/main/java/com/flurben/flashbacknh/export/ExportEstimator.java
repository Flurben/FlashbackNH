package com.flurben.flashbacknh.export;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.recording.RecordingManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Estimates the size of an export and how long it takes.
 *
 * <p>
 * The starting point is FlashbackNH's own measurements: a 10-minute replay with mobs, pistons and a moving camera,
 * exported at 1920x1080 and 60 fps on an i7-13700K with an RTX 4070 Ti SUPER (see the 0.3.0 release notes). Those are
 * scaled to the chosen resolution, frame rate, quality and encoder. After every finished export the real time per
 * frame and bytes per frame are stored for that encoder (export-stats.json), and later estimates for it use those
 * instead, so the estimates learn the user's machine and the kind of replays they make.
 *
 * <p>
 * Size depends mostly on how much moves in the picture, which nothing here can know beforehand, so it comes with a
 * range.
 */
public final class ExportEstimator {

    /** Pixels of the 1080p reference. */
    private static final double REF_PIXELS = 1920 * 1080;

    /** Result of an estimate. */
    public static final class Estimate {

        public long bytes, bytesLow, bytesHigh;
        public double seconds;
        /** Seconds of video (frames / fps). */
        public double videoSeconds;
        public int frames;
        /** The encoder the estimate is for, as shown to the user. */
        public String encoder;
        /** Whether the numbers come from the user's own exports with this encoder. */
        public boolean calibrated;
        /** How many of the user's exports the numbers come from. */
        public int samples;
    }

    /** Learned values for one encoder, normalised to 1080p, 60 fps and CRF 18. */
    private static final class Sample {

        double msPerFrame;
        double bytesPerFrame;
        int count;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();
    private static Map<String, Sample> history;

    private ExportEstimator() {}

    /**
     * @param resolved the encoder an MP4 or MKV export would use (Auto already resolved), or null for other formats
     */
    public static Estimate estimate(ExportSettings s, VideoEncoders.Choice resolved, int frames) {
        Estimate e = new Estimate();
        e.frames = frames;
        e.videoSeconds = frames / (double) Math.max(1, s.fps);
        double px = s.width * (double) s.height / REF_PIXELS;
        String key = key(s, resolved);
        e.encoder = encoderName(s, resolved);

        // time per frame: the game renders and reads frames while the encoder works on earlier ones, so the slower
        // of the two sets the pace. Game: measured 3.1 ms a frame at 1080p, about half of it fixed per frame
        double gameMs = (1.6 + 1.5 * px) * (1 + s.extraFrames * 0.75);
        double encoderMs = encoderMs(s, resolved) * px;
        double frameMs = Math.max(gameMs, encoderMs);

        // bytes per frame at 1080p for this content, then scaled
        double bytes = bytesPerFrame(s, resolved);
        double sizeScale = sizeScale(s, resolved, px);

        Sample learned = history().get(key);
        if (learned != null && learned.count > 0) {
            e.calibrated = true;
            e.samples = learned.count;
            if (s.extraFrames == 0) frameMs = Math.max(learned.msPerFrame * px, gameMs);
            bytes = learned.bytesPerFrame;
        }
        e.seconds = frames * frameMs / 1000.0 + startSeconds(s) + finishSeconds(s, (long) (bytes * sizeScale * frames));
        e.bytes = (long) (bytes * sizeScale * frames);
        double low, high;
        if (s.format == FFmpegEncoder.Format.MOV_PRORES || s.format == FFmpegEncoder.Format.PNG) {
            low = e.calibrated ? 0.85 : 0.7;
            high = e.calibrated ? 1.2 : 1.4;
        } else {
            // a still scene is several times smaller than one where everything moves
            low = e.calibrated ? 0.6 : 0.4;
            high = e.calibrated ? 1.7 : 2.5;
        }
        e.bytesLow = (long) (e.bytes * low);
        e.bytesHigh = (long) (e.bytes * high);
        return e;
    }

    /** Encoder time per frame at 1080p, measured where it says so. */
    private static double encoderMs(ExportSettings s, VideoEncoders.Choice c) {
        switch (s.format) {
            case PNG:
                // 148 fps with 23 writer threads at level 4; other levels from a single-thread benchmark
                return 6.8 * pngLevelSpeed(s.pngCompression)
                    * 24.0
                    / Math.max(
                        2,
                        Runtime.getRuntime()
                            .availableProcessors());
            case MOV_PRORES:
                return 9; // not measured in the game
            case WEBM:
                return 45; // libvpx-vp9 with row-mt; not measured in the game
            case GIF:
                return 6; // not measured in the game
            default:
                break;
        }
        if (c == null) c = VideoEncoders.Choice.LIBX264;
        switch (c) {
            case H264_NVENC:
                return 3.15; // 318 fps
            case HEVC_NVENC:
                return 3.5;
            case H264_QSV:
            case H264_AMF:
            case H264_VIDEOTOOLBOX:
                return 4.5; // not measured in the game
            default:
                // 175-185 fps for medium
                return 5.5 * x264Speed(s.preset);
        }
    }

    /** Bytes per frame at 1080p, 60 fps and CRF 18 (x264 medium: 1.68 GB for 36,000 frames). */
    private static double bytesPerFrame(ExportSettings s, VideoEncoders.Choice c) {
        switch (s.format) {
            case PNG:
                return pngLevelBytes(s.pngCompression);
            case MOV_PRORES:
                return 920_000; // ProRes 422 HQ is about 220 Mbit/s at 1080p30
            case GIF:
                return 250_000; // dithered full-size GIF; a rough guess
            case WEBM:
                return 46_600 * 0.8;
            default:
                break;
        }
        double x264 = 46_600;
        if (c == null) return x264;
        switch (c) {
            case H264_NVENC:
                return x264 * 1.1; // 0.96x over 10 min, 1.3x over the first minute
            case HEVC_NVENC:
                return x264 * 0.97;
            case H264_QSV:
                return x264 * 1.35;
            case H264_AMF:
            case H264_VIDEOTOOLBOX:
                return x264 * 1.3; // not measured
            default:
                return x264;
        }
    }

    /** From the 1080p, 60 fps, CRF 18 reference to these settings. */
    private static double sizeScale(ExportSettings s, VideoEncoders.Choice c, double px) {
        if (s.format == FFmpegEncoder.Format.PNG || s.format == FFmpegEncoder.Format.MOV_PRORES) return px;
        // bigger frames do not grow the file as fast as their pixel count
        double scale = Math.pow(px, 0.85);
        if (s.format == FFmpegEncoder.Format.GIF) return scale;
        // each 6 CRF steps about halves the size
        scale *= Math.pow(2, -(s.crf - 18) / 6.0);
        // fewer frames a second change more between frames
        scale *= Math.pow(60.0 / Math.max(1, s.fps), 0.4);
        boolean x264 = (s.format == FFmpegEncoder.Format.MP4 || s.format == FFmpegEncoder.Format.MKV)
            && (c == null || c == VideoEncoders.Choice.LIBX264);
        if (x264) scale *= x264Size(s.preset);
        return scale;
    }

    private static double startSeconds(ExportSettings s) {
        // reloading the start of the range, warm-up frames and starting ffmpeg
        return 2.0 + s.warmupFrames * 0.004;
    }

    private static double finishSeconds(ExportSettings s, long bytes) {
        if (s.format == FFmpegEncoder.Format.MP4 && s.mp4Layout == FFmpegEncoder.Mp4Layout.FASTSTART) {
            return 0.5 + bytes / 900e6; // 1.7 GB took 1.7 s on an NVMe SSD
        }
        return 0.3;
    }

    private static final String[] X264_PRESETS = { "ultrafast", "superfast", "veryfast", "faster", "fast", "medium",
        "slow", "slower", "veryslow" };
    /** Encoding time against medium; the usual x264 figures, not measured here. */
    private static final double[] X264_SPEED = { 0.2, 0.3, 0.45, 0.7, 0.85, 1, 1.5, 2.6, 5.5 };
    /** Size against medium at the same CRF. */
    private static final double[] X264_SIZE = { 2.0, 1.6, 1.2, 1.06, 1.03, 1, 0.97, 0.95, 0.93 };

    private static double x264Speed(String preset) {
        for (int i = 0; i < X264_PRESETS.length; i++) if (X264_PRESETS[i].equals(preset)) return X264_SPEED[i];
        return 1;
    }

    private static double x264Size(String preset) {
        for (int i = 0; i < X264_PRESETS.length; i++) if (X264_PRESETS[i].equals(preset)) return X264_SIZE[i];
        return 1;
    }

    /** Measured with Java's PNG writer on a 1080p frame of the test replay: KB and ms per level. */
    private static final int[] PNG_KB = { 6079, 864, 843, 836, 828, 813, 798, 792, 789, 787 };
    private static final double[] PNG_MS = { 76.7, 53.4, 62.0, 64, 66.4, 75, 83.4, 100, 120, 140.3 };

    private static double pngLevelBytes(int level) {
        return PNG_KB[level < 0 || level > 9 ? 4 : level] * 1024.0;
    }

    private static double pngLevelSpeed(int level) {
        return PNG_MS[level < 0 || level > 9 ? 4 : level] / PNG_MS[4];
    }

    private static String key(ExportSettings s, VideoEncoders.Choice c) {
        boolean h264 = s.format == FFmpegEncoder.Format.MP4 || s.format == FFmpegEncoder.Format.MKV;
        if (!h264) return s.format.name();
        if (c == null) c = VideoEncoders.Choice.LIBX264;
        return c == VideoEncoders.Choice.LIBX264 ? "libx264" : c.codec;
    }

    private static String encoderName(ExportSettings s, VideoEncoders.Choice c) {
        switch (s.format) {
            case PNG:
                return "PNG (built in)";
            case MOV_PRORES:
                return "ProRes 422 HQ";
            case WEBM:
                return "VP9";
            case GIF:
                return "GIF";
            default:
                if (c == null || c == VideoEncoders.Choice.LIBX264) return "x264 " + s.preset;
                return c.displayName;
        }
    }

    /**
     * Learns from a finished export: its time per saved frame and its size. Exports with extra frames or very short
     * ones are skipped, as their per-frame numbers say little.
     */
    public static synchronized void record(ExportSettings s, double msPerFrame, long bytes, int frames) {
        if (frames < 300 || s.extraFrames != 0 || bytes <= 0 || msPerFrame <= 0) return;
        double px = s.width * (double) s.height / REF_PIXELS;
        VideoEncoders.Choice c = s.resolvedEncoder;
        String key = key(s, c);
        double ms = msPerFrame / px;
        double perFrame = bytes / (double) frames / sizeScale(s, c, px);
        Sample old = history().get(key);
        Sample n = new Sample();
        if (old == null || old.count == 0) {
            n.msPerFrame = ms;
            n.bytesPerFrame = perFrame;
            n.count = 1;
        } else {
            // recent exports count more, as the machine and the kind of replays change
            double w = Math.max(0.25, 1.0 / (old.count + 1));
            n.msPerFrame = old.msPerFrame * (1 - w) + ms * w;
            n.bytesPerFrame = old.bytesPerFrame * (1 - w) + perFrame * w;
            n.count = Math.min(99, old.count + 1);
        }
        history.put(key, n);
        try {
            Files.write(
                file().toPath(),
                GSON.toJson(history)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            FlashbackNH.LOG.debug("Unable to save export statistics", e);
        }
    }

    /** Forgets what was learned, for the estimates to start from the built-in measurements again. */
    public static synchronized void reset() {
        history = new HashMap<>();
        if (file().isFile() && !file().delete()) file().deleteOnExit();
    }

    private static File file() {
        return new File(RecordingManager.rootDir(), "export-stats.json");
    }

    private static synchronized Map<String, Sample> history() {
        if (history != null) return history;
        history = new HashMap<>();
        File f = file();
        if (f.isFile()) {
            try {
                Map<String, Sample> read = GSON.fromJson(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Sample>>() {}.getType());
                if (read != null) history.putAll(read);
            } catch (Exception e) {
                FlashbackNH.LOG.warn("Unable to read export statistics from {}", f, e);
            }
        }
        return history;
    }

    /** "1.6 GB", "820 MB", "45 KB". */
    public static String formatBytes(long b) {
        if (b >= 1e9) return String.format(java.util.Locale.ROOT, b >= 1e10 ? "%.0f GB" : "%.1f GB", b / 1e9);
        if (b >= 1e6) return String.format(java.util.Locale.ROOT, "%.0f MB", b / 1e6);
        return String.format(java.util.Locale.ROOT, "%.0f KB", Math.max(1, b / 1e3));
    }

    /** "1 h 05 min", "4 min 10 s", "35 s". */
    public static String formatSeconds(double seconds) {
        long s = Math.round(seconds);
        if (s >= 3600) return String.format("%d h %02d min", s / 3600, s / 60 % 60);
        if (s >= 60) return String.format("%d min %02d s", s / 60, s % 60);
        return Math.max(1, s) + " s";
    }
}
