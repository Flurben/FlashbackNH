package com.flurben.flashbacknh.client;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiCommandBlock;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.GuiSleepMP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.flurben.flashbacknh.Config;
import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.export.Exporter;
import com.flurben.flashbacknh.gui.GuiRecovery;
import com.flurben.flashbacknh.gui.GuiReplayEditor;
import com.flurben.flashbacknh.gui.GuiReplaySelect;
import com.flurben.flashbacknh.recording.Recorder;
import com.flurben.flashbacknh.recording.RecordingManager;
import com.flurben.flashbacknh.replay.ReplayHandler;
import com.flurben.flashbacknh.util.Notifications;

import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** All client event handling. Registered on both the Forge and the FML event bus. */
public final class ClientEvents {

    private static final int BTN_REPLAYS = 0x46424E00;
    private static final int BTN_RECORD = 0x46424E01;
    private static final int BTN_PAUSE = 0x46424E02;
    private static final int BTN_FINISH = 0x46424E03;
    private static final int BTN_CANCEL = 0x46424E04;

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean recoveryShown;

    // ------------------------------------------------------------------ ticks

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ReplayHandler h = ReplayHandler.get();
        if (h != null && mc.theWorld == null && !h.isSeeking()) ReplayHandler.onWorldGone();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            ReplayHandler h = ReplayHandler.get();
            if (h != null) {
                try {
                    h.onRenderFrame(event.renderTickTime);
                } catch (Throwable t) {
                    FlashbackNH.LOG.error("Replay frame update failed", t);
                }
            }
        } else {
            Exporter e = Exporter.get();
            if (e != null) e.onFrameRendered();
            Recorder r = RecordingManager.INSTANCE.getCurrent();
            if (r != null && r.wantsThumbnail() && mc.currentScreen == null && !mc.gameSettings.hideGUI) {
                captureThumbnail(r);
            }
        }
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (ReplayHandler.isReplaying()) return;
        if (KeyBindings.toggleRecording.isPressed()) {
            if (RecordingManager.INSTANCE.isRecording()) RecordingManager.INSTANCE.finish();
            else RecordingManager.INSTANCE.start();
        }
        if (KeyBindings.pauseRecording.isPressed()) RecordingManager.INSTANCE.togglePause();
        if (KeyBindings.openReplays.isPressed() && mc.theWorld == null) mc.displayGuiScreen(new GuiReplaySelect(mc.currentScreen));
        for (int i = 0; i < KeyBindings.markers.length; i++) {
            if (KeyBindings.markers[i].isPressed()) RecordingManager.INSTANCE.addMarker(i);
        }
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        ReplayHandler h = ReplayHandler.get();
        if (h == null || mc.currentScreen != null) return;
        if (event.dwheel != 0) {
            h.cameraController.adjustSpeed(event.dwheel);
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ GUIs

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onGuiOpen(GuiOpenEvent event) {
        GuiScreen gui = event.gui;
        if (Exporter.isExporting()) {
            if (gui != null) event.setCanceled(true);
            return;
        }
        if (ReplayHandler.isReplaying()) {
            if (gui instanceof GuiIngameMenu) {
                event.gui = new GuiReplayEditor();
            } else if (gui instanceof GuiDownloadTerrain || gui instanceof GuiSleepMP || gui instanceof GuiGameOver) {
                event.setCanceled(true);
            } else if (Config.blockReplayGuis && (gui instanceof GuiContainer || gui instanceof GuiScreenBook
                || gui instanceof GuiEditSign
                || gui instanceof GuiCommandBlock)) {
                    event.setCanceled(true);
                }
            return;
        }
        if (gui instanceof GuiMainMenu && !recoveryShown) {
            recoveryShown = true;
            if (!RecordingManager.INSTANCE.findRecoverable()
                .isEmpty()) {
                event.gui = new GuiRecovery(gui);
            }
        }
    }

    @SubscribeEvent
    @SuppressWarnings("unchecked")
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event.gui instanceof GuiMainMenu) {
            event.buttonList.add(new GuiButton(BTN_REPLAYS, 4, 4, 70, 20, "Replays"));
        } else if (event.gui instanceof GuiIngameMenu && Config.pauseMenuButtons && !ReplayHandler.isReplaying()) {
            int y = event.gui.height / 4 + 120 + 24 - 16 + 24;
            int cx = event.gui.width / 2;
            Recorder r = RecordingManager.INSTANCE.getCurrent();
            if (r == null) {
                event.buttonList.add(new GuiButton(BTN_RECORD, cx - 100, y, 200, 20, "Start Recording"));
            } else {
                event.buttonList.add(new GuiButton(BTN_PAUSE, cx - 100, y, 64, 20, r.isPaused() ? "Unpause" : "Pause"));
                event.buttonList.add(new GuiButton(BTN_FINISH, cx - 32, y, 64, 20, "Finish"));
                event.buttonList.add(new GuiButton(BTN_CANCEL, cx + 36, y, 64, 20, "Cancel"));
            }
        }
    }

    @SubscribeEvent
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        int id = event.button.id;
        switch (id) {
            case BTN_REPLAYS:
                mc.displayGuiScreen(new GuiReplaySelect(event.gui));
                break;
            case BTN_RECORD:
                mc.displayGuiScreen(null);
                RecordingManager.INSTANCE.start();
                break;
            case BTN_PAUSE:
                RecordingManager.INSTANCE.togglePause();
                mc.displayGuiScreen(null);
                break;
            case BTN_FINISH:
                mc.displayGuiScreen(null);
                RecordingManager.INSTANCE.finish();
                break;
            case BTN_CANCEL:
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiYesNo((result, i) -> {
                    if (result) RecordingManager.INSTANCE.cancel();
                    mc.displayGuiScreen(null);
                }, "Cancel the recording?", "You won't be able to recover it", "Cancel Recording", "Keep Recording", 0));
                break;
            default:
                return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        Notifications.render();
    }

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (FlashbackNH.MODID.equals(event.modID)) {
            Config.config.save();
            Config.sync();
        }
    }

    // ------------------------------------------------------------------ rendering

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        ReplayHandler h = ReplayHandler.get();
        if (h == null) return;
        switch (event.type) {
            case HOTBAR:
            case HEALTH:
            case ARMOR:
            case FOOD:
            case AIR:
            case EXPERIENCE:
            case HEALTHMOUNT:
            case JUMPBAR:
            case CROSSHAIRS:
            case HELMET:
            case PORTAL:
                event.setCanceled(true);
                break;
            case CHAT:
                if (!h.editor.visuals.showChat) event.setCanceled(true);
                break;
            case PLAYER_LIST:
                if (!h.editor.visuals.showPlayerList) event.setCanceled(true);
                break;
            case BOSSHEALTH:
                if (!h.editor.visuals.showBossBar) event.setCanceled(true);
                break;
            default:
        }
    }

    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        ScaledResolution res = event.resolution;
        if (ReplayHandler.isReplaying()) {
            HudRenderer.renderRecordedHotbar(res);
        } else {
            HudRenderer.renderRecordingIndicator(res);
        }
        if (mc.currentScreen == null) Notifications.render();
    }

    @SubscribeEvent
    public void onBlockHighlight(DrawBlockHighlightEvent event) {
        if (ReplayHandler.isReplaying()) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        if (ReplayHandler.isReplaying()) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onNametag(RenderLivingEvent.Specials.Pre event) {
        if (ReplayHandler.isReplaying() && !ReplayHandler.visuals().renderNametags) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        try {
            CameraPathRenderer.render(event.partialTicks);
        } catch (Throwable t) {
            FlashbackNH.LOG.debug("Camera path render failed", t);
        }
    }

    // ------------------------------------------------------------------ thumbnails

    private void captureThumbnail(Recorder r) {
        try {
            int w = mc.displayWidth, h = mc.displayHeight;
            if (w <= 0 || h <= 0) return;
            if (OpenGlHelper.isFramebufferEnabled()) mc.getFramebuffer()
                .bindFramebuffer(false);
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            BufferedImage full = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                int base = (h - 1 - y) * w * 4;
                for (int x = 0; x < w; x++) {
                    int i = base + x * 4;
                    row[x] = ((buf.get(i) & 0xFF) << 16) | ((buf.get(i + 1) & 0xFF) << 8) | (buf.get(i + 2) & 0xFF);
                }
                full.setRGB(0, y, w, 1, row, 0, w);
            }
            int tw = 320, th = 180;
            BufferedImage thumb = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double scale = Math.max(tw / (double) w, th / (double) h);
            int sw = (int) (w * scale), sh = (int) (h * scale);
            g.drawImage(full, (tw - sw) / 2, (th - sh) / 2, sw, sh, null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(thumb, "png", bos);
            r.saveThumbnail(bos.toByteArray());
        } catch (Throwable t) {
            FlashbackNH.LOG.debug("Thumbnail capture failed", t);
            r.saveThumbnail(new byte[0]);
        }
    }
}
