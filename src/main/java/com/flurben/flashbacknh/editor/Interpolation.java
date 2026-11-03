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
            return valueOf(s.trim()
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

    /**
     * Interpolates between p1 and p2 with neighbours p0 and p3 (used by SMOOTH, a Catmull-Rom spline with tension 0.5
     * and non-uniform timing).
     */
    public static double catmullRom(double p0, double p1, double p2, double p3, double t0, double t1, double t2,
        double t3, double t) {
        // Hermite form with finite-difference tangents that respect uneven keyframe spacing
        double dt = t2 - t1;
        if (dt <= 0) return p1;
        double m1 = (t2 - t0) > 0 ? (p2 - p0) / (t2 - t0) * dt : (p2 - p1);
        double m2 = (t3 - t1) > 0 ? (p3 - p1) / (t3 - t1) * dt : (p2 - p1);
        double s = t;
        double s2 = s * s;
        double s3 = s2 * s;
        double h00 = 2 * s3 - 3 * s2 + 1;
        double h10 = s3 - 2 * s2 + s;
        double h01 = -2 * s3 + 3 * s2;
        double h11 = s3 - s2;
        return h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2;
    }
}
