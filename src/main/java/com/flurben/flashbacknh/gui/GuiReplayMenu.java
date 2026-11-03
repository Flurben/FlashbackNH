package com.flurben.flashbacknh.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.gui.GuiYesNoCallback;

import com.flurben.flashbacknh.format.ReplayMeta;
import com.flurben.flashbacknh.replay.ReplayHandler;

import cpw.mods.fml.client.config.GuiConfig;

/** The pause menu of a replay. */
public class GuiReplayMenu extends GuiScreen implements GuiYesNoCallback {

    private final GuiScreen editor;

    public GuiReplayMenu(GuiScreen editor) {
        this.editor = editor;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int cx = width / 2, y = height / 4 + 24;
        buttonList.add(new GuiButton(0, cx - 100, y, 200, 20, "Back to Replay"));
        buttonList.add(new GuiButton(1, cx - 100, y + 24, 200, 20, "Export Video..."));
        buttonList.add(new GuiButton(2, cx - 100, y + 48, 98, 20, "Options..."));
        buttonList.add(new GuiButton(3, cx + 2, y + 48, 98, 20, "Mod Settings..."));
        buttonList.add(new GuiButton(4, cx - 100, y + 84, 200, 20, "Exit Replay"));
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        ReplayHandler h = ReplayHandler.get();
        switch (b.id) {
            case 0:
                mc.displayGuiScreen(editor != null ? editor : new GuiReplayEditor());
                break;
            case 1:
                mc.displayGuiScreen(new GuiExport(editor != null ? editor : new GuiReplayEditor()));
                break;
            case 2:
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiOptions(this, mc.gameSettings));
                break;
            case 3:
                mc.displayGuiScreen(
                    new GuiConfig(this, ConfigGuiFactory.elements(), "flashbacknh", false, false, "FlashbackNH"));
                break;
            case 4:
                if (h != null) h.exit();
                break;
            default:
        }
    }

    @Override
    public void confirmClicked(boolean result, int id) {
        mc.displayGuiScreen(this);
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Replay", width / 2, 40, 0xFFFFFF);
        ReplayHandler h = ReplayHandler.get();
        if (h != null) {
            drawCenteredString(
                fontRendererObj,
                h.meta.name + " - " + ReplayMeta.formatTicks(h.getTotalTicks()),
                width / 2,
                54,
                0xA0A0A0);
        }
        super.drawScreen(mx, my, pt);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @SuppressWarnings("unused")
    private void confirm(String q) {
        mc.displayGuiScreen(new GuiYesNo(this, q, "", 0));
    }
}
