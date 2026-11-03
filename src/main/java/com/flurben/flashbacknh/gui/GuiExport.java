package com.flurben.flashbacknh.gui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import org.lwjgl.input.Keyboard;

import com.flurben.flashbacknh.Config;
import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.editor.EditorState;
import com.flurben.flashbacknh.editor.TrackType;
import com.flurben.flashbacknh.export.ExportSettings;
import com.flurben.flashbacknh.export.Exporter;
import com.flurben.flashbacknh.export.FFmpegEncoder;
import com.flurben.flashbacknh.format.ReplayMeta;
import com.flurben.flashbacknh.recording.RecordingManager;
import com.flurben.flashbacknh.replay.ReplayHandler;

/** Export settings. */
public class GuiExport extends GuiScreen {

    private static int exportCount;
    private static volatile String ffmpegVersion;
    private static volatile boolean ffmpegProbed;

    private final GuiScreen parent;
    private GuiTextField widthField, heightField, fpsField, crfField, startField, endField, nameField;
    private FFmpegEncoder.Format format;
    private String preset;
    private boolean useSpeedTrack, hideNametags, waitForChunks;
    private int extraFrames;
    private String error;

    private static final String[] PRESETS = { "ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow" };
    private static final int[][] RESOLUTIONS = { { 1280, 720 }, { 1920, 1080 }, { 2560, 1440 }, { 3840, 2160 }, { 1080, 1920 }, { 1080, 1080 } };

    public GuiExport(GuiScreen parent) {
        this.parent = parent;
        ReplayHandler h = ReplayHandler.get();
        EditorState.ExportSettings s = h != null ? h.editor.export : new EditorState.ExportSettings();
        format = FFmpegEncoder.Format.parse(s.format != null ? s.format : Config.exportFormat);
        preset = Config.exportPreset;
        useSpeedTrack = s.useSpeedTrack;
        hideNametags = s.hideNametags;
        waitForChunks = Config.exportWaitForChunks;
        extraFrames = Config.exportExtraFrames;
        if (!ffmpegProbed) {
            ffmpegProbed = true;
            Thread t = new Thread(() -> ffmpegVersion = FFmpegEncoder.probe(Config.ffmpegPath), "FlashbackNH ffmpeg probe");
            t.setDaemon(true);
            t.start();
        }
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        ReplayHandler h = ReplayHandler.get();
        if (h == null) {
            mc.displayGuiScreen(parent);
            return;
        }
        EditorState.ExportSettings s = h.editor.export;
        int cx = width / 2;
        int left = cx - 155;
        int y = 40;
        buttonList.clear();

        widthField = field(left + 70, y, 50, String.valueOf(s.width > 0 ? s.width : Config.exportWidth));
        heightField = field(left + 130, y, 50, String.valueOf(s.height > 0 ? s.height : Config.exportHeight));
        buttonList.add(new GuiButton(1, left + 190, y - 1, 120, 20, "Preset resolution"));
        y += 24;
        fpsField = field(left + 70, y, 50, String.valueOf(s.fps > 0 ? s.fps : Config.exportFps));
        crfField = field(left + 190, y, 40, String.valueOf(s.crf >= 0 ? s.crf : Config.exportCrf));
        y += 24;
        buttonList.add(new GuiButton(2, left, y, 150, 20, "Format: " + format.displayName));
        buttonList.add(new GuiButton(3, left + 160, y, 150, 20, "x264 preset: " + preset));
        y += 24;
        int start = h.editor.inPoint >= 0 ? h.editor.inPoint : 0;
        int end = h.editor.outPoint >= 0 ? h.editor.outPoint : h.getTotalTicks() - 1;
        startField = field(left + 70, y, 60, String.valueOf(start));
        endField = field(left + 190, y, 60, String.valueOf(end));
        y += 24;
        buttonList.add(new GuiButton(4, left, y, 150, 20, "Speed keyframes: " + (useSpeedTrack ? "On" : "Off")));
        buttonList.add(new GuiButton(5, left + 160, y, 150, 20, "Nametags: " + (hideNametags ? "Hidden" : "Shown")));
        y += 24;
        buttonList.add(new GuiButton(6, left, y, 150, 20, "Wait for chunks: " + (waitForChunks ? "On" : "Off")));
        buttonList.add(new GuiButton(7, left + 160, y, 150, 20, "Extra frames: " + extraFrames));
        y += 24;
        String defName = s.filename != null ? s.filename : Config.exportFilename;
        nameField = field(left + 70, y, 240, defName);
        nameField.setMaxStringLength(200);
        y += 30;
        buttonList.add(new GuiButton(0, cx - 155, y, 150, 20, "Start Export"));
        buttonList.add(new GuiButton(9, cx + 5, y, 150, 20, "Cancel"));
        buttonList.add(new GuiButton(8, cx - 155, y + 24, 310, 20, "Open exports folder"));
    }

