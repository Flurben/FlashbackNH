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
