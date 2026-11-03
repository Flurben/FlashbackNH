package com.flurben.flashbacknh.gui;

import java.io.File;
import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.gui.GuiYesNoCallback;

import org.apache.commons.io.FileUtils;
import org.lwjgl.input.Keyboard;

import com.flurben.flashbacknh.format.ReplayMeta;
import com.flurben.flashbacknh.recording.RecordingManager;

/** Asks for a name for a just finished recording. */
public class GuiSaveReplay extends GuiScreen implements GuiYesNoCallback {

    private final GuiScreen parent;
    private final File workDir;
    private final ReplayMeta meta;
    private GuiTextField nameField;

    public GuiSaveReplay(GuiScreen parent, File workDir, ReplayMeta meta) {
        this.parent = parent;
        this.workDir = workDir;
        this.meta = meta;
    }

    public static void open(File workDir, ReplayMeta meta) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.displayGuiScreen(new GuiSaveReplay(mc.currentScreen, workDir, meta));
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        String text = nameField != null ? nameField.getText() : RecordingManager.defaultName(meta);
        nameField = new GuiTextField(fontRendererObj, width / 2 - 150, height / 2 - 20, 300, 20);
        nameField.setMaxStringLength(100);
        nameField.setText(text);
        nameField.setFocused(true);
        buttonList.add(new GuiButton(0, width / 2 - 150, height / 2 + 12, 148, 20, "Save Replay"));
        buttonList.add(new GuiButton(1, width / 2 + 2, height / 2 + 12, 148, 20, "Delete Recording"));
    }

    private void save() {
        RecordingManager.saveAsync(workDir, meta, nameField.getText()
            .trim()
            .isEmpty() ? RecordingManager.defaultName(meta)
                : nameField.getText()
                    .trim());
        mc.displayGuiScreen(parent);
        if (parent == null && mc.theWorld != null) mc.setIngameFocus();
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == 0) save();
        else mc.displayGuiScreen(new GuiYesNo(this, "Delete this recording?", "You won't be able to recover it", "Delete", "Cancel", 0));
    }

    @Override
    public void confirmClicked(boolean result, int id) {
        if (result) {
            try {
                FileUtils.deleteDirectory(workDir);
            } catch (IOException ignored) {}
            mc.displayGuiScreen(parent);
        } else {
            mc.displayGuiScreen(this);
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_RETURN) {
            save();
            return;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            // leaving without a decision keeps the recording for recovery from the replay list
            mc.displayGuiScreen(parent);
            return;
        }
        nameField.textboxKeyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int x, int y, int b) {
        super.mouseClicked(x, y, b);
        nameField.mouseClicked(x, y, b);
    }

    @Override
    public void updateScreen() {
        nameField.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Save Replay", width / 2, height / 2 - 60, 0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            "Duration " + ReplayMeta.formatTicks(meta.durationTicks) + "  -  " + meta.markers.size() + " markers",
            width / 2,
            height / 2 - 44,
            0xA0A0A0);
        drawString(fontRendererObj, "Replay name", width / 2 - 150, height / 2 - 32, 0xA0A0A0);
        nameField.drawTextBox();
        super.drawScreen(mx, my, pt);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
