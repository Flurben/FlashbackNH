package com.flurben.flashbacknh.editor;

/**
 * A camera path preset that is worked out from a moving target while the replay plays, instead of from keyframes.
 * Over its range of ticks it replaces the camera keyframe path and blends into it at both ends.
 */
public final class CameraRig {

    public enum Type {

        /** Circles around the target. */
        ORBIT("Orbit"),
        /** Moves with the target, keeping an offset from it. */
        FOLLOW("Follow"),
        /** Stays in one place and turns to keep the target in view. */
        LOOK_AT("Look At");

        public final String displayName;

        Type(String displayName) {
            this.displayName = displayName;
        }
    }

    public Type type = Type.ORBIT;
    /** The target, as {@code ReplayHandler.entityKey}: a player's uuid, or '#' and an entity id. */
    public String target = "";
    /** The target's name when the preset was made, to show while the target is not loaded. */
    public String targetName = "";
    public int start;
    public int end;

    // Orbit
    public double radius = 5;
    public double height = 1.5;
    /** Where on the circle the camera starts, in degrees. 0 is south of the target. */
    public double startAngle = 0;
    /** Degrees per second, negative to go the other way. */
    public double orbitSpeed = 20;

    // Follow
    /** Offset from the target: right, up and forward, of the target's heading or of the world (x, y, z). */
    public double offsetRight = 0, offsetUp = 1.5, offsetForward = -4;
    /** Whether the follow offset turns with the target. */
    public boolean turnWithTarget = true;

    // Look at
    public double x, y, z;

    // Shared
    /** Whether the camera keeps looking at the target. If not it keeps {@link #yaw} and {@link #pitch}. */
    public boolean lookAtTarget = true;
    /** Yaw and pitch for a camera that does not look at the target. Follow adds the target's heading to the yaw. */
    public double yaw, pitch;
    /** How far above the target's feet the camera aims, as a fraction of the target's height. */
    public double aimHeight = 0.85;
    /** Seconds the camera lags behind the target's movement, which takes out jitter. 0 follows exactly. */
    public double smoothing = 0.25;
    /** Ticks at each end spent blending from and to the camera keyframe path. */
    public int blend = 10;
    public double roll = 0;

    public boolean covers(double tick) {
        return tick >= start && tick <= end;
    }

    public CameraRig copy() {
        CameraRig r = new CameraRig();
        r.type = type;
        r.target = target;
        r.targetName = targetName;
        r.start = start;
        r.end = end;
        r.radius = radius;
        r.height = height;
        r.startAngle = startAngle;
        r.orbitSpeed = orbitSpeed;
        r.offsetRight = offsetRight;
        r.offsetUp = offsetUp;
        r.offsetForward = offsetForward;
        r.turnWithTarget = turnWithTarget;
        r.x = x;
        r.y = y;
        r.z = z;
        r.lookAtTarget = lookAtTarget;
        r.yaw = yaw;
        r.pitch = pitch;
        r.aimHeight = aimHeight;
        r.smoothing = smoothing;
        r.blend = blend;
        r.roll = roll;
        return r;
    }

    /**
     * The camera at a tick: x, y, z of the eye, yaw, pitch and roll.
     *
     * @param tx        the target's feet
     * @param targetYaw the target's body heading in degrees
     * @param targetH   the target's height
     */
    public double[] evaluate(double tick, double tx, double ty, double tz, double targetYaw, double targetH) {
        double cx, cy, cz;
        switch (type) {
            case ORBIT: {
                double angle = Math.toRadians(startAngle + orbitSpeed * (tick - start) / 20.0);
                cx = tx - Math.sin(angle) * radius;
                cy = ty + height;
                cz = tz + Math.cos(angle) * radius;
                break;
            }
            case FOLLOW: {
                if (turnWithTarget) {
                    double h = Math.toRadians(targetYaw);
                    // a heading of 0 faces +z; its right is -x
                    double fx = -Math.sin(h), fz = Math.cos(h);
                    double rx = -fz, rz = fx;
                    cx = tx + fx * offsetForward + rx * offsetRight;
                    cz = tz + fz * offsetForward + rz * offsetRight;
                } else {
                    cx = tx + offsetRight;
                    cz = tz + offsetForward;
                }
                cy = ty + offsetUp;
                break;
            }
            default:
                cx = x;
                cy = y;
                cz = z;
                break;
        }
        double yawOut, pitchOut;
        if (lookAtTarget) {
            double[] look = lookAt(cx, cy, cz, tx, ty + targetH * aimHeight, tz);
            yawOut = look[0];
            pitchOut = look[1];
        } else {
            yawOut = yaw + (type == Type.FOLLOW && turnWithTarget ? targetYaw : 0);
            pitchOut = pitch;
        }
        return new double[] { cx, cy, cz, yawOut, pitchOut, roll };
    }

    /** Yaw and pitch of a camera at one point looking at another. */
    public static double[] lookAt(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double yaw = horizontal < 1e-6 && Math.abs(dy) > 0 ? 0 : Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, horizontal));
        return new double[] { yaw, pitch };
    }

    /** How much of the rig is used at a tick, 0 to 1, for blending with the keyframe path. */
    public double weight(double tick) {
        if (!covers(tick)) return 0;
        int b = Math.min(blend, (end - start) / 2);
        if (b <= 0) return 1;
        double w = Math.min(1, Math.min((tick - start) / b, (end - tick) / b));
        return w * w * (3 - 2 * w);
    }
}
