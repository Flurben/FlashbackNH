package com.flurben.flashbacknh.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CameraRigTest {

    private static final double EPS = 1e-6;

    @Test
    void orbitKeepsItsRadiusAndLooksAtTheTarget() {
        CameraRig r = new CameraRig();
        r.type = CameraRig.Type.ORBIT;
        r.start = 0;
        r.end = 400;
        r.radius = 6;
        r.height = 2;
        r.orbitSpeed = 45;
        r.aimHeight = 1;
        for (int tick = 0; tick <= 400; tick += 37) {
            double tx = tick * 0.1, ty = 64, tz = -tick * 0.05;
            double[] c = r.evaluate(tick, tx, ty, tz, 0, 1.8);
            assertEquals(6, Math.hypot(c[0] - tx, c[2] - tz), EPS);
            assertEquals(ty + 2, c[1], EPS);
            double[] look = CameraRig.lookAt(c[0], c[1], c[2], tx, ty + 1.8, tz);
            assertEquals(look[0], c[3], EPS);
            assertEquals(look[1], c[4], EPS);
        }
    }

    @Test
    void orbitStartsSouthAndTurnsAtItsSpeed() {
        CameraRig r = new CameraRig();
        r.type = CameraRig.Type.ORBIT;
        r.radius = 4;
        r.orbitSpeed = 90;
        double[] c = r.evaluate(0, 0, 0, 0, 0, 2);
        assertEquals(0, c[0], EPS);
        assertEquals(4, c[2], EPS);
        // looking north, at the target
        assertEquals(180, Math.abs(c[3]), EPS);
        // a second later, a quarter of the way round
        c = r.evaluate(20, 0, 0, 0, 0, 2);
        assertEquals(-4, c[0], EPS);
        assertEquals(0, c[2], EPS);
    }

    @Test
    void followStaysBehindATurningTarget() {
        CameraRig r = new CameraRig();
        r.type = CameraRig.Type.FOLLOW;
        r.offsetForward = -4;
        r.offsetUp = 1.5;
        r.offsetRight = 0;
        // heading 0 faces south (+z), so behind is north
        double[] c = r.evaluate(0, 10, 64, 10, 0, 1.8);
        assertEquals(10, c[0], EPS);
        assertEquals(6, c[2], EPS);
        assertEquals(65.5, c[1], EPS);
        // heading 90 faces west (-x), so behind is east
        c = r.evaluate(0, 10, 64, 10, 90, 1.8);
        assertEquals(14, c[0], EPS);
        assertEquals(10, c[2], EPS);
    }

    @Test
    void followRightIsTheTargetsRightHand() {
        CameraRig r = new CameraRig();
        r.type = CameraRig.Type.FOLLOW;
        r.offsetForward = 0;
        r.offsetRight = 3;
        r.offsetUp = 0;
        // facing south, the right hand is west
        double[] c = r.evaluate(0, 0, 0, 0, 0, 1.8);
        assertEquals(-3, c[0], EPS);
        assertEquals(0, c[2], EPS);
    }

    @Test
    void followWithoutTurningUsesWorldAxesAndFixedAngles() {
        CameraRig r = new CameraRig();
        r.type = CameraRig.Type.FOLLOW;
        r.turnWithTarget = false;
        r.offsetRight = 2;
        r.offsetForward = 3;
        r.lookAtTarget = false;
        r.yaw = 30;
        r.pitch = 10;
        double[] c = r.evaluate(0, 1, 2, 3, 77, 1.8);
        assertEquals(3, c[0], EPS);
        assertEquals(6, c[2], EPS);
        assertEquals(30, c[3], EPS);
        assertEquals(10, c[4], EPS);
    }

    @Test
    void lookAtStaysInPlace() {
        CameraRig r = new CameraRig();
        r.type = CameraRig.Type.LOOK_AT;
        r.x = 5;
        r.y = 70;
        r.z = -5;
        double[] c = r.evaluate(100, 0, 64, 0, 0, 1.8);
        assertEquals(5, c[0], EPS);
        assertEquals(70, c[1], EPS);
        assertEquals(-5, c[2], EPS);
        // the target is below, so the camera looks down
        assertEquals(true, c[4] > 0);
    }

    @Test
    void weightBlendsInAndOut() {
        CameraRig r = new CameraRig();
        r.start = 100;
        r.end = 200;
        r.blend = 10;
        assertEquals(0, r.weight(99), EPS);
        assertEquals(0, r.weight(100), EPS);
        assertEquals(0.5, r.weight(105), EPS);
        assertEquals(1, r.weight(150), EPS);
        assertEquals(0.5, r.weight(195), EPS);
        assertEquals(0, r.weight(201), EPS);
        r.blend = 0;
        assertEquals(1, r.weight(100), EPS);
    }
}
