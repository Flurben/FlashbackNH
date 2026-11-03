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
        Keyframe k0 = i > 0 ? ks.get(i - 1) : k1;
        Keyframe k3 = i + 2 < n ? ks.get(i + 2) : k2;
        double span = k2.tick - k1.tick;
        double t = span <= 0 ? 0 : (tick - k1.tick) / span;
        int channels = k1.values.length;
        double[] out = new double[channels];
        for (int c = 0; c < channels; c++) {
            double p1 = k1.values[c];
            double p2 = c < k2.values.length ? k2.values[c] : p1;
            double p0 = c < k0.values.length ? k0.values[c] : p1;
            double p3 = c < k3.values.length ? k3.values[c] : p2;
            if (type != null && c < type.angular.length && type.angular[c]) {
                p0 = p1 + MathHelper.wrapAngleTo180_double(p0 - p1);
                p2 = p1 + MathHelper.wrapAngleTo180_double(p2 - p1);
                p3 = p2 + MathHelper.wrapAngleTo180_double(p3 - p2);
            }
            switch (k1.interpolation) {
                case HOLD:
                    out[c] = p1;
                    break;
                case SMOOTH:
                    out[c] = Interpolation.catmullRom(p0, p1, p2, p3, k0.tick, k1.tick, k2.tick, k3.tick, t);
                    break;
                default:
                    out[c] = p1 + (p2 - p1) * k1.interpolation.ease(t);
                    break;
            }
        }
        return out;
    }

    public KeyframeTrack copy() {
        KeyframeTrack t = new KeyframeTrack(type);
        t.enabled = enabled;
        for (Keyframe k : keyframes) t.keyframes.add(k.copy());
        return t;
    }
}