    private GuiTextField field(int x, int y, int w, String text) {
        GuiTextField f = new GuiTextField(fontRendererObj, x, y, w, 18);
        f.setMaxStringLength(16);
        f.setText(text);
        return f;
    }

    private GuiTextField[] fields() {
        return new GuiTextField[] { widthField, heightField, fpsField, crfField, startField, endField, nameField };
    }

    public static File exportsDir() {
        File f = new File(RecordingManager.rootDir(), "exports");
        f.mkdirs();
        return f;
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        switch (b.id) {
            case 0:
                startExport();
                return;
            case 1: {
                int w = parseInt(widthField, 1920), hh = parseInt(heightField, 1080);
                int idx = 0;
                for (int i = 0; i < RESOLUTIONS.length; i++) {
                    if (RESOLUTIONS[i][0] == w && RESOLUTIONS[i][1] == hh) idx = (i + 1) % RESOLUTIONS.length;
                }
                widthField.setText(String.valueOf(RESOLUTIONS[idx][0]));
                heightField.setText(String.valueOf(RESOLUTIONS[idx][1]));
                return;
            }
            case 2:
                format = FFmpegEncoder.Format.values()[(format.ordinal() + 1) % FFmpegEncoder.Format.values().length];
                b.displayString = "Format: " + format.displayName;
                return;
            case 3: {
                int i = 0;
                for (int k = 0; k < PRESETS.length; k++) if (PRESETS[k].equals(preset)) i = k;
                preset = PRESETS[(i + 1) % PRESETS.length];
                b.displayString = "x264 preset: " + preset;
                return;
            }
            case 4:
                useSpeedTrack = !useSpeedTrack;
                b.displayString = "Speed keyframes: " + (useSpeedTrack ? "On" : "Off");
                return;
            case 5:
                hideNametags = !hideNametags;
                b.displayString = "Nametags: " + (hideNametags ? "Hidden" : "Shown");
                return;
            case 6:
                waitForChunks = !waitForChunks;
                b.displayString = "Wait for chunks: " + (waitForChunks ? "On" : "Off");
                return;
            case 7:
                extraFrames = (extraFrames + (isShiftKeyDown() ? 7 : 1)) % 9;
                b.displayString = "Extra frames: " + extraFrames;
                return;
            case 8:
                GuiReplaySelect.openFolder(exportsDir());
                return;
            case 9:
                mc.displayGuiScreen(parent);
                return;
            default:
        }
    }

    private void startExport() {
        ReplayHandler h = ReplayHandler.get();
        if (h == null) return;
        ExportSettings s = new ExportSettings();
        s.width = parseInt(widthField, 1920);
        s.height = parseInt(heightField, 1080);
        s.fps = Math.max(1, Math.min(1000, parseInt(fpsField, 60)));
        s.crf = Math.max(0, Math.min(63, parseInt(crfField, 18)));
        s.format = format;
        s.preset = preset;
        s.startTick = Math.max(0, parseInt(startField, 0));
        s.endTick = Math.min(h.getTotalTicks() - 1, parseInt(endField, h.getTotalTicks() - 1));
        s.useSpeedTrack = useSpeedTrack;
        s.hideNametags = hideNametags;
        s.waitForChunks = waitForChunks;
        s.extraFrames = extraFrames;
        s.warmupFrames = Config.exportWarmupFrames;
        s.ffmpegPath = Config.ffmpegPath;
        if (s.width < 16 || s.height < 16 || s.width > 16384 || s.height > 16384) {
            error = "Invalid resolution";
            return;
        }
        if (s.endTick <= s.startTick) {
            error = "End tick must be after start tick";
            return;
        }
        if (format != FFmpegEncoder.Format.PNG && ffmpegProbed && ffmpegVersion == null) {
            error = "ffmpeg not found ('" + Config.ffmpegPath + "'). Install it, set its path in the config, or export PNG.";
            return;
        }
        String name = expandName(nameField.getText(), h);
        File out = format == FFmpegEncoder.Format.PNG ? RecordingManager.uniqueFile(exportsDir(), name, "")
            : RecordingManager.uniqueFile(exportsDir(), name, "." + format.extension);
        s.output = out;

        EditorState.ExportSettings saved = h.editor.export;
        saved.width = s.width;
        saved.height = s.height;
        saved.fps = s.fps;
        saved.crf = s.crf;
        saved.format = format.name();
        saved.filename = nameField.getText();
        saved.useSpeedTrack = useSpeedTrack;
        saved.hideNametags = hideNametags;
        h.markEditorDirty();
        Config.setAndSave(Config.CAT_EXPORT, "waitForChunks", waitForChunks);
        Config.setAndSave(Config.CAT_EXPORT, "extraFrames", extraFrames);
        Config.setAndSave(Config.CAT_EXPORT, "preset", preset);
        try {
            Exporter.start(h, s);
        } catch (Throwable t) {
            FlashbackNH.LOG.error("Unable to start export", t);
            error = String.valueOf(t.getMessage());
            mc.displayGuiScreen(this);
        }
    }

