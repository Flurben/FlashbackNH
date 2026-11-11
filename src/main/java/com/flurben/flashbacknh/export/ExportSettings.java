package com.flurben.flashbacknh.export;

import java.io.File;

/** Parameters of one video export. */
public final class ExportSettings {

    public int width = 1920;
    public int height = 1080;
    public int fps = 60;
    public FFmpegEncoder.Format format = FFmpegEncoder.Format.MP4;
    public int crf = 18;
    public String preset = "medium";
    /** For MP4 and MKV. */
    public VideoEncoders.Choice encoder = VideoEncoders.Choice.AUTO;
    /** libx264 threads, 0 for ffmpeg's choice. */
    public int encoderThreads = 0;
    /** The encoder the export uses, after Auto and the test of a hardware encoder. Set by the exporter. */
    public VideoEncoders.Choice resolvedEncoder;
    /** Read frames through pixel buffer objects where the driver has them. */
    public boolean asyncReadback = true;
    /** zlib level of PNG sequences, 0 to 9, -1 for the Java default. */
    public int pngCompression = 4;
    public FFmpegEncoder.Mp4Layout mp4Layout = FFmpegEncoder.Mp4Layout.STANDARD;
    public File output;
    public int startTick;
    public int endTick;
    public boolean useSpeedTrack = true;
    public boolean hideNametags = false;
    public boolean waitForChunks = true;
    public int extraFrames = 0;
    public int warmupFrames = 30;
    public String ffmpegPath = "ffmpeg";
}
