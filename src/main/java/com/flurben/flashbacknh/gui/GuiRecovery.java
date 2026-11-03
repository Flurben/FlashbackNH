package com.flurben.flashbacknh.gui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.apache.commons.io.FileUtils;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.format.ReplayFormat;
import com.flurben.flashbacknh.format.ReplayMeta;
import com.flurben.flashbacknh.format.ReplayStream;
import com.flurben.flashbacknh.recording.RecordingManager;

/** Offers to recover recordings left behind by a crash. */
public class GuiRecovery extends GuiScreen {

    private final GuiScreen parent;
    private List<File> dirs;

    public GuiRecovery(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        dirs = RecordingManager.INSTANCE.findRecoverable();
        buttonList.clear();
        int cx = width / 2;
        buttonList.add(new GuiButton(0, cx - 154, height / 2 + 10, 100, 20, "Recover"));
        buttonList.add(new GuiButton(1, cx - 50, height / 2 + 10, 100, 20, "Delete"));
        buttonList.add(new GuiButton(2, cx + 54, height / 2 + 10, 100, 20, "Skip"));
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == 0) {
            for (File dir : dirs) recover(dir);
        } else if (b.id == 1) {
            for (File dir : dirs) {
                try {
                    FileUtils.deleteDirectory(dir);
                } catch (IOException e) {
                    FlashbackNH.LOG.warn("Unable to delete {}", dir, e);
                }
            }
        }
        mc.displayGuiScreen(parent);
    }

    private static void recover(File dir) {
        ReplayMeta meta;
        try {
            meta = ReplayMeta
                .fromJson(FileUtils.readFileToString(new File(dir, ReplayFormat.ENTRY_META), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            meta = new ReplayMeta();
            meta.createdAt = dir.lastModified();
        }
        try (ReplayStream stream = ReplayStream.open(new File(dir, ReplayFormat.ENTRY_STREAM))) {
            meta.durationTicks = stream.getTickCount();
        } catch (Throwable t) {
            FlashbackNH.LOG.warn("Recording in {} is not recoverable", dir, t);
            return;
        }
        RecordingManager.saveAsync(dir, meta, "Recovered " + RecordingManager.defaultName(meta));
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        int cx = width / 2, y = height / 2 - 50;
        drawCenteredString(fontRendererObj, "FlashbackNH: Recovery", cx, y, 0xFFFFFF);
        drawCenteredString(fontRendererObj, "Found " + dirs.size() + " unfinished recording(s).", cx, y + 16, 0xA0A0A0);
        drawCenteredString(
            fontRendererObj,
            "This usually happens when the game closed unexpectedly while recording.",
            cx,
            y + 28,
            0xA0A0A0);
        drawCenteredString(fontRendererObj, "Up to a second of gameplay at the end may be lost.", cx, y + 40, 0xA0A0A0);
        super.drawScreen(mx, my, pt);
    }
}