    private static String expandName(String pattern, ReplayHandler h) {
        Date now = new Date();
        exportCount++;
        String s = pattern.replace("%date%", new SimpleDateFormat("yyyy-MM-dd").format(now))
            .replace("%time%", new SimpleDateFormat("HH_mm_ss").format(now))
            .replace("%replay%", h.meta.name)
            .replace("%seq%", String.valueOf(exportCount));
        return RecordingManager.sanitize(s);
    }

    private static int parseInt(GuiTextField f, int def) {
        try {
            return Integer.parseInt(
                f.getText()
                    .trim());
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        for (GuiTextField f : fields()) {
            if (f.isFocused()) {
                if (f != nameField && c >= ' ' && !Character.isDigit(c)) return;
                f.textboxKeyTyped(c, key);
                return;
            }
        }
        if (key == Keyboard.KEY_RETURN) startExport();
    }

    @Override
    protected void mouseClicked(int x, int y, int b) {
        super.mouseClicked(x, y, b);
        for (GuiTextField f : fields()) f.mouseClicked(x, y, b);
    }

    @Override
    public void updateScreen() {
        for (GuiTextField f : fields()) f.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        int cx = width / 2;
        int left = cx - 155;
        drawCenteredString(fontRendererObj, "Export Video", cx, 14, 0xFFFFFF);
        int y = 40;
        drawString(fontRendererObj, "Resolution", left, y + 5, 0xA0A0A0);
        drawString(fontRendererObj, "x", left + 123, y + 5, 0xA0A0A0);
        y += 24;
        drawString(fontRendererObj, "Framerate", left, y + 5, 0xA0A0A0);
        drawString(fontRendererObj, "Quality (CRF)", left + 125, y + 5, 0xA0A0A0);
        y += 48;
        drawString(fontRendererObj, "Start tick", left, y + 5, 0xA0A0A0);
        drawString(fontRendererObj, "End tick", left + 140, y + 5, 0xA0A0A0);
        ReplayHandler h = ReplayHandler.get();
        if (h != null) {
            int s = parseInt(startField, 0), e = parseInt(endField, 0);
            String dur = ReplayMeta.formatTicks(Math.max(0, e - s));
            if (useSpeedTrack && h.editor.track(TrackType.SPEED).isActive()) dur += " (before speed keyframes)";
            drawString(fontRendererObj, dur, left + 258, y + 5, 0x707070);
        }
        y += 72;
        drawString(fontRendererObj, "Filename", left, y + 5, 0xA0A0A0);
        for (GuiTextField f : fields()) f.drawTextBox();
        super.drawScreen(mx, my, pt);

        String status;
        int color;
        if (format == FFmpegEncoder.Format.PNG) {
            status = "PNG sequence: no external tools needed";
            color = 0x70C070;
        } else if (!ffmpegProbed || (ffmpegVersion == null && ffmpegProbed && isProbing())) {
            status = "Checking ffmpeg...";
            color = 0xA0A0A0;
        } else if (ffmpegVersion != null) {
            status = ffmpegVersion.length() > 60 ? ffmpegVersion.substring(0, 60) : ffmpegVersion;
            color = 0x70C070;
        } else {
            status = "ffmpeg not found - set 'ffmpegPath' in the FlashbackNH config or export PNG";
            color = 0xE07070;
        }
        drawCenteredString(fontRendererObj, status, cx, height - 36, color);
        drawCenteredString(fontRendererObj, "Audio is not exported. Tip: use the in/out points (I/O) to set the range.", cx, height - 24, 0x707070);
        if (error != null) drawCenteredString(fontRendererObj, error, cx, 28, 0xFF6060);
    }

    private static boolean isProbing() {
        return false;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
