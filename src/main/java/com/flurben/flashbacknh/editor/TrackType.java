package com.flurben.flashbacknh.editor;

/** The kinds of keyframe tracks available in the editor. */
public enum TrackType {

    /** x, y, z, yaw, pitch, roll */
    CAMERA("Camera", 0xFF4FA3E0, new String[] { "X", "Y", "Z", "Yaw", "Pitch", "Roll" },
        new boolean[] { false, false, false, true, true, true }),
    /** field of view in degrees */
    FOV("FOV", 0xFFE0A040, new String[] { "FOV" }, new boolean[] { false }),
    /** time of day in ticks (0-24000, values above wrap) */
    TIME("Time of Day", 0xFFE0E060, new String[] { "Time" }, new boolean[] { false }),
    /** playback speed multiplier, used by export and playback */
    SPEED("Speed", 0xFF60D080, new String[] { "Speed" }, new boolean[] { false }),
    /** camera shake strength (0 = none) */
    SHAKE("Camera Shake", 0xFFD060D0, new String[] { "Strength" }, new boolean[] { false }),
    /** rain and thunder strength, each 0 to 1 (thunder only shows while it rains) */
    WEATHER("Weather", 0xFF70A0C0, new String[] { "Rain", "Thunder" }, new boolean[] { false, false });

    public final String displayName;
    public final int color;
    public final String[] channels;
    public final boolean[] angular;

    TrackType(String displayName, int color, String[] channels, boolean[] angular) {
        this.displayName = displayName;
        this.color = color;
        this.channels = channels;
        this.angular = angular;
    }
}
