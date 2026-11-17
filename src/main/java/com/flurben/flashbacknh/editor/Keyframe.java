package com.flurben.flashbacknh.editor;

/** A keyframe on a {@link KeyframeTrack}: a replay tick and the track's channel values at that tick. */
public final class Keyframe {

    public int tick;
    public double[] values;
    public Interpolation interpolation = Interpolation.SMOOTH;
    /**
     * For smooth movement: how much of the time before this keyframe is spent slowing into it (ease in) and of the time
     * after it speeding away (ease out), each a part of that segment from {@link #MIN_EASE} to 1. The speed at the
     * keyframe stays the same either way; a longer ease spreads the change of speed out. The default is the plain
     * spline of earlier versions.
     */
    public double easeIn = DEFAULT_EASE, easeOut = DEFAULT_EASE;

    public static final double DEFAULT_EASE = 1.0 / 3.0;
    public static final double MIN_EASE = 0.02;

    public Keyframe() {}

    public Keyframe(int tick, double[] values, Interpolation interpolation) {
        this.tick = tick;
        this.values = values;
        this.interpolation = interpolation;
    }

    public Keyframe copy() {
        Keyframe k = new Keyframe(tick, values.clone(), interpolation);
        k.easeIn = easeIn;
        k.easeOut = easeOut;
        return k;
    }
}
