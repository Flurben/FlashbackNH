package com.flurben.flashbacknh.export;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.flurben.flashbacknh.FlashbackNH;

/**
 * The H.264/HEVC encoders of ffmpeg an export can use: libx264 on the CPU, or the video encoder of the graphics card
 * or processor. Which hardware encoders work is found out once per session and ffmpeg path, by asking ffmpeg which
 * it has and encoding a test frame with each: ffmpeg lists encoders that its build supports, not ones the machine
 * has.
 */
public final class VideoEncoders {

    /** An encoder choice of the export dialog. */
    public enum Choice {

        AUTO("Auto", null),
        LIBX264("x264", "libx264"),
        H264_NVENC("NVENC H.264", "h264_nvenc"),
        HEVC_NVENC("NVENC HEVC", "hevc_nvenc"),
        H264_QSV("Intel QSV", "h264_qsv"),
        H264_AMF("AMD AMF", "h264_amf"),
        H264_VIDEOTOOLBOX("VideoToolbox", "h264_videotoolbox");

        public final String displayName;
        /** The ffmpeg encoder, null for {@link #AUTO}. */
        public final String codec;

        Choice(String displayName, String codec) {
            this.displayName = displayName;
            this.codec = codec;
        }

        public static Choice parse(String s) {
            try {
                return valueOf(s.toUpperCase());
            } catch (Exception e) {
                return AUTO;
            }
        }
    }

    /** Hardware encoders in the order Auto prefers them. HEVC is never picked by Auto: not every editor reads it. */
    private static final Choice[] AUTO_ORDER = { Choice.H264_NVENC, Choice.H264_AMF, Choice.H264_QSV,
        Choice.H264_VIDEOTOOLBOX };

    private static final Map<String, Set<Choice>> working = new HashMap<>();

    private VideoEncoders() {}

    /**
     * The hardware encoders that work with this ffmpeg. Slow the first time (a few seconds), cached after. Any
     * thread.
     */
    public static Set<Choice> detect(String ffmpeg) {
        synchronized (working) {
            Set<Choice> known = working.get(ffmpeg);
            if (known != null) return known;
        }
        Set<Choice> found = new LinkedHashSet<>();
        List<String> listed = listEncoders(ffmpeg);
        for (Choice c : Choice.values()) {
            if (c == Choice.AUTO || c == Choice.LIBX264 || !listed.contains(c.codec)) continue;
            if (test(ffmpeg, c, 256, 256, 18)) found.add(c);
        }
        FlashbackNH.LOG.info("Hardware video encoders that work: {}", found.isEmpty() ? "none" : found);
        Set<Choice> result = Collections.unmodifiableSet(found);
        synchronized (working) {
            working.put(ffmpeg, result);
        }
        return result;
    }

    /** Whether the hardware encoders have been checked for this ffmpeg already. */
    public static boolean detected(String ffmpeg) {
        synchronized (working) {
            return working.containsKey(ffmpeg);
        }
    }

    /** The encoder Auto uses: the first working hardware encoder, else libx264. */
    public static Choice auto(String ffmpeg) {
        Set<Choice> found = detect(ffmpeg);
        for (Choice c : AUTO_ORDER) if (found.contains(c)) return c;
        return Choice.LIBX264;
    }

    /**
     * Encodes one frame of the export's size with these settings, to see that the encoder takes them. Hardware
     * encoders have size limits and can be busy with other programs.
     */
    public static boolean test(String ffmpeg, Choice c, int width, int height, int crf) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-f");
        cmd.add("lavfi");
        cmd.add("-i");
        cmd.add("color=c=gray:s=" + width + "x" + height + ":r=30");
        cmd.add("-frames:v");
        cmd.add("1");
        cmd.addAll(args(c, crf, "medium", 0));
        cmd.add("-f");
        cmd.add("null");
        cmd.add("-");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) if (out.length() < 2000) out.append(line)
                    .append('\n');
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            if (p.exitValue() != 0) {
                FlashbackNH.LOG.info(
                    "Video encoder {} does not work here: {}",
                    c.codec,
                    out.toString()
                        .trim());
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> listEncoders(String ffmpeg) {
        List<String> names = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-encoders").redirectErrorStream(true)
                .start();
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // " V....D h264_nvenc NVIDIA NVENC H.264 encoder"
                    String[] parts = line.trim()
                        .split("\\s+");
                    if (parts.length >= 2 && parts[0].length() == 6 && parts[0].charAt(0) == 'V') names.add(parts[1]);
                }
            }
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            FlashbackNH.LOG.debug("Unable to list ffmpeg encoders", e);
        }
        return names;
    }

    /**
     * The ffmpeg output options for an encoder. Quality follows the CRF of the export dialog: each hardware encoder's
     * own constant quality mode, set so that the result looks about the same as libx264 at that CRF.
     *
     * @param threads libx264 threads, 0 for ffmpeg's choice
     */
    static List<String> args(Choice c, int crf, String preset, int threads) {
        List<String> a = new ArrayList<>();
        a.add("-c:v");
        a.add(c.codec);
        switch (c) {
            case H264_NVENC:
            case HEVC_NVENC:
                // measured against a lossless capture: CQ = CRF + 5 gives the SSIM of libx264 medium at that CRF
                // (0.9876 against 0.9872 at CRF 18). p4 encodes 1080p at about 410 fps on an RTX 4070 Ti SUPER, p5
                // at 270, with the same quality and 2% more size
                add(a, "-preset", "p4", "-tune", "hq", "-rc", "vbr", "-cq", String.valueOf(clamp(crf + 5, 0, 51)));
                add(a, "-b:v", "0", "-pix_fmt", "yuv420p");
                if (c == Choice.H264_NVENC) add(a, "-profile:v", "high");
                break;
            case H264_QSV:
                // CRF + 2 measured the same way (SSIM 0.9865 at CRF 18)
                add(a, "-preset", "medium", "-global_quality", String.valueOf(clamp(crf + 2, 1, 51)));
                add(a, "-pix_fmt", "nv12");
                break;
            case H264_AMF:
                add(a, "-quality", "balanced", "-rc", "cqp");
                add(a, "-qp_i", String.valueOf(clamp(crf, 0, 51)), "-qp_p", String.valueOf(clamp(crf + 2, 0, 51)));
                add(a, "-qp_b", String.valueOf(clamp(crf + 4, 0, 51)), "-pix_fmt", "yuv420p");
                break;
            case H264_VIDEOTOOLBOX:
                // -q:v runs from 1 (worst) to 100 (best)
                add(a, "-q:v", String.valueOf(clamp(100 - crf * 2, 1, 100)), "-pix_fmt", "yuv420p");
                break;
            default:
                add(a, "-preset", preset == null || preset.isEmpty() ? "medium" : preset);
                add(a, "-crf", String.valueOf(crf), "-pix_fmt", "yuv420p");
                if (threads > 0) add(a, "-threads", String.valueOf(threads));
                break;
        }
        return a;
    }

    private static void add(List<String> list, String... values) {
        Collections.addAll(list, values);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
