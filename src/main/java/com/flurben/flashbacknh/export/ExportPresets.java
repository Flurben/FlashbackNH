package com.flurben.flashbacknh.export;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.recording.RecordingManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/** The built-in export presets and the user's own, which are shared by all replays. */
public final class ExportPresets {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    private static final List<ExportPreset> BUILT_IN = Collections.unmodifiableList(
        Arrays.asList(
            new ExportPreset("YouTube 1080p", 1920, 1080, 60, FFmpegEncoder.Format.MP4, 18, "medium"),
            new ExportPreset("YouTube 4K", 3840, 2160, 60, FFmpegEncoder.Format.MP4, 20, "medium"),
            new ExportPreset("Shorts / TikTok", 1080, 1920, 60, FFmpegEncoder.Format.MP4, 18, "medium"),
            new ExportPreset("Quick Preview", 1280, 720, 30, FFmpegEncoder.Format.MP4, 26, "veryfast"),
            new ExportPreset("Editing (ProRes)", 1920, 1080, 60, FFmpegEncoder.Format.MOV_PRORES, 18, "medium"),
            new ExportPreset("PNG Frames", 1920, 1080, 60, FFmpegEncoder.Format.PNG, 18, "medium"),
            new ExportPreset("GIF", 854, 480, 30, FFmpegEncoder.Format.GIF, 18, "medium")));

    private static List<ExportPreset> user;

    private ExportPresets() {}

    private static File file() {
        return new File(RecordingManager.rootDir(), "export-presets.json");
    }

    /** Built-in presets first, then the user's. */
    public static synchronized List<ExportPreset> all() {
        List<ExportPreset> all = new ArrayList<>(BUILT_IN);
        all.addAll(user());
        return all;
    }

    public static synchronized List<ExportPreset> user() {
        if (user == null) user = load();
        return user;
    }

    public static synchronized ExportPreset find(String name) {
        if (name == null) return null;
        for (ExportPreset p : all()) if (p.name.equals(name)) return p;
        return null;
    }

    /** Adds a preset, or replaces the user's preset of that name. */
    public static synchronized void put(ExportPreset preset) {
        List<ExportPreset> list = user();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(preset.name)) {
                list.set(i, preset);
                save();
                return;
            }
        }
        list.add(preset);
        save();
    }

    public static synchronized void remove(ExportPreset preset) {
        if (user().remove(preset)) save();
    }

    public static synchronized void rename(ExportPreset preset, String name) {
        preset.name = name;
        save();
    }

    /** Whether a new or renamed preset may have this name: not empty and not taken by another preset. */
    public static synchronized boolean nameFree(String name, ExportPreset except) {
        if (name == null || name.trim()
            .isEmpty()) return false;
        for (ExportPreset p : all()) if (p != except && p.name.equalsIgnoreCase(name.trim())) return false;
        return true;
    }

    /** "name 2", "name 3", ... the first one not taken. */
    public static synchronized String freeName(String base) {
        if (nameFree(base, null)) return base;
        for (int i = 2;; i++) if (nameFree(base + " " + i, null)) return base + " " + i;
    }

    private static List<ExportPreset> load() {
        File f = file();
        List<ExportPreset> list = new ArrayList<>();
        if (!f.isFile()) return list;
        try {
            List<ExportPreset> read = GSON.fromJson(
                new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8),
                new TypeToken<List<ExportPreset>>() {}.getType());
            if (read != null) for (ExportPreset p : read) if (p != null && p.name != null) list.add(p);
        } catch (Exception e) {
            FlashbackNH.LOG.warn("Unable to read export presets from {}", f, e);
        }
        return list;
    }

    private static void save() {
        File f = file();
        try {
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(
                tmp.toPath(),
                GSON.toJson(user)
                    .getBytes(StandardCharsets.UTF_8));
            Files.move(
                tmp.toPath(),
                f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            FlashbackNH.LOG.warn("Unable to save export presets to {}", f, e);
        }
    }
}
