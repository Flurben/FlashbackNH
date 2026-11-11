package com.flurben.flashbacknh.gui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.flurben.flashbacknh.Config;
import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.editor.EditorState;
import com.flurben.flashbacknh.export.ExportEstimator;
import com.flurben.flashbacknh.export.ExportPreset;
import com.flurben.flashbacknh.export.ExportPresets;
import com.flurben.flashbacknh.export.ExportSettings;
import com.flurben.flashbacknh.export.Exporter;
import com.flurben.flashbacknh.export.FFmpegEncoder;
import com.flurben.flashbacknh.export.VideoEncoders;
import com.flurben.flashbacknh.format.ReplayMeta;
import com.flurben.flashbacknh.recording.RecordingManager;
import com.flurben.flashbacknh.replay.ReplayHandler;

/**
 * The export screen, laid out like the deliver page of a video editor: render settings and presets on the left, a
 * viewer of the replay cropped to the export's frame in the middle, the estimated size and time with the render button
 * on the right, and the export range on a timeline at the bottom.
 */
public class GuiExport extends GuiScreen {

    private static final int TOP_H = 16;
    private static final int TIMELINE_H = 40;
    private static final int ROW = 14;
    private static final int LABEL_W = 68;
    private static final int TILE_W = 62, TILE_H = 32;

    private static final int SCREEN_BG = 0xFF0C0C10;
    private static final int PANEL_BG = 0xFF16161C;
    private static final int HEADER_BG = 0xFF1E1E26;
    private static final int GOOD = 0xFF70C070;
    private static final int BAD = 0xFFE07070;

    private static int exportCount;
    private static volatile String ffmpegVersion;
    private static volatile boolean ffmpegProbed;
    /** The ffmpeg check has finished, found or not. */
    private static volatile boolean ffmpegChecked;

    private enum Tab {
        VIDEO,
        FILE,
        ADVANCED
    }

    private enum Modal {
        NONE,
        SAVE_AS,
        RENAME,
        DELETE
    }

    private final GuiScreen parent;
    private final Ui ui = new Ui();
    private final ViewerTexture viewer = new ViewerTexture();

    /** The settings being edited; range and output are filled in when rendering. */
    private final ExportSettings draft = new ExportSettings();
    private String filename;
    /** The chosen preset, null for custom settings. */
    private String presetName;
    private Tab tab = Tab.VIDEO;
    private int presetScroll, settingsScroll;
    private String error;

    private Modal modal = Modal.NONE;
    private String modalName;
    private ExportPreset modalTarget;
    private String modalError;

    /** Timeline drag: 1 start handle, 2 end handle, 3 playhead. */
    private int dragging;
    private int dragTick;

    private int frameCountKey = Integer.MIN_VALUE;
    private int frameCount;

    private static final List<String> FORMAT_NAMES = new ArrayList<>();
    private static final List<String> ENCODER_NAMES = new ArrayList<>();
    private static final List<String> X264_PRESETS = Arrays
        .asList("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow");
    private static final List<String> LAYOUT_NAMES = new ArrayList<>();
    private static final int[][] SIZES = { { 1280, 720 }, { 1920, 1080 }, { 2560, 1440 }, { 3840, 2160 },
        { 1080, 1920 }, { 1080, 1080 }, { 854, 480 } };
    private static final List<String> SIZE_NAMES = Arrays.asList(
        "720p (1280x720)",
        "1080p (1920x1080)",
        "1440p (2560x1440)",
        "4K (3840x2160)",
        "Vertical (1080x1920)",
        "Square (1080x1080)",
        "480p (854x480)",
        "Window size");
    private static final int[] RATES = { 24, 25, 30, 48, 50, 60, 120 };
    private static final List<String> RATE_NAMES = Arrays.asList("24", "25", "30", "48", "50", "60", "120");

    static {
        FORMAT_NAMES.addAll(
            Arrays.asList(
                "MP4 (H.264 / HEVC)",
                "MKV (H.264 / HEVC)",
                "WebM (VP9)",
                "QuickTime (ProRes 422 HQ)",
                "GIF",
                "PNG sequence"));
        for (FFmpegEncoder.Mp4Layout l : FFmpegEncoder.Mp4Layout.values()) LAYOUT_NAMES.add(l.displayName);
    }

    public GuiExport(GuiScreen parent) {
        this.parent = parent;
        ReplayHandler h = ReplayHandler.get();
        EditorState.ExportSettings s = h != null ? h.editor.export : new EditorState.ExportSettings();
        draft.width = s.width > 0 ? s.width : Config.exportWidth;
        draft.height = s.height > 0 ? s.height : Config.exportHeight;
        draft.fps = s.fps > 0 ? s.fps : Config.exportFps;
        draft.crf = s.crf >= 0 ? s.crf : Config.exportCrf;
        draft.format = FFmpegEncoder.Format.parse(s.format != null ? s.format : Config.exportFormat);
        draft.encoder = VideoEncoders.Choice.parse(s.encoder != null ? s.encoder : Config.exportEncoder);
        draft.preset = s.x264Preset != null ? s.x264Preset : Config.exportPreset;
        draft.mp4Layout = FFmpegEncoder.Mp4Layout.parse(s.mp4Layout != null ? s.mp4Layout : Config.exportMp4Layout);
        draft.pngCompression = s.pngCompression >= -1 ? s.pngCompression : Config.exportPngCompression;
        draft.extraFrames = s.extraFrames >= 0 ? s.extraFrames : Config.exportExtraFrames;
        draft.waitForChunks = s.waitForChunks != null ? s.waitForChunks : Config.exportWaitForChunks;
        draft.useSpeedTrack = s.useSpeedTrack;
        draft.hideNametags = s.hideNametags;
        draft.warmupFrames = Config.exportWarmupFrames;
        filename = s.filename != null ? s.filename : Config.exportFilename;
        presetName = ExportPresets.find(s.preset) != null ? s.preset : null;
        if (h != null) {
            draft.startTick = h.editor.inPoint >= 0 ? h.editor.inPoint : 0;
            draft.endTick = h.editor.outPoint >= 0 ? h.editor.outPoint : h.getTotalTicks() - 1;
        }
        if (!ffmpegProbed) {
            ffmpegProbed = true;
            Thread t = new Thread(() -> {
                ffmpegVersion = FFmpegEncoder.probe(Config.ffmpegPath);
                ffmpegChecked = true;
                if (ffmpegVersion != null) VideoEncoders.detect(Config.ffmpegPath);
            }, "FlashbackNH ffmpeg probe");
            t.setDaemon(true);
            t.start();
        }
    }

