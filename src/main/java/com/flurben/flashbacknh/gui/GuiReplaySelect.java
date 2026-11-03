package com.flurben.flashbacknh.gui;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.gui.GuiYesNoCallback;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;

import org.lwjgl.Sys;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.format.ReplayArchive;
import com.flurben.flashbacknh.format.ReplayFormat;
import com.flurben.flashbacknh.format.ReplayMeta;
import com.flurben.flashbacknh.recording.RecordingManager;
import com.flurben.flashbacknh.replay.ReplayHandler;

/** Lists saved replays. */
public class GuiReplaySelect extends GuiScreen implements GuiYesNoCallback {

    private static final int ROW_H = 38;
    private static final String[] SORTS = { "Date", "Name", "World", "Duration", "Size" };
    private static int sortMode;
    private static boolean sortDescending = true;

    private static final class Entry {

        File file;
        ReplayMeta meta;
        String error;
        long size;
        ResourceLocation thumbnail;
        DynamicTexture texture;
        boolean thumbLoaded;
    }

    private final GuiScreen parent;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<File, Entry> cache = new HashMap<>();
    private int selected = -1;
    private int scroll;
    private long lastClick;
    private String error;
    private Entry pendingOpen;
    private boolean drawnLoading;
    private int recoverable;
    private GuiButton openButton, renameButton, deleteButton;

    public GuiReplaySelect(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int cx = width / 2;
        int y = height - 52;
        buttonList.add(openButton = new GuiButton(0, cx - 154, y, 100, 20, "Open Replay"));
        buttonList.add(renameButton = new GuiButton(1, cx - 50, y, 100, 20, "Rename"));
        buttonList.add(deleteButton = new GuiButton(2, cx + 54, y, 100, 20, "Delete"));
        buttonList.add(new GuiButton(3, cx - 154, y + 24, 100, 20, "Sort: " + SORTS[sortMode] + (sortDescending ? " v" : " ^")));
        buttonList.add(new GuiButton(4, cx - 50, y + 24, 100, 20, "Open Folder"));
        buttonList.add(new GuiButton(5, cx + 54, y + 24, 100, 20, "Back"));
        recoverable = RecordingManager.INSTANCE.findRecoverable()
            .size();
        if (recoverable > 0) {
            buttonList.add(new GuiButton(6, width - 130, 6, 124, 20, "Recover (" + recoverable + ")"));
        }
        reload();
    }

    private void reload() {
        entries.clear();
        File[] files = RecordingManager.replaysDir()
            .listFiles((d, n) -> n.toLowerCase()
                .endsWith(".zip"));
        if (files != null) {
            for (File f : files) {
                Entry e = cache.get(f);
                if (e == null || e.size != f.length()) {
                    e = new Entry();
                    e.file = f;
                    e.size = f.length();
                    try {
                        e.meta = ReplayArchive.readMeta(f);
                    } catch (Throwable t) {
                        e.error = "Unreadable: " + t.getMessage();
                    }
                    cache.put(f, e);
                }
                entries.add(e);
            }
        }
        Comparator<Entry> cmp;
        switch (sortMode) {
            case 1:
                cmp = Comparator.comparing(e -> name(e).toLowerCase());
                break;
            case 2:
                cmp = Comparator.comparing(e -> e.meta == null ? "" : e.meta.worldName.toLowerCase());
                break;
            case 3:
                cmp = Comparator.comparingInt(e -> e.meta == null ? 0 : e.meta.durationTicks);
                break;
            case 4:
                cmp = Comparator.comparingLong(e -> e.size);
                break;
            default:
                cmp = Comparator.comparingLong(e -> e.meta == null ? e.file.lastModified() : e.meta.createdAt);
        }
        if (sortDescending) cmp = cmp.reversed();
        entries.sort(cmp);
        if (selected >= entries.size()) selected = entries.size() - 1;
        updateButtons();
    }

    private static String name(Entry e) {
        if (e.meta != null && e.meta.name != null && !e.meta.name.isEmpty()) return e.meta.name;
        String n = e.file.getName();
        return n.endsWith(".zip") ? n.substring(0, n.length() - 4) : n;
    }

