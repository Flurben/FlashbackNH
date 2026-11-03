package com.flurben.flashbacknh.export;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.editor.KeyframeTrack;
import com.flurben.flashbacknh.editor.TrackType;
import com.flurben.flashbacknh.gui.GuiReplayEditor;
import com.flurben.flashbacknh.mixins.early.AccessorMinecraft;
import com.flurben.flashbacknh.replay.ReplayHandler;
import com.flurben.flashbacknh.util.Notifications;

/**
 * Renders the replay frame by frame and feeds the frames to an encoder.
 *
 * <p>
 * Frames are produced by the game's normal render loop: the replay clock is driven by the exporter (one step per saved
 * frame) and every frame is read back from the main framebuffer after the complete frame, including shader
 * composite passes, has been rendered. The main framebuffer is resized to the export resolution the same way a window
 * resize would do it, which keeps shader packs and post-processing consistent.
 */
public final class Exporter implements ReplayHandler.ClockDriver {

    private static Exporter active;

    public static boolean isExporting() {
        return active != null;
    }

    public static Exporter get() {
        return active;
    }

    private final Minecraft mc = Minecraft.getMinecraft();
    private final ReplayHandler handler;
    private final ExportSettings settings;
    private final FrameEncoder encoder;
    private final int totalFrames;
    private final long startedAt = System.currentTimeMillis();

    private double time;
    private boolean advance;
    private int framesWritten;
    private int warmupLeft;
    private int extraLeft;
    private int waited;
    private ByteBuffer pixels;
    private boolean finished;

    // saved state
    private boolean savedHideGui;
    private boolean savedNametags;
    private boolean savedPaused;
    private int savedWidth, savedHeight;

    private Exporter(ReplayHandler handler, ExportSettings settings, FrameEncoder encoder) {
        this.handler = handler;
        this.settings = settings;
        this.encoder = encoder;
        this.totalFrames = countFrames();
    }

    public static void start(ReplayHandler handler, ExportSettings settings) throws IOException {
        if (active != null) throw new IllegalStateException("Already exporting");
        settings.width = Math.max(16, settings.width & ~1);
        settings.height = Math.max(16, settings.height & ~1);
        if (!OpenGlHelper.isFramebufferEnabled()) {
            settings.width = Math.min(settings.width, Display.getWidth() & ~1);
            settings.height = Math.min(settings.height, Display.getHeight() & ~1);
            Notifications.show("Flashback", "Framebuffers are disabled: exporting at window size", 6000);
        }
        if (settings.endTick <= settings.startTick) throw new IOException("The export range is empty");
        FrameEncoder encoder;
        if (settings.format == FFmpegEncoder.Format.PNG) {
            encoder = new PngSequenceEncoder(settings.output);
        } else {
            encoder = new FFmpegEncoder(
                settings.ffmpegPath,
                settings.format,
                settings.output,
                settings.width,
                settings.height,
                settings.fps,
                settings.crf,
                settings.preset);
        }
        Exporter e = new Exporter(handler, settings, encoder);
        active = e;
        e.begin();
    }

    private int countFrames() {
        double t = settings.startTick;
        int n = 0;
        while (t <= settings.endTick && n < 10_000_000) {
            n++;
            t = step(t);
        }
        return n;
    }

    private double step(double t) {
        double speed = 1.0;
        KeyframeTrack track = handler.editor.track(TrackType.SPEED);
        if (settings.useSpeedTrack && track.isActive()) speed = Math.max(0.01, track.evaluate(t)[0]);
        return t + speed * 20.0 / settings.fps;
    }

    private void begin() {
        savedHideGui = mc.gameSettings.hideGUI;
        savedNametags = handler.editor.visuals.renderNametags;
        savedPaused = handler.isPaused();
        savedWidth = mc.displayWidth;
        savedHeight = mc.displayHeight;

        mc.displayGuiScreen(null);
        mc.setIngameNotInFocus();
        mc.gameSettings.hideGUI = true;
        if (settings.hideNametags) handler.editor.visuals.renderNametags = false;
        mc.getSoundHandler()
            .pauseSounds();

        ((AccessorMinecraft) mc).flashbacknh$resize(settings.width, settings.height);
        pixels = BufferUtils.createByteBuffer(settings.width * settings.height * 4);

        handler.seek(settings.startTick);
        time = settings.startTick;
        advance = false;
        warmupLeft = Math.max(1, settings.warmupFrames);
        extraLeft = settings.extraFrames;
        handler.setClockDriver(this);
        FlashbackNH.LOG.info(
            "Export started: {}x{} @ {} fps, ticks {}-{}, {} frames -> {}",
            settings.width,
            settings.height,
            settings.fps,
            settings.startTick,
            settings.endTick,
            totalFrames,
            settings.output);
    }

    @Override
    public double nextFrameTime() {
        if (advance) {
            advance = false;
            time = step(time);
        }
        return time;
    }

