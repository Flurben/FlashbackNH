package com.flurben.flashbacknh.export;

/**
 * Named render settings: everything about how a video is made, nothing about which part of which replay. Built-in
 * presets cannot be changed or deleted; the user's own are kept in export-presets.json.
 */
public final class ExportPreset {

    public String name;
    public int width = 1920;
    public int height = 1080;
    public int fps = 60;
    public String format = "MP4";
    public String encoder = "AUTO";
    public int crf = 18;
    public String x264Preset = "medium";
    public String mp4Layout = "STANDARD";
    public int pngCompression = 4;
    public int extraFrames = 0;
    public boolean waitForChunks = true;
    public boolean hideNametags = false;

    /** Not saved: true for the presets that come with the mod. */
    public transient boolean builtIn;

    public ExportPreset() {}

    ExportPreset(String name, int width, int height, int fps, FFmpegEncoder.Format format, int crf, String x264Preset) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.format = format.name();
        this.crf = crf;
        this.x264Preset = x264Preset;
        this.builtIn = true;
    }

    /** A preset holding these settings. */
    public static ExportPreset of(String name, ExportSettings s) {
        ExportPreset p = new ExportPreset();
        p.name = name;
        p.width = s.width;
        p.height = s.height;
        p.fps = s.fps;
        p.format = s.format.name();
        p.encoder = s.encoder.name();
        p.crf = s.crf;
        p.x264Preset = s.preset;
        p.mp4Layout = s.mp4Layout.name();
        p.pngCompression = s.pngCompression;
        p.extraFrames = s.extraFrames;
        p.waitForChunks = s.waitForChunks;
        p.hideNametags = s.hideNametags;
        return p;
    }

    public void applyTo(ExportSettings s) {
        s.width = width;
        s.height = height;
        s.fps = fps;
        s.format = FFmpegEncoder.Format.parse(format);
        s.encoder = VideoEncoders.Choice.parse(encoder);
        s.crf = crf;
        s.preset = x264Preset;
        s.mp4Layout = FFmpegEncoder.Mp4Layout.parse(mp4Layout);
        s.pngCompression = pngCompression;
        s.extraFrames = extraFrames;
        s.waitForChunks = waitForChunks;
        s.hideNametags = hideNametags;
    }

    /** Whether these settings are what the preset sets. Settings that do not apply to the format are not compared. */
    public boolean matches(ExportSettings s) {
        FFmpegEncoder.Format f = FFmpegEncoder.Format.parse(format);
        if (s.width != width || s.height != height || s.fps != fps || s.format != f) return false;
        if (s.extraFrames != extraFrames || s.waitForChunks != waitForChunks || s.hideNametags != hideNametags) {
            return false;
        }
        if (f == FFmpegEncoder.Format.PNG) return s.pngCompression == pngCompression;
        boolean h264 = f == FFmpegEncoder.Format.MP4 || f == FFmpegEncoder.Format.MKV;
        if (h264 && (s.encoder != VideoEncoders.Choice.parse(encoder) || !s.preset.equals(x264Preset))) return false;
        if (f == FFmpegEncoder.Format.MP4 && s.mp4Layout != FFmpegEncoder.Mp4Layout.parse(mp4Layout)) return false;
        return !(h264 || f == FFmpegEncoder.Format.WEBM) || s.crf == crf;
    }

    /** A short description for the preset strip: "1920x1080 60 MP4". */
    public String summary() {
        return width + "x" + height + " " + fps + " " + FFmpegEncoder.Format.parse(format).extension.toUpperCase();
    }
}