    private void updateButtons() {
        boolean has = selected >= 0 && selected < entries.size();
        openButton.enabled = has && entries.get(selected).meta != null;
        renameButton.enabled = has;
        deleteButton.enabled = has;
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        switch (b.id) {
            case 0:
                if (selected >= 0) requestOpen(entries.get(selected));
                break;
            case 1:
                if (selected >= 0) {
                    final Entry e = entries.get(selected);
                    mc.displayGuiScreen(new GuiTextInput(this, "Rename Replay", name(e), value -> rename(e, value)));
                }
                break;
            case 2:
                if (selected >= 0) {
                    mc.displayGuiScreen(new GuiYesNo(this, "Are you sure you want to delete this replay?", "'" + name(entries.get(selected)) + "' will be lost forever!", "Delete", "Cancel", 0));
                }
                break;
            case 3:
                if (isShiftKeyDown()) sortDescending = !sortDescending;
                else sortMode = (sortMode + 1) % SORTS.length;
                b.displayString = "Sort: " + SORTS[sortMode] + (sortDescending ? " v" : " ^");
                reload();
                break;
            case 4:
                openFolder(RecordingManager.replaysDir());
                break;
            case 5:
                mc.displayGuiScreen(parent);
                break;
            case 6:
                mc.displayGuiScreen(new GuiRecovery(this));
                break;
            default:
        }
    }