    public static File exportsDir() {
        File f = new File(RecordingManager.rootDir(), "exports");
        f.mkdirs();
        return f;
    }

    // ------------------------------------------------------------------ input

    @Override
    public void handleMouseInput() {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) ui.queueScroll(wheel > 0 ? 1 : -1);
        super.handleMouseInput();
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        ui.queueClick(x, y, button);
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (ui.isEditing()) {
            ui.keyTyped(c, key);
            return;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            if (ui.popupOpen()) ui.closePopups();
            else if (modal != Modal.NONE) modal = Modal.NONE;
            else close();
            return;
        }
        if ((key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) && isCtrlKeyDown()) render();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        viewer.dispose();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void close() {
        remember();
        mc.displayGuiScreen(parent);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ReplayHandler h = ReplayHandler.get();
        if (h == null) {
            mc.displayGuiScreen(parent);
            return;
        }
        viewer.capture();
        ui.begin(mouseX, mouseY);
        ui.popupClicks(width, height);
        Object held = modal != Modal.NONE ? ui.holdInput() : null;

        int leftW = clamp(width * 36 / 100, 170, 230);
        int rightW = clamp(width * 24 / 100, 120, 170);
        int bottom = height - TIMELINE_H;
        ui.rect(0, 0, width, height, SCREEN_BG);
        drawTopBar(h);
        drawViewer(h, leftW, TOP_H, width - leftW - rightW, bottom - TOP_H);
        drawSettings(h, 0, TOP_H, leftW, bottom - TOP_H);
        drawSummary(h, width - rightW, TOP_H, rightW, bottom - TOP_H);
        drawTimeline(h, 0, bottom, width, TIMELINE_H);

        if (held != null) {
            ui.tooltip = null;
            ui.releaseInput(held);
            drawModal();
        }
        ui.drawPopups(width, height);
        ui.drawTooltip(width, height);
        ui.end();
    }

    private void drawTopBar(ReplayHandler h) {
        ui.rect(0, 0, width, TOP_H, HEADER_BG);
        ui.rect(0, TOP_H - 1, width, 1, Ui.BORDER);
        // page tabs, as in an editor's page bar: back to the editor, or this one
        int x = 4;
        if (ui.button("Edit", x, 2, 40, 12, false, "Back to the replay editor (Esc)")) {
            close();
            return;
        }
        x += 42;
        ui.button("Deliver", x, 2, 48, 12, true, "Export the replay as a video or images");
        x += 56;
        ui.text(ui.trim(h.meta.name, width - x - 80), x, 4, Ui.TEXT_DIM);
        String title = "Export";
        ui.text(title, width - ui.width(title) - 20, 4, Ui.TEXT);
        if (ui.button("x", width - 15, 2, 12, 12, false, "Close (Esc)")) close();
    }

    // ------------------------------------------------------------------ viewer

    private void drawViewer(ReplayHandler h, int x, int y, int w, int hh) {
        ui.rect(x, y, w, hh, 0xFF08080A);
        ui.text("Viewer", x + 6, y + 4, Ui.TEXT_DIM);
        String tc = ReplayMeta.formatTicks(h.getCurrentTick());
        ui.text(tc, x + w - ui.width(tc) - 6, y + 4, Ui.TEXT);
        int top = y + 16, avail = hh - 30;
        if (w < 40 || avail < 30) return;
        double aspect = draft.width / (double) Math.max(1, draft.height);
        int fw = w - 12, fh = (int) Math.round(fw / aspect);
        if (fh > avail) {
            fh = avail;
            fw = (int) Math.round(fh * aspect);
        }
        int fx = x + (w - fw) / 2, fy = top + (avail - fh) / 2;
        ui.rect(fx - 1, fy - 1, fw + 2, fh + 2, Ui.BORDER);
        ui.rect(fx, fy, fw, fh, 0xFF000000);
        if (viewer.isValid()) {
            // the game's vertical field of view is fixed, so a frame narrower than the window shows its middle and
            // a wider one shows more at the sides than the window has
            double wa = viewer.aspect();
            if (aspect <= wa) {
                double u = aspect / wa;
                viewer.draw(fx, fy, fw, fh, 0.5 - u / 2, 0, 0.5 + u / 2, 1);
            } else {
                int iw = (int) Math.round(fh * wa);
                int ix = fx + (fw - iw) / 2;
                viewer.draw(ix, fy, iw, fh, 0, 0, 1, 1);
                ui.rect(fx, fy, ix - fx, fh, 0xFF141418);
                ui.rect(ix + iw, fy, fx + fw - ix - iw, fh, 0xFF141418);
                if (ui.hovered(fx, fy, fw, fh)) {
                    ui.tooltip = "The export is wider than the window:\nits sides show more than this preview";
                }
            }
        } else {
            ui.textCentered("No preview", fx + fw / 2, fy + fh / 2 - 4, Ui.TEXT_DIM);
        }
        String info = draft.width + " x " + draft.height + "  " + draft.fps + " fps";
        ui.text(ui.trim(info, w - 12), x + 6, y + hh - 11, Ui.TEXT_DIM);
    }

    // ------------------------------------------------------------------ render settings

