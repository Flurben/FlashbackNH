package com.flurben.flashbacknh.editor;

/** How values move from one keyframe to the next. */
public enum Interpolation {

    SMOOTH("Smooth"),
    LINEAR("Linear"),
    EASE_IN_OUT("Ease In/Out"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    HOLD("Hold");

    public final String displayName;

    Interpolation(String displayName) {
        this.displayName = displayName;
    }

    public Interpolation next() {
        Interpolation[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    public static Interpolation parse(String s, Interpolation def) {
        if (s == null) return def;
        try {
            return valueOf(
                s.trim()
                    .toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    /** Maps linear progress 0..1 to eased progress (for the scalar easing curves). */
    public double ease(double t) {
        switch (this) {
            case EASE_IN_OUT:
                return t * t * (3 - 2 * t);
            case EASE_IN:
                return t * t * t;
            case EASE_OUT: {
                double u = 1 - t;
                return 1 - u * u * u;
            }
            case HOLD:
                return 0;
            default:
                return t;
        }
    }

    /** A cubic Bezier curve through p0 and p3 with control points p1 and p2, at parameter u. */
    public static double bezier(double p0, double p1, double p2, double p3, double u) {
        double v = 1 - u;
        return v * v * v * p0 + 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u * p3;
    }

    /**
     * The parameter at which a curve in time, from 0 to 1 with control points at {@code a} and {@code 1 - b}, reaches
     * time t. With both at a third, time runs evenly and this is t itself.
     */
    public static double bezierTime(double a, double b, double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        if (Math.abs(a - 1.0 / 3.0) < 1e-9 && Math.abs(b - 1.0 / 3.0) < 1e-9) return t;
        double lo = 0, hi = 1, u = t;
        for (int i = 0; i < 40; i++) {
            u = (lo + hi) / 2;
            if (bezier(0, a, 1 - b, 1, u) < t) lo = u;
            else hi = u;
        }
        return (lo + hi) / 2;
    }
}
