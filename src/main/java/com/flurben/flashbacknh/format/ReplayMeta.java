package com.flurben.flashbacknh.format;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Replay metadata, stored as metadata.json inside the replay archive. */
public final class ReplayMeta {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    public int formatVersion = ReplayFormat.VERSION;
    public String name = "Replay";
    public String worldName = "";
    public String serverAddress = null;
    public boolean singleplayer = true;
    public String mcVersion = "1.7.10";
    public String modVersion = "";
    public long createdAt = System.currentTimeMillis();
    public int durationTicks = 0;
    public String recordedBy = "";
    public String recordedByUuid = "";
    public int localPlayerEntityId = 0;
    public List<Marker> markers = new ArrayList<>();
    public List<String> mods = new ArrayList<>();

    public static final class Marker {

        public int tick;
        public int color = 0xFFD94040;
        public String description = "";
        public boolean hasPosition;
        public double x, y, z;
        public int dimension;

        public Marker() {}

        public Marker(int tick, int color, String description) {
            this.tick = tick;
            this.color = color;
            this.description = description == null ? "" : description;
        }
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static ReplayMeta fromJson(String json) {
        ReplayMeta meta = GSON.fromJson(json, ReplayMeta.class);
        if (meta == null) meta = new ReplayMeta();
        if (meta.markers == null) meta.markers = new ArrayList<>();
        if (meta.mods == null) meta.mods = new ArrayList<>();
        if (meta.name == null) meta.name = "Replay";
        if (meta.worldName == null) meta.worldName = "";
        return meta;
    }

    public String formatDuration() {
        return formatTicks(durationTicks);
    }

    public static String formatTicks(int ticks) {
        int totalSeconds = Math.max(0, ticks) / 20;
        int h = totalSeconds / 3600;
        int m = (totalSeconds / 60) % 60;
        int s = totalSeconds % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }
}
