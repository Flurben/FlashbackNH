package com.flurben.flashbacknh.gui;

import java.nio.ByteBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

/**
 * A copy of the frame the game rendered behind a screen, for showing it smaller inside the screen like the viewer of a
 * video editor. Copied on the GPU (glCopyTexSubImage2D) once a frame before the screen draws over it.
 */
final class ViewerTexture {

    private int texture;
    private int texWidth, texHeight;
    private boolean valid;

    /** Copies what is in the framebuffer now. Call before drawing anything over the world. */
    void capture() {
        Minecraft mc = Minecraft.getMinecraft();
        int w = mc.displayWidth, h = mc.displayHeight;
        valid = false;
        if (w <= 0 || h <= 0) return;
        try {
            if (texture == 0 || w != texWidth || h != texHeight) {
                if (texture == 0) texture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
                GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGB8,
                    w,
                    h,
                    0,
                    GL11.GL_RGB,
                    GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
                texWidth = w;
                texHeight = h;
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            if (OpenGlHelper.isFramebufferEnabled()) mc.getFramebuffer()
                .bindFramebuffer(false);
            else GL11.glReadBuffer(GL11.GL_BACK);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
            valid = true;
        } catch (Throwable t) {
            valid = false;
        }
    }

    boolean isValid() {
        return valid;
    }

    /** The aspect ratio of the captured frame. */
    double aspect() {
        return texHeight > 0 ? texWidth / (double) texHeight : 16 / 9.0;
    }

    /**
     * Draws the part of the frame between u0..u1 (left to right) and v0..v1 (top to bottom, 0 to 1) into a
     * rectangle.
     */
    void draw(double x, double y, double w, double h, double u0, double v0, double u1, double v1) {
        if (!valid) return;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        // texture rows run bottom-up
        t.addVertexWithUV(x, y + h, 0, u0, 1 - v1);
        t.addVertexWithUV(x + w, y + h, 0, u1, 1 - v1);
        t.addVertexWithUV(x + w, y, 0, u1, 1 - v0);
        t.addVertexWithUV(x, y, 0, u0, 1 - v0);
        t.draw();
        GL11.glEnable(GL11.GL_ALPHA_TEST);
    }

    void dispose() {
        if (texture != 0) GL11.glDeleteTextures(texture);
        texture = 0;
        valid = false;
    }
}
