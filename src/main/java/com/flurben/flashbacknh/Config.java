package com.flurben.flashbacknh;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraftforge.common.config.Configuration;

/**
 * All user-facing settings. Stored in config/flashbacknh.cfg and editable from the in-game mod options screen.
 */
public final class Config {

    public static final String CAT_RECORDING = "recording";
    public static final String CAT_REPLAY = "replay";
    public static final String CAT_EXPORT = "export";
    public static final String CAT_ADVANCED = "advanced";

    public static Configuration config;

    // Recording
    public static boolean autoStartRecording = false;
    public static boolean autoFinishRecording = true;
    public static boolean quicksave = false;
    public static boolean showRecordingToasts = true;
    public static boolean markDimensionChanges = true;
    public static boolean recordHotbar = true;
    public static boolean showRecordingIndicator = true;
    public static boolean pauseMenuButtons = true;

    // Replay
    public static float cameraBaseSpeed = 10.0f;
    public static boolean cameraMomentum = true;
    public static boolean showCameraPath = true;
    public static boolean showTips = true;
    public static String defaultInterpolation = "SMOOTH";
    public static int skipSeconds = 5;

    // Export
    public static String ffmpegPath = "ffmpeg";
    public static int exportWidth = 1920;
    public static int exportHeight = 1080;
    public static int exportFps = 60;
    public static int exportCrf = 18;
    public static String exportFormat = "MP4";
    public static String exportPreset = "medium";
    public static String exportFilename = "%replay%-%date%-%time%";
    public static int exportExtraFrames = 0;
    public static boolean exportWaitForChunks = true;
    public static int exportWarmupFrames = 30;

    // Advanced
    public static Set<String> ignoredCustomPayloads = new HashSet<>();
    public static boolean blockReplayGuis = true;

    private Config() {}

    public static void load(File file) {
        config = new Configuration(file);
        sync();
    }

