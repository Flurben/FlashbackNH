package com.flurben.flashbacknh.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KeyframeTrackTest {

    private static KeyframeTrack track(TrackType type, Interpolation in, int[] ticks, double[]... values) {
        KeyframeTrack t = new KeyframeTrack(type);
        for (int i = 0; i < ticks.length; i++) t.put(ticks[i], values[i], in);
        return t;
    }

    @Test
    void emptyTrackHasNoValue() {
        assertNull(new KeyframeTrack(TrackType.FOV).evaluate(10));
    }

    @Test
    void linearInterpolatesAndClamps() {
        KeyframeTrack t = track(
            TrackType.FOV,
            Interpolation.LINEAR,
            new int[] { 0, 100 },
            new double[] { 70 },
            new double[] { 90 });
        assertEquals(70, t.evaluate(-5)[0], 1e-9);
        assertEquals(80, t.evaluate(50)[0], 1e-9);
        assertEquals(90, t.evaluate(500)[0], 1e-9);
    }

    @Test
    void holdKeepsLeftValue() {
        KeyframeTrack t = track(
            TrackType.FOV,
            Interpolation.HOLD,
            new int[] { 0, 100 },
            new double[] { 70 },
            new double[] { 90 });
        assertEquals(70, t.evaluate(99.9)[0], 1e-9);
        assertEquals(90, t.evaluate(100)[0], 1e-9);
    }

    @Test
    void smoothPassesThroughKeyframes() {
        KeyframeTrack t = track(
            TrackType.CAMERA,
            Interpolation.SMOOTH,
            new int[] { 0, 40, 100 },
            new double[] { 0, 64, 0, 0, 0, 0 },
            new double[] { 10, 70, 5, 90, 10, 0 },
            new double[] { 30, 64, 20, 180, -10, 0 });
        assertEquals(10, t.evaluate(40)[0], 1e-9);
        assertEquals(70, t.evaluate(40)[1], 1e-9);
        double[] mid = t.evaluate(20);
        assertTrue(mid[0] > 0 && mid[0] < 10, "x between keyframes: " + mid[0]);
    }

    @Test
    void anglesTakeShortestPath() {
        KeyframeTrack t = track(
            TrackType.CAMERA,
            Interpolation.LINEAR,
            new int[] { 0, 10 },
            new double[] { 0, 0, 0, 170, 0, 0 },
            new double[] { 0, 0, 0, -170, 0, 0 });
        // 170 -> -170 should pass through 180, not 0
        assertEquals(180, t.evaluate(5)[3], 1e-9);
    }

    @Test
    void putReplacesExistingTick() {
        KeyframeTrack t = new KeyframeTrack(TrackType.SPEED);
        t.put(5, new double[] { 1 }, Interpolation.LINEAR);
        t.put(5, new double[] { 2 }, Interpolation.LINEAR);
        assertEquals(1, t.keyframes.size());
        assertEquals(2, t.evaluate(5)[0], 1e-9);
    }

    @Test
    void editorStateRoundTrip() {
        EditorState s = EditorState.createDefault();
        s.track(TrackType.CAMERA)
            .put(3, new double[] { 1, 2, 3, 4, 5, 6 }, Interpolation.EASE_IN_OUT);
        s.inPoint = 3;
        s.visuals.hiddenEntities.add("#5");
        EditorState r = EditorState.fromJson(s.toJson());
        assertEquals(3, r.inPoint);
        assertEquals(Interpolation.EASE_IN_OUT, r.track(TrackType.CAMERA).keyframes.get(0).interpolation);
        assertTrue(r.visuals.hiddenEntities.contains("#5"));
        assertEquals(TrackType.values().length, r.tracks.size());
    }

    /** Speed of channel c just before (side -1) or after (side 1) a tick. */
    private static double speed(KeyframeTrack t, double tick, int side, int c) {
        double h = 1e-4;
        return side < 0 ? (t.evaluate(tick)[c] - t.evaluate(tick - h)[c]) / h
            : (t.evaluate(tick + h)[c] - t.evaluate(tick)[c]) / h;
    }

    private static KeyframeTrack unevenSmooth() {
        return track(
            TrackType.FOV,
            Interpolation.SMOOTH,
            new int[] { 0, 10, 60, 70, 200 },
            new double[] { 0 },
            new double[] { 30 },
            new double[] { 40 },
            new double[] { 90 },
            new double[] { 20 });
    }

    @Test
    void smoothSpeedNeverJumpsAtKeyframes() {
        KeyframeTrack t = unevenSmooth();
        for (int tick : new int[] { 10, 60, 70 }) {
            assertEquals(speed(t, tick, -1, 0), speed(t, tick, 1, 0), 1e-2, "speed at tick " + tick);
        }
    }

    @Test
    void smoothPathStartsAndEndsAtRest() {
        KeyframeTrack t = unevenSmooth();
        assertEquals(0, speed(t, 0, 1, 0), 1e-2);
        assertEquals(0, speed(t, 200, -1, 0), 1e-2);
    }

    @Test
    void easeChangesTimingButNotSpeedAtKeyframe() {
        KeyframeTrack plain = unevenSmooth();
        KeyframeTrack eased = unevenSmooth();
        eased.at(10).easeOut = 0.9;
        eased.at(60).easeIn = 0.9;
        assertEquals(speed(plain, 10, 1, 0), speed(eased, 10, 1, 0), 1e-2);
        assertEquals(speed(eased, 10, -1, 0), speed(eased, 10, 1, 0), 1e-2);
        assertEquals(40, eased.evaluate(60)[0], 1e-9);
        assertTrue(Math.abs(plain.evaluate(35)[0] - eased.evaluate(35)[0]) > 0.5, "ease changes the curve");
    }

    @Test
    void smoothTakesTheSpeedOfALinearNeighbour() {
        KeyframeTrack t = unevenSmooth();
        t.at(0).interpolation = Interpolation.LINEAR;
        t.at(60).interpolation = Interpolation.LINEAR;
        assertEquals(3, speed(t, 10, 1, 0), 1e-2);
        assertEquals(speed(t, 60, -1, 0), speed(t, 60, 1, 0), 1e-2);
        assertEquals(speed(t, 70, -1, 0), speed(t, 70, 1, 0), 1e-2);
    }

    @Test
    void defaultEaseKeepsTheSplineBetweenKeyframes() {
        KeyframeTrack t = unevenSmooth();
        // the spline of earlier versions, between 10 and 60, with neighbours at 0 and 70
        double p0 = 0, p1 = 30, p2 = 40, p3 = 90, t0 = 0, t1 = 10, t2 = 60, t3 = 70;
        double dt = t2 - t1, m1 = (p2 - p0) / (t2 - t0) * dt, m2 = (p3 - p1) / (t3 - t1) * dt;
        for (double s = 0.1; s < 1; s += 0.2) {
            double s2 = s * s, s3 = s2 * s;
            double old = (2 * s3 - 3 * s2 + 1) * p1 + (s3 - 2 * s2 + s) * m1 + (-2 * s3 + 3 * s2) * p2 + (s3 - s2) * m2;
            assertEquals(old, t.evaluate(t1 + s * dt)[0], 1e-9);
        }
    }

    @Test
    void keyframesFromOlderVersionsGetTheDefaultEase() {
        EditorState r = EditorState.fromJson(
            "{\"tracks\":[{\"type\":\"FOV\",\"keyframes\":[{\"tick\":0,\"values\":[70],\"interpolation\":\"SMOOTH\"}]}]}");
        Keyframe k = r.track(TrackType.FOV).keyframes.get(0);
        assertEquals(Keyframe.DEFAULT_EASE, k.easeIn, 1e-12);
        assertEquals(Keyframe.DEFAULT_EASE, k.easeOut, 1e-12);
    }

    @Test
    void undoAndRedoStepThroughSettledChanges() {
        EditorState s = EditorState.createDefault();
        EditHistory h = new EditHistory();
        h.reset(s);
        s.track(TrackType.FOV)
            .put(5, new double[] { 60 }, Interpolation.SMOOTH);
        h.changed();
        h.settle(s);
        s.inPoint = 5;
        h.changed();
        // the second change is not settled yet: undo takes it in first
        assertTrue(h.undo(s));
        assertEquals(-1, s.inPoint);
        assertEquals(1, s.track(TrackType.FOV).keyframes.size());
        assertTrue(h.undo(s));
        assertEquals(0, s.track(TrackType.FOV).keyframes.size());
        assertTrue(!h.undo(s));
        assertTrue(h.redo(s));
        assertEquals(1, s.track(TrackType.FOV).keyframes.size());
        assertTrue(h.redo(s));
        assertEquals(5, s.inPoint);
        assertTrue(!h.redo(s));
        // a new change after undoing drops what could be redone
        h.undo(s);
        s.outPoint = 9;
        h.changed();
        h.settle(s);
        assertTrue(!h.redo(s));
    }

    @Test
    void restoredStateIsTheSameState() {
        EditorState s = EditorState.createDefault();
        s.track(TrackType.CAMERA)
            .put(20, new double[] { 1.5, 2, 3, 4, 5, 6 }, Interpolation.SMOOTH);
        s.visuals.hiddenEntities.add("#5");
        s.visuals.hiddenEntities.add("abc");
        String json = s.historyJson();
        s.restoreHistory(json);
        assertEquals(json, s.historyJson());
    }

    @Test
    void corruptEditorStateFallsBackToDefault() {
        EditorState r = EditorState.fromJson("{ not json");
        assertEquals(TrackType.values().length, r.tracks.size());
    }
}