    private void rename(Entry e, String value) {
        if (value == null || value.trim()
            .isEmpty()) return;
        try {
            File target = RecordingManager.uniqueFile(RecordingManager.replaysDir(), RecordingManager.sanitize(value.trim()), ".zip");
            if (e.meta != null) {
                e.meta.name = value.trim();
                Map<String, byte[]> map = new HashMap<>();
                map.put(ReplayFormat.ENTRY_META, e.meta.toJson()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                ReplayArchive.update(e.file, map);
            }
            if (!e.file.renameTo(target)) error = "Unable to rename file";
            cache.clear();
        } catch (Throwable t) {
            error = "Rename failed: " + t.getMessage();
        }
    }

    @Override
    public void confirmClicked(boolean result, int id) {
        if (result && id == 0 && selected >= 0 && selected < entries.size()) {
            Entry e = entries.get(selected);
            if (!e.file.delete()) error = "Unable to delete " + e.file.getName();
            freeThumb(e);
            cache.remove(e.file);
            selected = -1;
        }
        mc.displayGuiScreen(this);
    }

    private void requestOpen(Entry e) {
        if (e.meta == null) return;
        pendingOpen = e;
        drawnLoading = false;
    }

    @Override
    public void updateScreen() {
        if (pendingOpen != null && drawnLoading) {
            Entry e = pendingOpen;
            pendingOpen = null;
            try {
                if (mc.theWorld != null) {
                    error = "Leave the world before opening a replay";
                    return;
                }
                freeAllThumbs();
                ReplayHandler.open(e.file);
            } catch (Throwable t) {
                FlashbackNH.LOG.error("Unable to open replay {}", e.file, t);
                error = "Unable to open replay: " + t.getMessage();
                if (mc.currentScreen != this) mc.displayGuiScreen(this);
            }
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) scroll = Math.max(0, scroll - Integer.signum(wheel) * ROW_H);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        int top = 32, bottom = height - 58;
        int left = width / 2 - 160;
        if (y >= top && y < bottom && x >= left && x < left + 320) {
            int idx = (y - top + scroll) / ROW_H;
            if (idx >= 0 && idx < entries.size()) {
                long now = System.currentTimeMillis();
                if (idx == selected && now - lastClick < 300) requestOpen(entries.get(idx));
                selected = idx;
                lastClick = now;
                updateButtons();
            }
        }
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Select Replay", width / 2, 12, 0xFFFFFF);
        int top = 32, bottom = height - 58;
        int left = width / 2 - 160;
        drawRect(left - 2, top - 2, left + 322, bottom + 2, 0x80000000);
        int maxScroll = Math.max(0, entries.size() * ROW_H - (bottom - top));
        scroll = Math.min(scroll, maxScroll);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        int scale = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        GL11.glScissor(0, mc.displayHeight - bottom * scale, mc.displayWidth, (bottom - top) * scale);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (int i = 0; i < entries.size(); i++) {
            int y = top + i * ROW_H - scroll;
            if (y + ROW_H < top || y > bottom) continue;
            Entry e = entries.get(i);
            boolean hover = mx >= left && mx < left + 320 && my >= y && my < y + ROW_H && my >= top && my < bottom;
            if (i == selected) drawRect(left, y, left + 320, y + ROW_H - 2, 0x60FFFFFF);
            else if (hover) drawRect(left, y, left + 320, y + ROW_H - 2, 0x30FFFFFF);
            drawThumb(e, left + 2, y + 2, 60, 33);
            int tx = left + 68;
            drawString(fontRendererObj, fontRendererObj.trimStringToWidth(name(e), 248), tx, y + 3, 0xFFFFFF);
            if (e.meta != null) {
                String world = (e.meta.singleplayer ? "Singleplayer: " : "Server: ") + e.meta.worldName;
                drawString(fontRendererObj, fontRendererObj.trimStringToWidth(world, 248), tx, y + 14, 0x909090);
                String info = df.format(new Date(e.meta.createdAt)) + "   " + e.meta.formatDuration() + "   " + formatSize(e.size);
                drawString(fontRendererObj, info, tx, y + 25, 0x707070);
            } else {
                drawString(fontRendererObj, e.error == null ? "?" : fontRendererObj.trimStringToWidth(e.error, 248), tx, y + 14, 0xE06060);
            }
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        if (entries.isEmpty()) {
            drawCenteredString(fontRendererObj, "No replays yet. Record one from the pause menu, or enable auto-recording", width / 2, top + 20, 0x909090);
            drawCenteredString(fontRendererObj, "in the FlashbackNH settings.", width / 2, top + 32, 0x909090);
        }
        if (error != null) drawCenteredString(fontRendererObj, error, width / 2, 22, 0xFF6060);
        super.drawScreen(mx, my, pt);
        if (pendingOpen != null) {
            drawRect(0, 0, width, height, 0xC0000000);
            drawCenteredString(fontRendererObj, "Loading replay...", width / 2, height / 2 - 4, 0xFFFFFF);
            drawnLoading = true;
        }
    }

    private void drawThumb(Entry e, int x, int y, int w, int h) {
        if (!e.thumbLoaded && e.meta != null) {
            e.thumbLoaded = true;
            try {
                byte[] data = ReplayArchive.readEntry(e.file, ReplayFormat.ENTRY_THUMBNAIL);
                if (data != null) {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                    if (img != null) {
                        e.texture = new DynamicTexture(img);
                        e.thumbnail = mc.getTextureManager()
                            .getDynamicTextureLocation("flashbacknh_thumb", e.texture);
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (e.thumbnail == null) {
            drawRect(x, y, x + w, y + h, 0xFF202028);
            drawCenteredString(fontRendererObj, "?", x + w / 2, y + h / 2 - 4, 0x606060);
            return;
        }
        mc.getTextureManager()
            .bindTexture(e.thumbnail);
        GL11.glColor4f(1, 1, 1, 1);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x, y + h, 0, 0, 1);
        t.addVertexWithUV(x + w, y + h, 0, 1, 1);
        t.addVertexWithUV(x + w, y, 0, 1, 0);
        t.addVertexWithUV(x, y, 0, 0, 0);
        t.draw();
    }

    private void freeThumb(Entry e) {
        if (e.thumbnail != null) mc.getTextureManager()
            .deleteTexture(e.thumbnail);
        e.thumbnail = null;
        e.texture = null;
        e.thumbLoaded = false;
    }

    private void freeAllThumbs() {
        for (Entry e : cache.values()) freeThumb(e);
    }

    @Override
    public void onGuiClosed() {
        if (!(mc.currentScreen instanceof GuiYesNo) && !(mc.currentScreen instanceof GuiTextInput)) {
            // keep thumbnails while sub-screens are open
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f kB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static void openFolder(File dir) {
        dir.mkdirs();
        String path = dir.getAbsolutePath();
        try {
            if (Util.getOSType() == Util.EnumOS.OSX) {
                Runtime.getRuntime()
                    .exec(new String[] { "/usr/bin/open", path });
                return;
            } else if (Util.getOSType() == Util.EnumOS.WINDOWS) {
                Runtime.getRuntime()
                    .exec(new String[] { "cmd.exe", "/C", "start", "\"\"", path });
                return;
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop()
                    .open(dir);
                return;
            }
        } catch (Throwable ignored) {}
        Sys.openURL("file://" + path);
    }
}
