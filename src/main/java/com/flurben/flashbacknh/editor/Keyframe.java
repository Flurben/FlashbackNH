package com.flurben.flashbacknh.editor;

/** A keyframe on a {@link KeyframeTrack}: a replay tick and the track's channel values at that tick. */
public final class Keyframe {

    public int tick;
    public double[] values;
    public Interpolation interpolation = Interpolation.SMOOTH;

    public Keyframe() {}

    public Keyframe(int tick, double[] values, Interpolation interpolation) {
        this.tick = tick;
        this.values = values;
        this.interpolation = interpolation;
    }

    public Keyframe copy() {
        return new Keyframe(tick, values.clone(), interpolation);
    }
}
