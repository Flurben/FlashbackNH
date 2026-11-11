package com.flurben.flashbacknh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;

import org.lwjgl.opengl.GL11;

import com.flurben.flashbacknh.editor.Keyframe;
import com.flurben.flashbacknh.editor.KeyframeTrack;
import com.flurben.flashbacknh.editor.TrackType;
import com.flurben.flashbacknh.export.Exporter;
import com.flurben.flashbacknh.gui.GuiReplayEditor;
import com.flurben.flashbacknh.replay.ReplayHandler;

/** Draws the camera keyframe path in the world while editing. Never drawn during export. */
public final class CameraPathRenderer {

    private CameraPathRenderer() {}

    public static void render(float partialTicks) {
        ReplayHandler h = ReplayHandler.get();
        // nor in the export screen's viewer, which shows what an export would look like
        if (h == null || Exporter.isExporting()
            || !h.editor.visuals.showCameraPath
            || GuiReplayEditor.isUiHidden()
            || Minecraft.getMinecraft().currentScreen instanceof com.flurben.flashbacknh.gui.GuiExport) return;
        KeyframeTrack track = h.editor.track(TrackType.CAMERA);
        if (track.keyframes.isEmpty()) return;
        double cx = RenderManager.renderPosX, cy = RenderManager.renderPosY, cz = RenderManager.renderPosZ;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        Tessellator t = Tessellator.instance;

        for (int pass = 0; pass < 2; pass++) {
            // pass 0: behind blocks (faint), pass 1: visible parts
            if (pass == 0) GL11.glDisable(GL11.GL_DEPTH_TEST);
            else GL11.glEnable(GL11.GL_DEPTH_TEST);
            float alpha = pass == 0 ? 0.25f : 0.9f;
            GL11.glLineWidth(2.0f);
            if (track.keyframes.size() >= 2) {
                int first = track.keyframes.get(0).tick;
                int last = track.keyframes.get(track.keyframes.size() - 1).tick;
                int steps = Math.min(2000, Math.max(2, (last - first) * 2));
                t.startDrawing(GL11.GL_LINE_STRIP);
                t.setColorRGBA_F(0.31f, 0.64f, 0.88f, alpha);
                for (int i = 0; i <= steps; i++) {
                    double tick = first + (last - first) * (i / (double) steps);
                    double[] v = track.evaluate(tick);
                    t.addVertex(v[0] - cx, v[1] - cy, v[2] - cz);
                }
                t.draw();
            }
            // keyframe markers with view direction
            for (Keyframe k : track.keyframes) {
                double x = k.values[0] - cx, y = k.values[1] - cy, z = k.values[2] - cz;
                double s = 0.15;
                t.startDrawing(GL11.GL_LINES);
                t.setColorRGBA_F(1f, 1f, 1f, alpha);
                t.addVertex(x - s, y, z);
                t.addVertex(x + s, y, z);
                t.addVertex(x, y - s, z);
                t.addVertex(x, y + s, z);
                t.addVertex(x, y, z - s);
                t.addVertex(x, y, z + s);
                double yaw = Math.toRadians(k.values[3]), pitch = Math.toRadians(k.values[4]);
                double dx = -Math.sin(yaw) * Math.cos(pitch), dy = -Math.sin(pitch),
                    dz = Math.cos(yaw) * Math.cos(pitch);
                t.setColorRGBA_F(1f, 0.8f, 0.3f, alpha);
                t.addVertex(x, y, z);
                t.addVertex(x + dx * 0.8, y + dy * 0.8, z + dz * 0.8);
                t.draw();
            }
        }
        GL11.glDepthMask(true);
        GL11.glLineWidth(1.0f);
        GL11.glPopAttrib();
    }
}
