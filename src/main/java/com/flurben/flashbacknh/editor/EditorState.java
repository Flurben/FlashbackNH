package com.flurben.flashbacknh.editor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.flurben.flashbacknh.format.ReplayMeta;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Everything the user edits in a replay. Saved as editor.json inside the replay archive. */
public final class EditorState {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    public int version = 1;
    public List<KeyframeTrack> tracks = new ArrayList<>();
    public int inPoint = -1;
    public int outPoint = -1;
    public Visuals visuals = new Visuals();
    public ExportSettings export = new ExportSettings();
    /** Markers added in the editor (recording markers live in the metadata). */
    public List<ReplayMeta.Marker> markers = new ArrayList<>();
    /** Camera path presets that follow a moving target. */
    public List<CameraRig> rigs = new ArrayList<>();
    /** Timeline zoom: visible range in ticks, and first visible tick. */
    public double zoomTicks = -1;
    public double scrollTick = 0;

    private transient Map<TrackType, KeyframeTrack> byType;

    public EditorState() {}

    public static EditorState createDefault() {
        EditorState s = new EditorState();
        s.ensureTracks();
        return s;
    }

    public void ensureTracks() {
        if (tracks == null) tracks = new ArrayList<>();
        if (visuals == null) visuals = new Visuals();
        if (export == null) export = new ExportSettings();
        if (markers == null) markers = new ArrayList<>();
        if (rigs == null) rigs = new ArrayList<>();
        rigs.removeIf(r -> r == null || r.type == null || r.target == null);
        byType = new EnumMap<>(TrackType.class);
        List<KeyframeTrack> cleaned = new ArrayList<>();
        for (KeyframeTrack t : tracks) {
            if (t == null || t.type == null || byType.containsKey(t.type)) continue;
            if (t.keyframes == null) t.keyframes = new ArrayList<>();
            t.keyframes.removeIf(k -> k == null || k.values == null || k.values.length < t.type.channels.length);
            for (Keyframe k : t.keyframes) if (k.interpolation == null) k.interpolation = Interpolation.SMOOTH;
            t.sort();
            byType.put(t.type, t);
            cleaned.add(t);
        }
        for (TrackType type : TrackType.values()) {
            if (!byType.containsKey(type)) {
                KeyframeTrack t = new KeyframeTrack(type);
                byType.put(type, t);
                cleaned.add(t);
            }
        }
        cleaned.sort((a, b) -> a.type.ordinal() - b.type.ordinal());
        tracks = cleaned;
    }

    public KeyframeTrack track(TrackType type) {
        if (byType == null) ensureTracks();
        return byType.get(type);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static EditorState fromJson(String json) {
        EditorState s;
        try {
            s = GSON.fromJson(json, EditorState.class);
        } catch (Exception e) {
            s = null;
        }
        if (s == null) s = new EditorState();
        s.ensureTracks();
        return s;
    }

    /** Export options remembered per replay. */
    public static final class ExportSettings {

        public int width = -1;
        public int height = -1;
        public int fps = -1;
        public String format = null;
        public int crf = -1;
        public String filename = null;
        public boolean hideNametags = false;
        public boolean useSpeedTrack = true;
    }
}
