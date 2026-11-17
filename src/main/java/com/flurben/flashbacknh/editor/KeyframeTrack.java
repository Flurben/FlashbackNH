package com.flurben.flashbacknh.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.util.MathHelper;

/** An ordered list of keyframes of one {@link TrackType}. */
public final class KeyframeTrack {

    public TrackType type;
    public boolean enabled = true;
    public List<Keyframe> keyframes = new ArrayList<>();

    public KeyframeTrack() {}

    public KeyframeTrack(TrackType type) {
        this.type = type;
    }

    public boolean isActive() {
        return enabled && keyframes != null && !keyframes.isEmpty();
    }

    public void sort() {
        keyframes.sort(Comparator.comparingInt(k -> k.tick));
    }

    /** Adds or replaces the keyframe at the given tick. */
    public Keyframe put(int tick, double[] values, Interpolation interpolation) {
        for (Keyframe k : keyframes) {
            if (k.tick == tick) {
                k.values = values;
                return k;
            }
        }
        Keyframe k = new Keyframe(tick, values, interpolation);
        keyframes.add(k);
        sort();
        return k;
    }

    public Keyframe at(int tick) {
        for (Keyframe k : keyframes) if (k.tick == tick) return k;
        return null;
    }

    public Keyframe nextAfter(int tick) {
        for (Keyframe k : keyframes) if (k.tick > tick) return k;
        return null;
    }

    public Keyframe lastBefore(int tick) {
        Keyframe best = null;
        for (Keyframe k : keyframes) if (k.tick < tick) best = k;
        return best;
    }

    /**
     * Evaluates the track at a (fractional) tick. Returns null when the track has no keyframes.
     *
     * <p>
     * A smooth segment is a curve of value against time whose speed never jumps: at a keyframe between two smooth
     * segments it goes on at the spline's speed, next to another kind of segment it takes that segment's speed, and the
     * path starts and ends at rest. How quickly the speed changes is each keyframe's ease in and ease out.
     */
    public double[] evaluate(double tick) {
        List<Keyframe> ks = keyframes;
        int n = ks.size();
        if (n == 0) return null;
        if (n == 1 || tick <= ks.get(0).tick) return ks.get(0).values.clone();
        if (tick >= ks.get(n - 1).tick) return ks.get(n - 1).values.clone();

        int i = 0;
        while (i < n - 2 && ks.get(i + 1).tick <= tick) i++;
        Keyframe k1 = ks.get(i);
        Keyframe k2 = ks.get(i + 1);
        Keyframe k0 = i > 0 ? ks.get(i - 1) : null;
        Keyframe k3 = i + 2 < n ? ks.get(i + 2) : null;
        double span = k2.tick - k1.tick;
        double t = span <= 0 ? 0 : (tick - k1.tick) / span;
        int channels = k1.values.length;
        double[] out = new double[channels];
        // the curve's time is shared by every channel, so it is solved once
        double a = 0, b = 0, u = t;
        if (k1.interpolation == Interpolation.SMOOTH) {
            a = clampEase(k1.easeOut);
            b = clampEase(k2.easeIn);
            u = Interpolation.bezierTime(a, b, t);
        }
        for (int c = 0; c < channels; c++) {
            double p1 = k1.values[c];
            double p2 = c < k2.values.length ? k2.values[c] : p1;
            double p0 = k0 != null && c < k0.values.length ? k0.values[c] : p1;
            double p3 = k3 != null && c < k3.values.length ? k3.values[c] : p2;
            if (type != null && c < type.angular.length && type.angular[c]) {
                p0 = p1 + MathHelper.wrapAngleTo180_double(p0 - p1);
                p2 = p1 + MathHelper.wrapAngleTo180_double(p2 - p1);
                p3 = p2 + MathHelper.wrapAngleTo180_double(p3 - p2);
            }
            switch (k1.interpolation) {
                case HOLD:
                    out[c] = p1;
                    break;
                case SMOOTH: {
                    // speeds in value per tick at both ends of the segment
                    double v1 = speedAt(k0, k1, k2, p0, p1, p2);
                    double v2 = speedAt(k1, k2, k3, p1, p2, p3);
                    out[c] = Interpolation.bezier(p1, p1 + v1 * a * span, p2 - v2 * b * span, p2, u);
                    break;
                }
                default:
                    out[c] = p1 + (p2 - p1) * k1.interpolation.ease(t);
                    break;
            }
        }
        return out;
    }

    private static double clampEase(double e) {
        if (Double.isNaN(e)) return Keyframe.DEFAULT_EASE;
        return Math.max(Keyframe.MIN_EASE, Math.min(1, e));
    }

    /**
     * The speed at keyframe {@code k} with values {@code p} (before: {@code prev}, {@code pp}, after: {@code next},
     * {@code pn}), as smooth segments on either side need it. A segment of another kind sets it, as the speed must not
     * jump there, and so does the end of the path, where the camera is at rest. Otherwise it is the spline's speed.
     */
    private static double speedAt(Keyframe prev, Keyframe k, Keyframe next, double pp, double p, double pn) {
        if (prev != null && prev.interpolation != Interpolation.SMOOTH) {
            return endSpeed(prev.interpolation, pp, p, k.tick - prev.tick);
        }
        if (next != null && k.interpolation != Interpolation.SMOOTH) {
            return startSpeed(k.interpolation, p, pn, next.tick - k.tick);
        }
        if (prev == null || next == null) return 0;
        double dt = next.tick - prev.tick;
        return dt > 0 ? (pn - pp) / dt : 0;
    }

    /** The speed at which a segment of this kind from {@code from} to {@code to} over {@code dt} ticks ends. */
    private static double endSpeed(Interpolation kind, double from, double to, double dt) {
        if (dt <= 0) return 0;
        switch (kind) {
            case LINEAR:
                return (to - from) / dt;
            case EASE_IN:
                return 3 * (to - from) / dt;
            default:
                return 0;
        }
    }

    /** The speed at which a segment of this kind from {@code from} to {@code to} over {@code dt} ticks starts. */
    private static double startSpeed(Interpolation kind, double from, double to, double dt) {
        if (dt <= 0) return 0;
        switch (kind) {
            case LINEAR:
                return (to - from) / dt;
            case EASE_OUT:
                return 3 * (to - from) / dt;
            default:
                return 0;
        }
    }

    public KeyframeTrack copy() {
        KeyframeTrack t = new KeyframeTrack(type);
        t.enabled = enabled;
        for (Keyframe k : keyframes) t.keyframes.add(k.copy());
        return t;
    }
}