    /** Called at the end of every rendered frame. */
    public void onFrameRendered() {
        if (finished) return;
        if (Keyboard.isKeyDown(Keyboard.KEY_ESCAPE)) {
            cancel("Export cancelled");
            return;
        }
        if (mc.displayWidth != settings.width || mc.displayHeight != settings.height) {
            // window was resized by the user: force our size back
            ((AccessorMinecraft) mc).flashbacknh$resize(settings.width, settings.height);
            warmupLeft = Math.max(warmupLeft, 5);
        }
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        try {
            if (warmupLeft > 0) {
                warmupLeft--;
                return;
            }
            if (settings.waitForChunks && waited < 240 && !ChunkWaiter.isComplete()) {
                waited++;
                return;
            }
            if (extraLeft > 0) {
                extraLeft--;
                return;
            }
            capture();
            framesWritten++;
            waited = 0;
            extraLeft = settings.extraFrames;
            if (framesWritten >= totalFrames) {
                finish();
                return;
            }
            advance = true;
        } catch (Throwable t) {
            FlashbackNH.LOG.error("Export failed", t);
            cancel("Export failed: " + t.getMessage());
        } finally {
            if (!finished) drawProgress();
        }
    }

    private void capture() throws IOException {
        int w = settings.width, h = settings.height;
        Framebuffer fb = mc.getFramebuffer();
        if (OpenGlHelper.isFramebufferEnabled()) {
            fb.bindFramebuffer(false);
            if (fb.framebufferWidth != w || fb.framebufferHeight != h) {
                throw new IOException(
                    "Framebuffer is " + fb.framebufferWidth + "x" + fb.framebufferHeight + ", expected " + w + "x" + h);
            }
        } else {
            GL11.glReadBuffer(GL11.GL_BACK);
        }
        pixels.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        pixels.rewind();
        encoder.encode(pixels, w, h);
    }

    private void drawProgress() {
        int winW = Display.getWidth(), winH = Display.getHeight();
        int vw = Math.min(winW, mc.displayWidth), vh = Math.min(winH, mc.displayHeight);
        if (vw <= 0 || vh <= 0) return;
        ScaledResolution res = new ScaledResolution(mc, vw, vh);
        GL11.glViewport(0, 0, vw, vh);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, res.getScaledWidth_double(), res.getScaledHeight_double(), 0, 1000, 3000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0, 0, -2000);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);

        int sw = res.getScaledWidth();
        String line1 = String.format(
            "Exporting %d / %d frames (%.1f%%)",
            framesWritten,
            totalFrames,
            framesWritten * 100.0 / Math.max(1, totalFrames));
        long elapsed = System.currentTimeMillis() - startedAt;
        String eta = framesWritten > 0
            ? formatMs((long) ((double) elapsed / framesWritten * (totalFrames - framesWritten)))
            : "...";
        String line2 = "Elapsed " + formatMs(elapsed) + "  |  remaining " + eta + "  |  ESC to cancel";
        int boxW = Math.max(mc.fontRenderer.getStringWidth(line1), mc.fontRenderer.getStringWidth(line2)) + 16;
        int x = (sw - boxW) / 2, y = 6;
        Gui.drawRect(x, y, x + boxW, y + 36, 0xD0101014);
        int barW = boxW - 16;
        Gui.drawRect(x + 8, y + 26, x + 8 + barW, y + 30, 0xFF303040);
        Gui.drawRect(
            x + 8,
            y + 26,
            x + 8 + (int) (barW * framesWritten / (double) Math.max(1, totalFrames)),
            y + 30,
            0xFF4F8FE0);
        mc.fontRenderer.drawStringWithShadow(line1, x + 8, y + 4, 0xFFFFFF);
        mc.fontRenderer.drawStringWithShadow(line2, x + 8, y + 15, 0xAAAAAA);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
    }

    private static String formatMs(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private void restore() {
        handler.setClockDriver(null);
        mc.gameSettings.hideGUI = savedHideGui;
        handler.editor.visuals.renderNametags = savedNametags;
        handler.setPaused(true);
        int w = Display.getWidth(), h = Display.getHeight();
        if (w <= 0 || h <= 0) {
            w = savedWidth;
            h = savedHeight;
        }
        ((AccessorMinecraft) mc).flashbacknh$resize(w, h);
        mc.getSoundHandler()
            .resumeSounds();
        active = null;
        if (!savedPaused) handler.setPaused(false);
        mc.displayGuiScreen(new GuiReplayEditor());
    }

    private void finish() {
        finished = true;
        restore();
        final File out = settings.output;
        Notifications.show("Flashback", "Finishing export...");
        Thread t = new Thread(() -> {
            try {
                encoder.close();
                Notifications.show("Export finished", out.getName(), 8000);
                ChatComponentText msg = new ChatComponentText("[FlashbackNH] Export saved: ");
                ChatComponentText link = new ChatComponentText(out.getName());
                link.setChatStyle(
                    new ChatStyle().setColor(EnumChatFormatting.AQUA)
                        .setUnderlined(true)
                        .setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, out.getAbsolutePath())));
                msg.appendSibling(link);
                mc.func_152344_a(
                    () -> {
                        if (mc.ingameGUI != null) mc.ingameGUI.getChatGUI()
                            .printChatMessage(msg);
                    });
            } catch (Throwable e) {
                FlashbackNH.LOG.error("Export failed while finishing", e);
                Notifications.show("Export failed", String.valueOf(e.getMessage()), 10000);
            }
        }, "FlashbackNH export finish");
        t.start();
    }

    public void cancel(String message) {
        if (finished) return;
        finished = true;
        encoder.abort();
        restore();
        Notifications.show("Flashback", message, 6000);
    }

    public int getFramesWritten() {
        return framesWritten;
    }

    public int getTotalFrames() {
        return totalFrames;
    }
}