    private void drawSettings(ReplayHandler h, int x, int y, int w, int hh) {
        ui.rect(x, y, w, hh, PANEL_BG);
        ui.rect(x + w - 1, y, 1, hh, Ui.BORDER);
        int px = x + 6, pw = w - 12;
        ui.text("Render Settings", px, y + 4, Ui.TEXT);
        int cy = y + 15;
        cy = drawPresetStrip(px, cy, pw);
        cy = drawPresetBar(px, cy, pw);

        // tabs
        Tab[] tabs = Tab.values();
        String[] names = { "Video", "File", "Advanced" };
        int tw = pw / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            int bw = i == tabs.length - 1 ? pw - tw * i : tw;
            if (ui.button(names[i], px + tw * i, cy, bw, 12, tab == tabs[i], null)) {
                tab = tabs[i];
                settingsScroll = 0;
            }
        }
        cy += 15;

        int clipTop = cy, clipBottom = y + hh;
        int scroll = ui.takeScroll(x, clipTop, w, clipBottom - clipTop);
        settingsScroll = Math.max(0, settingsScroll - scroll * 14);
        scissor(x, clipTop, w, clipBottom - clipTop);
        int start = cy - settingsScroll;
        int end;
        switch (tab) {
            case FILE:
                end = drawFileTab(h, px, start, pw);
                break;
            case ADVANCED:
                end = drawAdvancedTab(px, start, pw);
                break;
            default:
                end = drawVideoTab(px, start, pw);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        int content = end - start;
        settingsScroll = Math.min(settingsScroll, Math.max(0, content - (clipBottom - clipTop) + 4));
        // clicks outside the visible part of the scrolled list must not reach it
        ui.block(x, y, w, hh);
    }

