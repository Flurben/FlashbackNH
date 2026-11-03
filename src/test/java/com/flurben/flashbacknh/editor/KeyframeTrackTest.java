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

    @Test
    void corruptEditorStateFallsBackToDefault() {
        EditorState r = EditorState.fromJson("{ not json");
        assertEquals(TrackType.values().length, r.tracks.size());
    }
}