    public static void sync() {
        Configuration c = config;
        c.setCategoryComment(CAT_RECORDING, "Replay recording settings");
        autoStartRecording = c.getBoolean(
            "autoStart",
            CAT_RECORDING,
            autoStartRecording,
            "Automatically start a recording when joining a world/server. Recordings that start on join are the most accurate, especially on servers.");
        autoFinishRecording = c.getBoolean(
            "autoFinish",
            CAT_RECORDING,
            autoFinishRecording,
            "Automatically finish (save) the recording when leaving the world/server.");
        quicksave = c.getBoolean(
            "quicksave",
            CAT_RECORDING,
            quicksave,
            "Save recordings with default values instead of opening the Save Replay screen.");
        showRecordingToasts = c
            .getBoolean("showRecordingToasts", CAT_RECORDING, showRecordingToasts, "Show a notification when starting/stopping a recording.");
        markDimensionChanges = c.getBoolean(
            "markDimensionChanges",
            CAT_RECORDING,
            markDimensionChanges,
            "Automatically add a marker when the recording player changes dimension.");
        recordHotbar = c.getBoolean(
            "recordHotbar",
            CAT_RECORDING,
            recordHotbar,
            "Record first-person hotbar/health information so it can be shown while spectating the recording player.");
        showRecordingIndicator = c.getBoolean(
            "showRecordingIndicator",
            CAT_RECORDING,
            showRecordingIndicator,
            "Show a small REC indicator in the top-left corner while recording.");
        pauseMenuButtons = c
            .getBoolean("pauseMenuButtons", CAT_RECORDING, pauseMenuButtons, "Add recording controls to the pause menu.");

        c.setCategoryComment(CAT_REPLAY, "Replay viewer / editor settings");
        cameraBaseSpeed = c.getFloat("cameraBaseSpeed", CAT_REPLAY, cameraBaseSpeed, 0.1f, 500f, "Default free camera speed in blocks per second.");
        cameraMomentum = c.getBoolean("cameraMomentum", CAT_REPLAY, cameraMomentum, "Smoothly accelerate/decelerate the free camera.");
        showCameraPath = c.getBoolean("showCameraPath", CAT_REPLAY, showCameraPath, "Render the camera keyframe path in the world while editing (never visible in exports).");
        showTips = c.getBoolean("showTips", CAT_REPLAY, showTips, "Show a usage tip when opening a replay.");
        defaultInterpolation = c.getString(
            "defaultInterpolation",
            CAT_REPLAY,
            defaultInterpolation,
            "Interpolation used for new keyframes.",
            new String[] { "SMOOTH", "LINEAR", "EASE_IN_OUT", "HOLD" });
        skipSeconds = c.getInt("skipSeconds", CAT_REPLAY, skipSeconds, 1, 600, "Seconds skipped by the skip forwards/backwards buttons.");

        c.setCategoryComment(CAT_EXPORT, "Video export settings");
        ffmpegPath = c.getString(
            "ffmpegPath",
            CAT_EXPORT,
            ffmpegPath,
            "Path to the ffmpeg executable used for video export. 'ffmpeg' uses the one on your PATH. PNG sequence export does not need ffmpeg.");
        exportWidth = c.getInt("width", CAT_EXPORT, exportWidth, 16, 16384, "Default export width.");
        exportHeight = c.getInt("height", CAT_EXPORT, exportHeight, 16, 16384, "Default export height.");
        exportFps = c.getInt("fps", CAT_EXPORT, exportFps, 1, 1000, "Default export framerate.");
        exportCrf = c.getInt("crf", CAT_EXPORT, exportCrf, 0, 63, "Default constant rate factor (quality, lower is better).");
        exportFormat = c.getString(
            "format",
            CAT_EXPORT,
            exportFormat,
            "Default export format.",
            new String[] { "MP4", "MKV", "WEBM", "MOV_PRORES", "GIF", "PNG" });
        exportPreset = c.getString("preset", CAT_EXPORT, exportPreset, "x264 preset (ultrafast..veryslow).");
        exportFilename = c.getString(
            "filename",
            CAT_EXPORT,
            exportFilename,
            "Default export filename. Variables: %date%, %time%, %replay%, %seq%");
        exportExtraFrames = c.getInt(
            "extraFrames",
            CAT_EXPORT,
            exportExtraFrames,
            0,
            64,
            "Dummy frames rendered before each saved frame. Helps shaders that rely on temporal accumulation (TAA) settle.");
        exportWaitForChunks = c.getBoolean(
            "waitForChunks",
            CAT_EXPORT,
            exportWaitForChunks,
            "Before saving a frame, keep rendering until all visible chunks are built (works with vanilla rendering and Angelica).");
        exportWarmupFrames = c.getInt(
            "warmupFrames",
            CAT_EXPORT,
            exportWarmupFrames,
            0,
            1000,
            "Frames rendered (and discarded) before the first exported frame, to let chunks, shaders and particles settle.");

        c.setCategoryComment(CAT_ADVANCED, "Only change these if you know what you are doing");
        ignoredCustomPayloads = new HashSet<>(
            Arrays.asList(
                c.getStringList(
                    "ignoredCustomPayloads",
                    CAT_ADVANCED,
                    new String[0],
                    "Custom payload (mod network) channels that are ignored during playback. Use this to work around mods that misbehave in replays.")));
        com.flurben.flashbacknh.replay.net.PacketFilter.ignoredChannels = ignoredCustomPayloads;
        blockReplayGuis = c.getBoolean(
            "blockReplayGuis",
            CAT_ADVANCED,
            blockReplayGuis,
            "Prevent recorded server packets from opening container/sign/book GUIs during playback.");

        if (c.hasChanged()) {
            c.save();
        }
    }

    public static void setAndSave(String category, String key, Object value) {
        if (config == null) return;
        net.minecraftforge.common.config.Property p = config.getCategory(category)
            .get(key);
        if (p == null) return;
        if (value instanceof Boolean) p.set((Boolean) value);
        else if (value instanceof Integer) p.set((Integer) value);
        else if (value instanceof Double) p.set((Double) value);
        else if (value instanceof Float) p.set(((Float) value).doubleValue());
        else p.set(String.valueOf(value));
        config.save();
        sync();
    }
}
