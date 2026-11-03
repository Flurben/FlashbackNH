package com.flurben.flashbacknh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.flurben.flashbacknh.format.ReplayMeta;
import com.flurben.flashbacknh.recording.HotbarState;
import com.flurben.flashbacknh.recording.Recorder;
import com.flurben.flashbacknh.recording.RecordingManager;
import com.flurben.flashbacknh.replay.ReplayHandler;

/** In-game overlays: the recording indicator and the recorded first-person hotbar while spectating. */
public final class HudRenderer {

    private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");
    private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");
    private static final RenderItem ITEM_RENDERER = new RenderItem();

    private HudRenderer() {}

    public static void renderRecordingIndicator(ScaledResolution res) {
        Recorder r = RecordingManager.INSTANCE.getCurrent();
        if (r == null || !r.isStarted() || !com.flurben.flashbacknh.Config.showRecordingIndicator) return;
        Minecraft mc = Minecraft.getMinecraft();
        boolean blink = (System.currentTimeMillis() / 600) % 2 == 0;
        String text = (r.isPaused() ? "PAUSED " : "REC ") + ReplayMeta.formatTicks(r.getTicks());
        Gui.drawRect(2, 2, 14 + mc.fontRenderer.getStringWidth(text), 13, 0x80000000);
        if (r.isPaused() || blink) Gui.drawRect(4, 5, 9, 10, r.isPaused() ? 0xFFE0C040 : 0xFFE03030);
        mc.fontRenderer.drawStringWithShadow(text, 12, 4, 0xFFFFFF);
    }

    /** The recording player's hotbar, health and food, shown while spectating them in first person. */
    public static void renderRecordedHotbar(ScaledResolution res) {
        ReplayHandler h = ReplayHandler.get();
        Minecraft mc = Minecraft.getMinecraft();
        if (h == null || !h.editor.visuals.showRecordedHotbar || mc.gameSettings.thirdPersonView != 0) return;
        if (!h.isSpectating() || mc.renderViewEntity == null || mc.renderViewEntity.getEntityId() != h.getLocalPlayerId()) return;
        HotbarState s = h.getHotbar();
        if (s == null) return;
        int w = res.getScaledWidth(), height = res.getScaledHeight();
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        mc.getTextureManager()
            .bindTexture(WIDGETS);
        Gui gui = new Gui();
        int x = w / 2 - 91;
        gui.drawTexturedModalRect(x, height - 22, 0, 0, 182, 22);
        gui.drawTexturedModalRect(x - 1 + Math.max(0, Math.min(8, s.selectedSlot)) * 20, height - 23, 0, 22, 24, 22);

        mc.getTextureManager()
            .bindTexture(ICONS);
        // experience bar
        int xpY = height - 29;
        gui.drawTexturedModalRect(x, xpY, 0, 64, 182, 5);
        int filled = (int) (s.xpProgress * 183.0F);
        if (filled > 0) gui.drawTexturedModalRect(x, xpY, 0, 69, filled, 5);
        // health
        int heartsY = height - 39;
        int health = (int) Math.ceil(s.health);
        for (int i = 0; i < 10; i++) {
            int hx = x + i * 8;
            gui.drawTexturedModalRect(hx, heartsY, 16, 0, 9, 9);
            if (i * 2 + 1 < health) gui.drawTexturedModalRect(hx, heartsY, 52, 0, 9, 9);
            else if (i * 2 + 1 == health) gui.drawTexturedModalRect(hx, heartsY, 61, 0, 9, 9);
        }
        // armor
        if (s.armor > 0) {
            for (int i = 0; i < 10; i++) {
                int ax = x + i * 8;
                if (i * 2 + 1 < s.armor) gui.drawTexturedModalRect(ax, heartsY - 10, 34, 9, 9, 9);
                else if (i * 2 + 1 == s.armor) gui.drawTexturedModalRect(ax, heartsY - 10, 25, 9, 9, 9);
                else gui.drawTexturedModalRect(ax, heartsY - 10, 16, 9, 9, 9);
            }
        }
        // food
        for (int i = 0; i < 10; i++) {
            int fx = x + 182 - 9 - i * 8;
            gui.drawTexturedModalRect(fx, heartsY, 16, 27, 9, 9);
            if (i * 2 + 1 < s.food) gui.drawTexturedModalRect(fx, heartsY, 52, 27, 9, 9);
            else if (i * 2 + 1 == s.food) gui.drawTexturedModalRect(fx, heartsY, 61, 27, 9, 9);
        }
        if (s.xpLevel > 0) {
            String lvl = String.valueOf(s.xpLevel);
            int lx = (w - mc.fontRenderer.getStringWidth(lvl)) / 2;
            mc.fontRenderer.drawString(lvl, lx + 1, xpY - 6, 0);
            mc.fontRenderer.drawString(lvl, lx - 1, xpY - 6, 0);
            mc.fontRenderer.drawString(lvl, lx, xpY - 5, 0);
            mc.fontRenderer.drawString(lvl, lx, xpY - 7, 0);
            mc.fontRenderer.drawString(lvl, lx, xpY - 6, 0x80FF20);
        }

        // items
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        RenderHelper.enableGUIStandardItemLighting();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = s.hotbar[i];
            if (stack == null) continue;
            int ix = x + 3 + i * 20, iy = height - 19;
            try {
                ITEM_RENDERER.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, ix, iy);
                ITEM_RENDERER.renderItemOverlayIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, ix, iy);
            } catch (Throwable ignored) {}
        }
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1, 1, 1, 1);
    }
}