    private void scissor(int x, int y, int w, int h) {
        ScaledResolution s = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int f = s.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * f, mc.displayHeight - (y + h) * f, Math.max(0, w * f), Math.max(0, h * f));
    }

    /** The presets as tiles: "Custom" first, then built-in, then the user's. Scrolls sideways. */
    private int drawPresetStrip(int x, int y, int w) {
        List<ExportPreset> presets = ExportPresets.all();
        int count = presets.size() + 1;
        int step = TILE_W + 3;
        int maxScroll = Math.max(0, count * step - 3 - w);
        int s = ui.takeScroll(x, y, w, TILE_H);
        presetScroll = clamp(presetScroll - s * step, 0, maxScroll);
        scissor(x, y, w, TILE_H + 1);
        for (int i = 0; i < count; i++) {
            int tx = x + i * step - presetScroll;
            if (tx + TILE_W < x || tx > x + w) continue;
            ExportPreset p = i == 0 ? null : presets.get(i - 1);
            boolean selected = p == null ? presetName == null : p.name.equals(presetName);
            boolean hover = ui.hovered(tx, y, TILE_W, TILE_H) && ui.hovered(x, y, w, TILE_H);
            ui.rect(tx, y, TILE_W, TILE_H, selected ? 0xFF243A5A : hover ? Ui.HOVER : 0xFF202028);
            ui.outline(tx, y, TILE_W, TILE_H, selected ? Ui.ACCENT : Ui.BORDER);
            // a small badge for the kind of output
            String kind = p == null ? "*" : FFmpegEncoder.Format.parse(p.format).extension.toUpperCase();
            ui.rect(tx + 3, y + 3, ui.width(kind) + 4, 9, p == null ? 0xFF505060 : 0xFF35507A);
            ui.text(kind, tx + 5, y + 4, Ui.TEXT);
            if (p != null && !p.builtIn) ui.rect(tx + TILE_W - 6, y + 3, 3, 3, 0xFFE0C060);
            // next to the badge the size and rate, below it the name on up to two lines
            String sub = p == null ? "" : p.width < p.height ? "vertical" : p.height + "p";
            ui.text(ui.trim(sub, TILE_W - ui.width(kind) - 12), tx + ui.width(kind) + 9, y + 4, Ui.TEXT_DIM);
            String name = p == null ? "Custom" : p.name;
            List<String> lines = mc.fontRenderer.listFormattedStringToWidth(name, TILE_W - 5);
            for (int l = 0; l < Math.min(2, lines.size()); l++) {
                String line = l == 1 && lines.size() > 2 ? ui.trim(lines.get(1) + " " + lines.get(2), TILE_W - 5)
                    : lines.get(l);
                ui.text(line, tx + 3, y + 14 + l * 9, Ui.TEXT);
            }
            if (hover) {
                ui.tooltip = p == null ? "Custom: settings that are not a preset"
                    : p.name + "\n"
                        + p.summary()
                        + (p.builtIn ? "\nBuilt in. Right-click to duplicate." : "\nRight-click to manage");
            }
            // only the part of the tile inside the strip takes clicks
            int vx0 = Math.max(tx, x), vx1 = Math.min(tx + TILE_W, x + w);
            if (ui.clicked(vx0, y, vx1 - vx0, TILE_H, 0)) {
                if (p == null) presetName = null;
                else applyPreset(p);
            }
            if (p != null && ui.clicked(vx0, y, vx1 - vx0, TILE_H, 1)) {
                modalTarget = p;
                ui.openMenu(
                    "presetMenu",
                    p.builtIn ? Arrays.asList("Use", "Duplicate")
                        : Arrays.asList("Use", "Update with current settings", "Rename...", "Duplicate", "Delete..."),
                    ui.clickX,
                    ui.clickY);
            }
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        handlePresetMenu();
        if (maxScroll > 0) {
            // how far along the strip is
            int bar = Math.max(12, w * w / (count * step));
            int bx = x + (int) ((w - bar) * (presetScroll / (double) maxScroll));
            ui.rect(x, y + TILE_H + 2, w, 1, 0xFF26262E);
            ui.rect(bx, y + TILE_H + 2, bar, 1, Ui.TEXT_DIM);
        }
        return y + TILE_H + 6;
    }

    private void handlePresetMenu() {
        int pick = ui.takePick("presetMenu");
        if (pick < 0 || modalTarget == null) return;
        ExportPreset p = modalTarget;
        String action = p.builtIn ? (pick == 0 ? "use" : "duplicate")
            : new String[] { "use", "update", "rename", "duplicate", "delete" }[pick];
        switch (action) {
            case "use":
                applyPreset(p);
                break;
            case "update": {
                ExportPreset n = ExportPreset.of(p.name, draft);
                ExportPresets.put(n);
                presetName = n.name;
                break;
            }
            case "rename":
                openModal(Modal.RENAME, p, p.name);
                break;
            case "duplicate": {
                ExportSettings tmp = new ExportSettings();
                p.applyTo(tmp);
                ExportPresets.put(ExportPreset.of(ExportPresets.freeName(p.name + " copy"), tmp));
                presetScroll = Integer.MAX_VALUE;
                break;
            }
            case "delete":
                openModal(Modal.DELETE, p, p.name);
                break;
            default:
        }
    }

    /** The chosen preset's name, whether the settings still match it, and saving. */
    private int drawPresetBar(int x, int y, int w) {
        ExportPreset p = ExportPresets.find(presetName);
        if (p == null) presetName = null;
        boolean modified = p != null && !p.matches(draft);
        String label = p == null ? "Custom settings" : p.name + (modified ? " (modified)" : "");
        boolean canUpdate = p != null && !p.builtIn && modified;
        int bw = 44;
        int buttons = canUpdate ? bw * 2 + 2 : bw;
        ui.text(ui.trim(label, w - buttons - 4), x, y + 2, modified ? 0xFFE0C060 : Ui.TEXT_DIM);
        if (canUpdate && ui.button("Update", x + w - buttons, y, bw, 11, false, "Save these settings into " + p.name)) {
            ExportPresets.put(ExportPreset.of(p.name, draft));
        }
        if (ui.button("Save as", x + w - bw, y, bw, 11, false, "Save these settings as a new preset")) {
            String base = p != null ? p.name
                : draft.height + "p" + draft.fps + " " + draft.format.extension.toUpperCase();
            openModal(Modal.SAVE_AS, null, ExportPresets.freeName(p != null ? base + " copy" : base));
        }
        return y + 15;
    }

    private void applyPreset(ExportPreset p) {
        p.applyTo(draft);
        presetName = p.name;
        error = null;
    }

    private int row(String label, int x, int y) {
        ui.text(ui.trim(label, LABEL_W - 4), x, y + 2, Ui.TEXT_DIM);
        return x + LABEL_W;
    }

    private int drawVideoTab(int x, int y, int w) {
        int cx, cw = w - LABEL_W;
        boolean h264 = usesEncoder();

        cx = row("Format", x, y);
        int f = ui.dropdown("format", FORMAT_NAMES, draft.format.ordinal(), cx, y, cw, 12, true, null);
        draft.format = FFmpegEncoder.Format.values()[f];
        y += ROW;

        cx = row(h264 ? "Encoder" : "Codec", x, y);
        if (h264) {
            List<String> names = encoderNames();
            int e = ui.dropdown(
                "encoder",
                names,
                draft.encoder.ordinal(),
                cx,
                y,
                cw,
                12,
                true,
                "Auto uses the graphics card's encoder when ffmpeg can,\nelse x264 on the CPU");
            draft.encoder = VideoEncoders.Choice.values()[e];
        } else {
            ui.text(codecName(), cx + 2, y + 2, Ui.TEXT);
        }
        y += ROW;

        cx = row("Resolution", x, y);
        int nw = (cw - 22) / 2 - 7;
        draft.width = (int) ui.number("width", draft.width, 2, 16, 16384, "%.0f", cx, y, nw, 12);
        ui.text("x", cx + nw + 3, y + 2, Ui.TEXT_DIM);
        draft.height = (int) ui.number("height", draft.height, 2, 16, 16384, "%.0f", cx + nw + 10, y, nw, 12);
        int size = ui.dropdown("sizes", SIZE_NAMES, -1, cx + cw - 18, y, 18, 12, true, "Common sizes");
        if (size >= 0) {
            if (size < SIZES.length) {
                draft.width = SIZES[size][0];
                draft.height = SIZES[size][1];
            } else {
                draft.width = Math.max(16, mc.displayWidth & ~1);
                draft.height = Math.max(16, mc.displayHeight & ~1);
            }
        }
        y += ROW;

        cx = row("Frame rate", x, y);
        draft.fps = (int) ui.number("fps", draft.fps, 1, 1, 1000, "%.0f fps", cx, y, cw - 22, 12);
        int rate = ui.dropdown("rates", RATE_NAMES, -1, cx + cw - 18, y, 18, 12, true, "Common frame rates");
        if (rate >= 0) draft.fps = RATES[rate];
        y += ROW;

        if (draft.format == FFmpegEncoder.Format.PNG) {
            cx = row("Compression", x, y);
            draft.pngCompression = (int) ui
                .slider("pngLevel", draft.pngCompression, 0, 9, 1, "Level %.0f", cx, y, cw, 12);
            y += ROW;
            ui.text("Same images at every level: higher", x, y, Ui.TEXT_DIM);
            ui.text("is smaller and slower.", x, y + 10, Ui.TEXT_DIM);
            y += 22;
        } else if (draft.format == FFmpegEncoder.Format.MOV_PRORES || draft.format == FFmpegEncoder.Format.GIF) {
            cx = row("Quality", x, y);
            ui.text(
                draft.format == FFmpegEncoder.Format.GIF ? "256 colours, dithered" : "Fixed (ProRes 422 HQ)",
                cx + 2,
                y + 2,
                Ui.TEXT);
            y += ROW;
        } else {
            cx = row("Quality", x, y);
            draft.crf = (int) ui.slider("crf", draft.crf, 0, 51, 1, "CRF %.0f", cx, y, cw, 12);
            if (ui.hovered(cx, y, cw, 12))
                ui.tooltip = "Lower is better quality and a larger file.\n18 looks the same as the game.";
            y += ROW;
            ui.text(ui.trim(qualityName(draft.crf), cw - 2), cx + 2, y, qualityColor(draft.crf));
            y += 12;
        }

        if (h264) {
            cx = row("x264 speed", x, y);
            boolean x264 = resolvedEncoder() == VideoEncoders.Choice.LIBX264;
            int i = Math.max(0, X264_PRESETS.indexOf(draft.preset));
            i = ui.dropdown(
                "x264",
                X264_PRESETS,
                i,
                cx,
                y,
                cw,
                12,
                x264,
                x264 ? "Slower presets make smaller files at the same quality" : "Only for the x264 encoder");
            draft.preset = X264_PRESETS.get(i);
            y += ROW;
        }
        if (draft.format == FFmpegEncoder.Format.MP4) {
            cx = row("MP4 index", x, y);
            int l = ui.dropdown(
                "layout",
                LAYOUT_NAMES,
                draft.mp4Layout.ordinal(),
                cx,
                y,
                cw,
                12,
                true,
                "Standard: works everywhere, finishes at once\nFragmented: survives a crash, some old editors\nread it badly\nFast start: for the web, rewrites the file at the end");
            draft.mp4Layout = FFmpegEncoder.Mp4Layout.values()[l];
            y += ROW;
        }
        return y;
    }

    private int drawFileTab(ReplayHandler h, int x, int y, int w) {
        int cx, cw = w - LABEL_W;
        cx = row("Filename", x, y);
        filename = ui.textField("filename", filename, cx, y, cw, 12, 120, "%replay%-%date%-%time%");
        y += ROW;
        String ext = draft.format == FFmpegEncoder.Format.PNG ? "/" : "." + draft.format.extension;
        ui.text(ui.trim(expandName(filename, h, false) + ext, w), x, y, Ui.TEXT_DIM);
        y += 10;
        ui.text("%replay% %date% %time% %seq%", x, y, 0xFF606070);
        y += 13;

        cx = row("Location", x, y);
        ui.text(ui.trim("flashbacknh/exports", cw - 36), cx + 2, y + 2, Ui.TEXT);
        if (ui.button("Open", cx + cw - 32, y, 32, 12, false, exportsDir().getAbsolutePath())) {
            GuiReplaySelect.openFolder(exportsDir());
        }
        y += ROW + 4;

        int last = h.getTotalTicks() - 1;
        cx = row("Start", x, y);
        draft.startTick = (int) ui.number("start", draft.startTick, 1, 0, last, "%.0f", cx, y, cw - 50, 12);
        ui.text(ReplayMeta.formatTicks(draft.startTick), cx + cw - 46, y + 2, Ui.TEXT_DIM);
        y += ROW;
        cx = row("End", x, y);
        draft.endTick = (int) ui.number("end", draft.endTick, 1, 0, last, "%.0f", cx, y, cw - 50, 12);
        ui.text(ReplayMeta.formatTicks(draft.endTick), cx + cw - 46, y + 2, Ui.TEXT_DIM);
        y += ROW;
        int bw = (w - 2) / 2;
        if (ui.button("In/Out points", x, y, bw, 12, false, "Set the range to the editor's in and out points")) {
            draft.startTick = h.editor.inPoint >= 0 ? h.editor.inPoint : 0;
            draft.endTick = h.editor.outPoint >= 0 ? h.editor.outPoint : last;
        }
        if (ui.button("Whole replay", x + bw + 2, y, w - bw - 2, 12)) {
            draft.startTick = 0;
            draft.endTick = last;
        }
        y += ROW + 2;
        draft.useSpeedTrack = ui.checkbox("Apply speed keyframes", draft.useSpeedTrack, x, y, w);
        y += ROW;
        return y;
    }

    private int drawAdvancedTab(int x, int y, int w) {
        draft.hideNametags = ui.checkbox("Hide nametags", draft.hideNametags, x, y, w);
        y += ROW;
        draft.waitForChunks = ui.checkbox("Wait for chunks to load", draft.waitForChunks, x, y, w);
        if (ui.hovered(x, y, w, 11))
            ui.tooltip = "Keep rendering until every visible chunk is built\nbefore saving each frame";
        y += ROW;
        int cx = row("Extra frames", x, y);
        draft.extraFrames = (int) ui.number("extra", draft.extraFrames, 1, 0, 8, "%.0f", cx, y, w - LABEL_W, 12);
        if (ui.hovered(x, y, w, 12)) {
            ui.tooltip = "Frames rendered and thrown away before each saved one.\nFor shader packs with temporal effects (TAA).\nEach one adds about 75% to the export time.";
        }
        y += ROW + 4;
        ui.text("Readback: " + (Config.exportAsyncReadback ? "without waiting" : "direct"), x, y, Ui.TEXT_DIM);
        y += 10;
        ui.text("(asyncReadback in the config)", x, y, 0xFF606070);
        y += 14;
        if (ui.button("Reset estimates", x, y, w, 12, false, "Forget what past exports taught the estimates")) {
            ExportEstimator.reset();
        }
        y += ROW;
        return y;
    }

    // ------------------------------------------------------------------ summary

    private void drawSummary(ReplayHandler h, int x, int y, int w, int hh) {
        ui.rect(x, y, w, hh, PANEL_BG);
        ui.rect(x, y, 1, hh, Ui.BORDER);
        int px = x + 6, pw = w - 12;
        ui.text("Output", px, y + 4, Ui.TEXT);
        int cy = y + 17;

        ExportEstimator.Estimate e = estimate(h);
        cy = bigValue("ESTIMATED SIZE", "~ " + ExportEstimator.formatBytes(e.bytes), px, cy, pw);
        ui.text(
            ui.trim(ExportEstimator.formatBytes(e.bytesLow) + " to " + ExportEstimator.formatBytes(e.bytesHigh), pw),
            px,
            cy,
            Ui.TEXT_DIM);
        if (ui.hovered(px, cy - 22, pw, 32)) {
            ui.tooltip = "Depends mostly on how much moves on screen:\na still scene is smaller, a busy one larger";
        }
        cy += 14;
        cy = bigValue("ESTIMATED TIME", "~ " + ExportEstimator.formatSeconds(e.seconds), px, cy, pw);
        String basis = e.calibrated ? "From your last " + (e.samples == 1 ? "export" : e.samples + " exports")
            : "From test measurements";
        ui.text(ui.trim(basis, pw), px, cy, e.calibrated ? GOOD : Ui.TEXT_DIM);
        if (ui.hovered(px, cy - 22, pw, 32)) {
            ui.tooltip = e.calibrated ? "Learned from your exports with this encoder"
                : "From FlashbackNH's measurements on an RTX 4070 Ti SUPER\nand i7-13700K. Gets more exact after your first\nexport with this encoder.";
        }
        cy += 14;
        ui.separator(px, cy, pw);
        cy += 5;

        cy = detail("Length", ReplayMeta.formatTicks((int) Math.round(e.videoSeconds * 20)), px, cy, pw);
        cy = detail("Frames", String.valueOf(e.frames), px, cy, pw);
        cy = detail("Encoder", e.encoder, px, cy, pw);
        if (draft.format != FFmpegEncoder.Format.PNG && e.videoSeconds > 0) {
            double mbit = e.bytes * 8 / e.videoSeconds / 1e6;
            cy = detail("Bitrate", String.format(java.util.Locale.ROOT, "%.0f Mbit/s", mbit), px, cy, pw);
        }

        // the render button and what may stop it, at the bottom
        int by = y + hh - 24;
        String status;
        int color;
        if (draft.format == FFmpegEncoder.Format.PNG) {
            status = "No external tools needed";
            color = GOOD;
        } else if (ffmpegVersion != null) {
            status = ffmpegVersion.replaceFirst("^ffmpeg version ", "ffmpeg ");
            color = GOOD;
        } else if (!ffmpegChecked) {
            status = "Checking ffmpeg...";
            color = Ui.TEXT_DIM;
        } else {
            status = "ffmpeg not found";
            color = BAD;
        }
        if (error != null) {
            List<String> lines = mc.fontRenderer.listFormattedStringToWidth(error, pw);
            int ly = by - 12 - lines.size() * 9;
            for (String l : lines) {
                ui.text(l, px, ly, BAD);
                ly += 9;
            }
        }
        ui.text(ui.trim(status, pw), px, by - 11, color);
        if (ui.hovered(px, by - 11, pw, 9)) ui.tooltip = color == BAD
            ? "Install ffmpeg or set ffmpegPath in the FlashbackNH config,\nor export a PNG sequence"
            : ffmpegVersion;
        boolean hover = ui.hovered(px, by, pw, 20);
        ui.rect(px, by, pw, 20, hover ? 0xFF5A9AEA : Ui.ACCENT);
        ui.textCentered("Render", px + pw / 2, by + 6, 0xFFFFFFFF);
        if (hover) ui.tooltip = "Start the export (Ctrl+Enter)";
        if (ui.clicked(px, by, pw, 20, 0)) render();
    }

    /** A small caption and a value drawn one and a half times as large. */
    private int bigValue(String caption, String value, int x, int y, int w) {
        ui.text(caption, x, y, 0xFF7A7A88);
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y + 11, 0);
        GL11.glScalef(1.5f, 1.5f, 1);
        ui.text(ui.trim(value, (int) (w / 1.5)), 0, 0, Ui.TEXT);
        GL11.glPopMatrix();
        return y + 25;
    }

    private int detail(String label, String value, int x, int y, int w) {
        ui.text(label, x, y, Ui.TEXT_DIM);
        int lw = ui.width(label) + 6;
        String v = ui.trim(value, w - lw);
        ui.text(v, x + w - ui.width(v), y, Ui.TEXT);
        return y + 11;
    }

    private ExportEstimator.Estimate estimate(ReplayHandler h) {
        ExportSettings s = rangeChecked(h);
        int key = s.startTick * 31 + s.endTick * 17 + s.fps * 7 + (s.useSpeedTrack ? 1 : 0);
        if (key != frameCountKey) {
            frameCountKey = key;
            frameCount = s.endTick > s.startTick ? Exporter.countFrames(h, s) : 0;
        }
        VideoEncoders.Choice c = usesEncoder() ? resolvedEncoder() : null;
        return ExportEstimator.estimate(s, c, frameCount);
    }

    /** The draft with its range kept inside the replay. */
    private ExportSettings rangeChecked(ReplayHandler h) {
        int last = Math.max(0, h.getTotalTicks() - 1);
        draft.startTick = clamp(draft.startTick, 0, last);
        draft.endTick = clamp(draft.endTick, 0, last);
        return draft;
    }

    // ------------------------------------------------------------------ timeline

    private void drawTimeline(ReplayHandler h, int x, int y, int w, int hh) {
        ui.rect(x, y, w, hh, HEADER_BG);
        ui.rect(x, y, w, 1, Ui.BORDER);
        int total = Math.max(1, h.getTotalTicks() - 1);
        boolean paused = h.isPaused();
        if (ui.button(paused ? ">" : "||", x + 6, y + 4, 18, 12, false, paused ? "Play" : "Pause")) {
            h.setPaused(!paused);
        }
        String now = ReplayMeta.formatTicks(h.getCurrentTick()) + " / " + ReplayMeta.formatTicks(total);
        ui.text(now, x + 30, y + 6, Ui.TEXT);
        String range = "In " + ReplayMeta.formatTicks(draft.startTick)
            + "   Out "
            + ReplayMeta.formatTicks(draft.endTick)
            + "   Duration "
            + ReplayMeta.formatTicks(Math.max(0, draft.endTick - draft.startTick));
        ui.text(ui.trim(range, w - 40 - ui.width(now) - 20), x + w - 6 - ui.width(range), y + 6, Ui.TEXT_DIM);

        int bx = x + 6, bw = w - 12, by = y + 20, bh = 14;
        ui.rect(bx, by, bw, bh, 0xFF101014);
        ui.outline(bx, by, bw, bh, Ui.BORDER);
        int sx = bx + (int) ((long) draft.startTick * bw / total);
        int ex = bx + (int) ((long) draft.endTick * bw / total);
        ui.rect(sx, by + 1, Math.max(1, ex - sx), bh - 2, 0x804F8FE0);
        // second ticks along the bar, minutes taller
        int secondsPx = Math.max(1, bw * 20 / total);
        if (secondsPx >= 4) for (int t = 0; t <= total; t += 20) {
            int tx = bx + (int) ((long) t * bw / total);
            ui.rect(tx, by + bh - (t % 1200 == 0 ? 5 : 3), 1, t % 1200 == 0 ? 5 : 3, 0x40FFFFFF);
        }
        ui.rect(sx - 1, by - 2, 3, bh + 4, 0xFFE0C060);
        ui.rect(ex - 1, by - 2, 3, bh + 4, 0xFFE0C060);
        int playTick = dragging == 3 ? dragTick : h.getCurrentTick();
        int playX = bx + (int) ((long) playTick * bw / total);
        ui.rect(playX, by - 3, 1, bh + 6, 0xFFFFFFFF);

        boolean pressed = ui.clicked(bx - 4, by - 3, bw + 8, bh + 6, 0);
        if (pressed) {
            int mx = ui.clickX;
            if (Math.abs(mx - sx) <= 4 && Math.abs(mx - sx) <= Math.abs(mx - ex)) dragging = 1;
            else if (Math.abs(mx - ex) <= 4) dragging = 2;
            else dragging = 3;
        }
        if (dragging != 0) {
            // the press itself counts where it happened, later frames where the mouse is
            int mx = pressed ? ui.clickX : ui.mouseX;
            int tick = clamp((int) Math.round((mx - bx) * (double) total / bw), 0, total);
            if (dragging == 1) draft.startTick = Math.min(tick, draft.endTick - 1);
            else if (dragging == 2) draft.endTick = Math.max(tick, draft.startTick + 1);
            else dragTick = tick;
            ui.tooltip = ReplayMeta.formatTicks(dragging == 1 ? draft.startTick : dragging == 2 ? draft.endTick : tick);
            if (!Mouse.isButtonDown(0)) {
                if (dragging == 3 && tick != h.getCurrentTick()) h.seek(tick);
                dragging = 0;
            }
        } else if (ui.hovered(bx, by, bw, bh)) {
            ui.tooltip = "Drag the yellow handles to set the range,\nclick elsewhere to move the playhead";
        }
        ui.block(x, y, w, hh);
    }

    // ------------------------------------------------------------------ preset dialogs

    private void openModal(Modal m, ExportPreset target, String name) {
        modal = m;
        modalTarget = target;
        modalName = name;
        modalError = null;
        if (m != Modal.DELETE) ui.focusText("modalName", name, 40);
    }

    private void drawModal() {
        ui.rect(0, 0, width, height, 0xA0000000);
        int w = 220, hh = modal == Modal.DELETE ? 58 : 70;
        int x = (width - w) / 2, y = (height - hh) / 2;
        ui.rect(x - 1, y - 1, w + 2, hh + 2, Ui.BORDER);
        ui.rect(x, y, w, hh, PANEL_BG);
        ui.rect(x, y, w, 14, HEADER_BG);
        String title = modal == Modal.SAVE_AS ? "Save Preset"
            : modal == Modal.RENAME ? "Rename Preset" : "Delete Preset";
        ui.text(title, x + 6, y + 3, Ui.TEXT);
        boolean ok;
        if (modal == Modal.DELETE) {
            ui.text(ui.trim("Delete \"" + modalTarget.name + "\"?", w - 12), x + 6, y + 20, Ui.TEXT);
            ui.text("This cannot be undone.", x + 6, y + 30, Ui.TEXT_DIM);
            ok = ui.button("Delete", x + w - 110, y + hh - 17, 50, 13, false, null);
            if (ui.button("Cancel", x + w - 56, y + hh - 17, 50, 13, false, null)) modal = Modal.NONE;
            if (ok) {
                if (modalTarget.name.equals(presetName)) presetName = null;
                ExportPresets.remove(modalTarget);
                modal = Modal.NONE;
            }
        } else {
            ui.text("Name", x + 6, y + 21, Ui.TEXT_DIM);
            modalName = ui.textField("modalName", modalName, x + 36, y + 19, w - 42, 12, 40, "Preset name");
            boolean enter = ui.enterPressed;
            if (modalError != null) ui.text(ui.trim(modalError, w - 12), x + 6, y + 36, BAD);
            ok = ui.button(modal == Modal.SAVE_AS ? "Save" : "Rename", x + w - 110, y + hh - 17, 50, 13, false, null)
                || enter;
            if (ui.button("Cancel", x + w - 56, y + hh - 17, 50, 13, false, null)) {
                modal = Modal.NONE;
                return;
            }
            if (ok) {
                String name = modalName == null ? "" : modalName.trim();
                ExportPreset except = modal == Modal.RENAME ? modalTarget : null;
                if (!ExportPresets.nameFree(name, except)) {
                    modalError = name.isEmpty() ? "Enter a name" : "A preset with that name exists";
                    ui.focusText("modalName", name, 40);
                } else if (modal == Modal.SAVE_AS) {
                    ExportPresets.put(ExportPreset.of(name, draft));
                    presetName = name;
                    modal = Modal.NONE;
                    // show the new tile
                    presetScroll = Integer.MAX_VALUE;
                } else {
                    boolean chosen = modalTarget.name.equals(presetName);
                    ExportPresets.rename(modalTarget, name);
                    if (chosen) presetName = name;
                    modal = Modal.NONE;
                }
            }
        }
        ui.block(0, 0, width, height);
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        ReplayHandler h = ReplayHandler.get();
        if (h == null) return;
        error = null;
        ExportSettings s = copy(rangeChecked(h));
        s.width = Math.max(16, s.width & ~1);
        s.height = Math.max(16, s.height & ~1);
        s.ffmpegPath = Config.ffmpegPath;
        s.asyncReadback = Config.exportAsyncReadback;
        if (s.endTick <= s.startTick) {
            error = "The end must be after the start";
            return;
        }
        if (s.format != FFmpegEncoder.Format.PNG && ffmpegChecked && ffmpegVersion == null) {
            error = "ffmpeg not found. Install it, set its path in the config, or export PNG.";
            return;
        }
        String name = expandName(filename, h, true);
        s.output = s.format == FFmpegEncoder.Format.PNG ? RecordingManager.uniqueFile(exportsDir(), name, "")
            : RecordingManager.uniqueFile(exportsDir(), name, "." + s.format.extension);
        remember();
        try {
            Exporter.start(h, s);
        } catch (Throwable t) {
            FlashbackNH.LOG.error("Unable to start export", t);
            error = String.valueOf(t.getMessage());
            mc.displayGuiScreen(this);
        }
    }

    private static ExportSettings copy(ExportSettings d) {
        ExportSettings s = new ExportSettings();
        ExportPreset.of("", d)
            .applyTo(s);
        s.startTick = d.startTick;
        s.endTick = d.endTick;
        s.useSpeedTrack = d.useSpeedTrack;
        s.warmupFrames = d.warmupFrames;
        return s;
    }

    /** Keeps the settings with the replay, and as the defaults for replays without their own. */
    private void remember() {
        ReplayHandler h = ReplayHandler.get();
        if (h == null) return;
        EditorState.ExportSettings saved = h.editor.export;
        saved.width = draft.width;
        saved.height = draft.height;
        saved.fps = draft.fps;
        saved.crf = draft.crf;
        saved.format = draft.format.name();
        saved.filename = filename;
        saved.useSpeedTrack = draft.useSpeedTrack;
        saved.hideNametags = draft.hideNametags;
        saved.encoder = draft.encoder.name();
        saved.x264Preset = draft.preset;
        saved.mp4Layout = draft.mp4Layout.name();
        saved.pngCompression = draft.pngCompression;
        saved.extraFrames = draft.extraFrames;
        saved.waitForChunks = draft.waitForChunks;
        saved.preset = presetName;
        h.markEditorDirty();
        Config.setAndSave(Config.CAT_EXPORT, "waitForChunks", draft.waitForChunks);
        Config.setAndSave(Config.CAT_EXPORT, "extraFrames", draft.extraFrames);
        Config.setAndSave(Config.CAT_EXPORT, "preset", draft.preset);
        Config.setAndSave(Config.CAT_EXPORT, "mp4Layout", draft.mp4Layout.name());
        Config.setAndSave(Config.CAT_EXPORT, "encoder", draft.encoder.name());
        Config.setAndSave(Config.CAT_EXPORT, "pngCompression", draft.pngCompression);
    }

    // ------------------------------------------------------------------ helpers

    private boolean usesEncoder() {
        return draft.format == FFmpegEncoder.Format.MP4 || draft.format == FFmpegEncoder.Format.MKV;
    }

    /** The encoder an MP4 or MKV export would use now, Auto worked out once the check has run. */
    private VideoEncoders.Choice resolvedEncoder() {
        VideoEncoders.Choice c = draft.encoder;
        if (!VideoEncoders.detected(Config.ffmpegPath)) {
            return c == VideoEncoders.Choice.AUTO ? VideoEncoders.Choice.LIBX264 : c;
        }
        if (c == VideoEncoders.Choice.AUTO) return VideoEncoders.auto(Config.ffmpegPath);
        if (c != VideoEncoders.Choice.LIBX264 && !VideoEncoders.detect(Config.ffmpegPath)
            .contains(c)) return VideoEncoders.Choice.LIBX264;
        return c;
    }

    private List<String> encoderNames() {
        boolean known = VideoEncoders.detected(Config.ffmpegPath);
        List<String> names = new ArrayList<>();
        for (VideoEncoders.Choice c : VideoEncoders.Choice.values()) {
            String n = c.displayName;
            if (c == VideoEncoders.Choice.AUTO)
                n = known ? "Auto (" + VideoEncoders.auto(Config.ffmpegPath).displayName + ")" : "Auto (checking...)";
            else if (c == VideoEncoders.Choice.LIBX264) n = "x264 (CPU)";
            else if (known && !VideoEncoders.detect(Config.ffmpegPath)
                .contains(c)) n += " (not found)";
            names.add(n);
        }
        return names;
    }

    private String codecName() {
        switch (draft.format) {
            case WEBM:
                return "VP9 (libvpx)";
            case MOV_PRORES:
                return "ProRes 422 HQ";
            case GIF:
                return "GIF";
            default:
                return "PNG, built in";
        }
    }

    private static String qualityName(int crf) {
        if (crf <= 14) return "Near lossless, very large";
        if (crf <= 19) return "High: looks like the game";
        if (crf <= 23) return "Good";
        if (crf <= 28) return "Medium: visible in motion";
        return "Low";
    }

    private static int qualityColor(int crf) {
        return crf <= 23 ? GOOD : crf <= 28 ? 0xFFE0C060 : BAD;
    }

    private static String expandName(String pattern, ReplayHandler h, boolean count) {
        Date now = new Date();
        if (count) exportCount++;
        String p = pattern == null || pattern.trim()
            .isEmpty() ? "%replay%-%date%-%time%" : pattern;
        String s = p.replace("%date%", new SimpleDateFormat("yyyy-MM-dd").format(now))
            .replace("%time%", new SimpleDateFormat("HH_mm_ss").format(now))
            .replace("%replay%", h.meta.name)
            .replace("%seq%", String.valueOf(exportCount + (count ? 0 : 1)));
        return RecordingManager.sanitize(s);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
