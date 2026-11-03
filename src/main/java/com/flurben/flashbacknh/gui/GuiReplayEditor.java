package com.flurben.flashbacknh.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.flurben.flashbacknh.Config;
import com.flurben.flashbacknh.editor.EditorState;
import com.flurben.flashbacknh.editor.Interpolation;
import com.flurben.flashbacknh.editor.Keyframe;
import com.flurben.flashbacknh.editor.KeyframeTrack;
import com.flurben.flashbacknh.editor.TrackType;
import com.flurben.flashbacknh.editor.Visuals;
import com.flurben.flashbacknh.format.ReplayMeta;
import com.flurben.flashbacknh.replay.CameraEntity;
import com.flurben.flashbacknh.replay.ReplayHandler;
import com.flurben.flashbacknh.util.Notifications;

/**
 * The replay editor: a transparent overlay over the replay world with a menu bar, a timeline with keyframe tracks and
 * optional side panels. Hold the right mouse button over the world to look around and fly with the movement keys.
 */
public class GuiReplayEditor extends GuiScreen {

    private static final int MENU_H = 14;
    private static final int CONTROLS_H = 18;
    private static final int RULER_H = 14;
    private static final int TRACK_H = 13;
    private static final int ZOOM_H = 7;
    private static final int LABEL_W = 96;
    private static final int PANEL_W = 170;

    private enum Panel {
        NONE,
        PLAYERS,
        VISUALS,
        INSPECTOR,
        MOVEMENT,
        HELP
    }

    private static final class Selection {

        final KeyframeTrack track;
        final Keyframe keyframe;
        int originalTick;

        Selection(KeyframeTrack track, Keyframe keyframe) {
            this.track = track;
            this.keyframe = keyframe;
        }
    }

    private static final List<Object[]> clipboard = new ArrayList<>();
    private static Panel lastPanel = Panel.NONE;
    private static boolean hideUi;

    private final Ui ui = new Ui();
    private Panel panel = lastPanel;
    private final List<Selection> selection = new ArrayList<>();
    private String openMenu;
    private String justClosedMenu;
    private int menuX;
    private List<String> contextItems;
    private List<Runnable> contextActions;
    private int contextX, contextY;

    private boolean grabbed;
    private boolean scrubbing;
    private int scrubTick = -1;
    private boolean draggingKeys;
    private int dragStartX;
    private boolean draggingZoom;
    private double zoomDragOffset;
    private int panelScroll;
    private long lastClickTime;
    private Keyframe lastClickedKey;

    private double viewStart;
    private double viewLen;

    // layout (computed per frame)
    private int tlTop, tlX, tlW, tracksTop, viewportRight;

    public boolean isCameraGrabbed() {
        return grabbed;
    }

    public static boolean isUiHidden() {
        return hideUi;
    }

    private ReplayHandler handler() {
        return ReplayHandler.get();
    }

    @Override
    public void initGui() {
        ReplayHandler h = handler();
        if (h == null) return;
        EditorState e = h.editor;
        int total = Math.max(1, h.getTotalTicks() - 1);
        if (e.zoomTicks <= 0 || e.zoomTicks > total) {
            viewStart = 0;
            viewLen = total;
        } else {
            viewLen = e.zoomTicks;
            viewStart = e.scrollTick;
        }
        clampView();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        releaseGrab();
        lastPanel = panel;
        ReplayHandler h = handler();
        if (h != null) {
            h.editor.zoomTicks = viewLen;
            h.editor.scrollTick = viewStart;
        }
        Keyboard.enableRepeatEvents(false);
    }

    // ------------------------------------------------------------------ input

    @Override
    public void handleMouseInput() {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            if (grabbed) {
                ReplayHandler h = handler();
                if (h != null) h.cameraController.adjustSpeed(wheel);
            } else {
                ui.queueScroll(wheel > 0 ? 1 : -1);
            }
        }
        super.handleMouseInput();
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        if (grabbed) return;
        if (hideUi) {
            if (button == 1) startGrab();
            return;
        }
        if (button == 1 && isInViewport(x, y) && contextItems == null && openMenu == null) {
            startGrab();
            return;
        }
        // timeline direct manipulation (handled immediately for responsive dragging)
        if (button == 0 && contextItems == null && openMenu == null && handleTimelinePress(x, y)) return;
        ui.queueClick(x, y, button);
    }

    @Override
    protected void mouseMovedOrUp(int x, int y, int button) {
        if (button == 1 && grabbed) {
            releaseGrab();
            return;
        }
        if (button == 0) {
            ReplayHandler h = handler();
            if (scrubbing && h != null) {
                scrubbing = false;
                if (scrubTick >= 0) h.seek(scrubTick);
                scrubTick = -1;
            }
            if (draggingKeys && h != null) {
                draggingKeys = false;
                if (!selection.isEmpty()) {
                    for (Selection s : selection) s.track.sort();
                    h.markEditorDirty();
                }
            }
            draggingZoom = false;
        }
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long time) {
        ReplayHandler h = handler();
        if (h == null || button != 0) return;
        if (scrubbing) {
            scrubTick = tickAt(x);
            if (scrubTick > h.getCurrentTick()) h.seek(scrubTick);
            if (isCtrlKeyDown()) previewCameraAt(scrubTick);
        } else if (draggingKeys) {
            int delta = (int) Math.round((x - dragStartX) * viewLen / Math.max(1, tlW));
            for (Selection s : selection) {
                s.keyframe.tick = MathHelper.clamp_int(s.originalTick + delta, 0, h.getTotalTicks() - 1);
            }
        } else if (draggingZoom) {
            int total = Math.max(1, h.getTotalTicks() - 1);
            viewStart = (x - tlX) * (double) total / tlW - zoomDragOffset;
            clampView();
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        ReplayHandler h = handler();
        if (h == null) return;
        if (key == Keyboard.KEY_ESCAPE) {
            if (contextItems != null || openMenu != null) {
                contextItems = null;
                openMenu = null;
                return;
            }
            if (hideUi) {
                hideUi = false;
                return;
            }
            mc.displayGuiScreen(new GuiReplayMenu(this));
            return;
        }
        if (grabbed) return; // movement keys
        if (key == Keyboard.KEY_F1) {
            hideUi = !hideUi;
            return;
        }
        switch (key) {
            case Keyboard.KEY_SPACE:
                h.setPaused(!h.isPaused());
                break;
            case Keyboard.KEY_LEFT:
                h.seekRelative(isShiftKeyDown() ? -20 : -1);
                break;
            case Keyboard.KEY_RIGHT:
                h.seekRelative(isShiftKeyDown() ? 20 : 1);
                break;
            case Keyboard.KEY_UP:
                jumpKeyframe(true);
                break;
            case Keyboard.KEY_DOWN:
                jumpKeyframe(false);
                break;
            case Keyboard.KEY_I:
                h.editor.inPoint = h.getCurrentTick();
                if (h.editor.outPoint >= 0 && h.editor.outPoint <= h.editor.inPoint) h.editor.outPoint = -1;
                h.markEditorDirty();
                break;
            case Keyboard.KEY_O:
                h.editor.outPoint = h.getCurrentTick();
                if (h.editor.inPoint >= h.editor.outPoint) h.editor.inPoint = -1;
                h.markEditorDirty();
                break;
            case Keyboard.KEY_K:
                addKeyframe(TrackType.CAMERA);
                break;
            case Keyboard.KEY_M:
                addMarker();
                break;
            case Keyboard.KEY_DELETE:
            case Keyboard.KEY_BACK:
                deleteSelection();
                break;
            case Keyboard.KEY_C:
                if (isCtrlKeyDown()) copySelection();
                break;
            case Keyboard.KEY_V:
                if (isCtrlKeyDown()) paste();
                break;
            case Keyboard.KEY_A:
                if (isCtrlKeyDown()) selectAll();
                break;
            case Keyboard.KEY_E:
                if (isCtrlKeyDown()) mc.displayGuiScreen(new GuiExport(this));
                break;
            default:
        }
    }

    private void startGrab() {
        grabbed = true;
        mc.mouseHelper.grabMouseCursor();
        mc.mouseHelper.mouseXYChange(); // discard accumulated delta
    }

    private void releaseGrab() {
        if (!grabbed) return;
        grabbed = false;
        mc.mouseHelper.ungrabMouseCursor();
        ReplayHandler h = handler();
        if (h != null) h.cameraController.reset();
    }

    private boolean isInViewport(int x, int y) {
        if (hideUi) return true;
        return y > MENU_H && y < tlTop && x < viewportRight;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ReplayHandler h = handler();
        if (h == null) {
            mc.displayGuiScreen(null);
            return;
        }
        if (grabbed) {
            if (!Mouse.isButtonDown(1)) {
                releaseGrab();
            } else {
                mc.mouseHelper.mouseXYChange();
                float f = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
                float f1 = f * f * f * 8.0F;
                CameraEntity camera = h.getCamera();
                if (camera != null && !h.isSpectating()) {
                    camera.setAngles(
                        mc.mouseHelper.deltaX * f1,
                        mc.mouseHelper.deltaY * f1 * (mc.gameSettings.invertMouse ? -1 : 1));
                    camera.snapInterpolation();
                    camera.remember();
                }
            }
        }

        ui.begin(mouseX, mouseY);
        computeLayout();
        drawGuides(h);
        if (hideUi) {
            ui.text("UI hidden - F1 to show", 4, 4, 0x80FFFFFF);
            ui.end();
            return;
        }

        // popups first so they get click priority, but they are drawn last
        justClosedMenu = null;
        boolean popupClick = handlePopupClicks();

        drawTopBar(h);
        drawTimeline(h);
        if (panel != Panel.NONE) drawPanel(h);
        if (!popupClick) {
            // clicks that hit nothing close menus
        }
        drawPopups(h);
        if (grabbed) {
            ui.textCentered(
                String.format("Flying speed: %.1f blocks/s (scroll to change)", h.cameraController.getSpeed()),
                (viewportRight) / 2,
                MENU_H + 6,
                0xC0FFFFFF);
        } else if (isInViewport(mouseX, mouseY) && contextItems == null && openMenu == null) {
            ui.textCentered("Hold right mouse to fly", viewportRight / 2, tlTop - 12, 0x60FFFFFF);
        }
        ui.drawTooltip(width, height);
        ui.end();
    }

    private void computeLayout() {
        int tlH = CONTROLS_H + RULER_H + TRACK_H * TrackType.values().length + ZOOM_H + 6;
        tlTop = height - tlH;
        viewportRight = panel != Panel.NONE ? width - PANEL_W : width;
        tlX = LABEL_W + 4;
        tlW = Math.max(20, width - tlX - 6);
        tracksTop = tlTop + CONTROLS_H + RULER_H;
    }

    private void drawGuides(ReplayHandler h) {
        Visuals v = h.editor.visuals;
        int top = hideUi ? 0 : MENU_H;
        int bottom = hideUi ? height : tlTop;
        int right = hideUi ? width : viewportRight;
        if (v.ruleOfThirds) {
            int w = right, hh = bottom - top;
            ui.rect(w / 3, top, 1, hh, 0x60FFFFFF);
            ui.rect(w * 2 / 3, top, 1, hh, 0x60FFFFFF);
            ui.rect(0, top + hh / 3, w, 1, 0x60FFFFFF);
            ui.rect(0, top + hh * 2 / 3, w, 1, 0x60FFFFFF);
        }
        if (v.centerGuide) {
            int cx = right / 2, cy = (top + bottom) / 2;
            ui.rect(cx - 6, cy, 13, 1, 0x80FFFFFF);
            ui.rect(cx, cy - 6, 1, 13, 0x80FFFFFF);
        }
    }

    private void drawTopBar(ReplayHandler h) {
        ui.rect(0, 0, width, MENU_H, Ui.BG);
        String[] menus = { "File", "Window", "View", "Help" };
        int x = 4;
        for (String m : menus) {
            int w = ui.width(m) + 10;
            boolean open = m.equals(openMenu);
            if (open || ui.hovered(x, 0, w, MENU_H)) ui.rect(x, 0, w, MENU_H, open ? Ui.ACTIVE : Ui.HOVER);
            ui.text(m, x + 5, 3, Ui.TEXT);
            if (ui.clicked(x, 0, w, MENU_H, 0)) {
                openMenu = open || m.equals(justClosedMenu) ? null : m;
                menuX = x;
            }
            x += w;
        }
        String title = ReplayMeta.formatTicks(h.getCurrentTick()) + " / "
            + ReplayMeta.formatTicks(h.getTotalTicks() - 1)
            + "  -  "
            + h.meta.name;
        ui.text(ui.trim(title, width - x - 80), x + 10, 3, Ui.TEXT_DIM);
        if (h.isSpectating()) {
            Entity e = h.getSpectating();
            String s = "Spectating " + (e == null ? "?" : e.getCommandSenderName());
            int sw = ui.width(s) + 40;
            ui.text(s, width - sw, 3, 0xFFE0C060);
            if (ui.button("Stop", width - 34, 1, 30, 12)) h.stopSpectating();
        }
        ui.block(0, 0, width, MENU_H);
    }

    // ------------------------------------------------------------------ timeline

    private void drawTimeline(ReplayHandler h) {
        EditorState e = h.editor;
        int total = Math.max(1, h.getTotalTicks() - 1);
        ui.rect(0, tlTop, width, height - tlTop, Ui.BG);
        ui.separator(0, tlTop, width);

        // zoom with scroll wheel over the timeline
        int scroll = ui.takeScroll(tlX, tlTop + CONTROLS_H, tlW, height - tlTop - CONTROLS_H);
        if (scroll != 0) {
            double anchor = tickAtD(ui.mouseX);
            double factor = scroll > 0 ? 0.8 : 1.25;
            double newLen = MathHelper.clamp_double(viewLen * factor, 20, total);
            viewStart = anchor - (anchor - viewStart) * (newLen / viewLen);
            viewLen = newLen;
            clampView();
        }

        // controls row
        int y = tlTop + 2;
        int bx = 4;
        if (ui.button("|<", bx, y, 18, 14, false, "Skip backwards " + Config.skipSeconds + "s"))
            h.seekRelative(-Config.skipSeconds * 20);
        bx += 20;
        if (ui.button("-", bx, y, 14, 14, false, "Slow down")) h.setSpeed(slower(h.getSpeed()));
        bx += 16;
        if (ui.button(
            h.isPaused() ? ">" : "||",
            bx,
            y,
            22,
            14,
            !h.isPaused(),
            h.isPaused() ? "Play (Space)" : "Pause (Space)")) h.setPaused(!h.isPaused());
        bx += 24;
        if (ui.button("+", bx, y, 14, 14, false, "Speed up")) h.setSpeed(faster(h.getSpeed()));
        bx += 16;
        if (ui.button(">|", bx, y, 18, 14, false, "Skip forwards " + Config.skipSeconds + "s"))
            h.seekRelative(Config.skipSeconds * 20);
        bx += 22;
        ui.text(String.format("%.2fx", h.getSpeed()), bx, y + 3, Ui.TEXT);
        bx += 36;
        int rx = width - 4;
        rx -= 54;
        if (ui.button("Export", rx, y, 52, 14, false, "Export video (Ctrl+E)"))
            mc.displayGuiScreen(new GuiExport(this));
        rx -= 60;
        boolean apply = h.isApplyKeyframes();
        if (ui.button(apply ? "Keys: On" : "Keys: Off", rx, y, 58, 14, apply, "Apply keyframes during playback"))
            h.setApplyKeyframes(!apply);
        rx -= 40;
        if (ui.button("Clear", rx, y, 38, 14, false, "Clear in/out points")) {
            e.inPoint = -1;
            e.outPoint = -1;
            h.markEditorDirty();
        }
        rx -= 32;
        if (ui.button("Out", rx, y, 30, 14, e.outPoint >= 0, "Set out point (O)")) keyTyped('o', Keyboard.KEY_O);
        rx -= 32;
        if (ui.button("In", rx, y, 30, 14, e.inPoint >= 0, "Set in point (I)")) keyTyped('i', Keyboard.KEY_I);
        rx -= 50;
        if (ui.button("Marker", rx, y, 48, 14, false, "Add marker (M)")) addMarker();
        String time = "Tick " + h.getCurrentTick();
        if (ui.width(time) <= rx - bx - 4) ui.text(time, bx, y + 3, Ui.TEXT_DIM);

        // ruler
        int rulerY = tlTop + CONTROLS_H;
        ui.rect(tlX, rulerY, tlW, RULER_H, Ui.PANEL);
        double ticksPerPx = viewLen / tlW;
        double[] steps = { 1, 2, 5, 10, 20, 40, 100, 200, 400, 600, 1200, 2400, 6000, 12000, 36000, 72000 };
        double step = steps[steps.length - 1];
        for (double s : steps) {
            if (s / ticksPerPx >= 55) {
                step = s;
                break;
            }
        }
        double first = Math.ceil(viewStart / step) * step;
        for (double t = first; t <= viewStart + viewLen; t += step) {
            int px = xAt(t);
            ui.rect(px, rulerY + RULER_H - 4, 1, 4, Ui.TEXT_DIM);
            String label = step < 20 ? (int) t + "t" : ReplayMeta.formatTicks((int) t);
            ui.text(label, px + 2, rulerY + 2, Ui.TEXT_DIM);
        }

        // in/out shading
        int tracksBottom = tracksTop + TRACK_H * TrackType.values().length;
        if (e.inPoint >= 0 || e.outPoint >= 0) {
            int a = xAt(e.inPoint >= 0 ? e.inPoint : 0);
            int b = xAt(e.outPoint >= 0 ? e.outPoint : total);
            int left = Math.max(tlX, Math.min(a, b));
            int right = Math.min(tlX + tlW, Math.max(a, b));
            if (right > left) ui.rect(left, rulerY, right - left, tracksBottom - rulerY, 0x2850A0FF);
            if (e.inPoint >= 0 && a >= tlX && a <= tlX + tlW) ui.rect(a, rulerY, 1, tracksBottom - rulerY, 0xFF50A0FF);
            if (e.outPoint >= 0 && b >= tlX && b <= tlX + tlW) ui.rect(b, rulerY, 1, tracksBottom - rulerY, 0xFF50A0FF);
        }

        // markers
        for (ReplayMeta.Marker m : h.getAllMarkers()) {
            int px = xAt(m.tick);
            if (px < tlX || px > tlX + tlW) continue;
            ui.rect(px - 2, rulerY, 5, 5, m.color | 0xFF000000);
            ui.rect(px, rulerY, 1, RULER_H, m.color | 0xFF000000);
            if (ui.hovered(px - 3, rulerY, 7, RULER_H)) {
                String desc = m.description == null || m.description.isEmpty() ? "Marker" : m.description;
                ui.tooltip = desc + "\n"
                    + ReplayMeta.formatTicks(m.tick)
                    + (m.hasPosition ? String.format("\n%.1f, %.1f, %.1f", m.x, m.y, m.z) : "");
            }
        }

        // tracks
        int ty = tracksTop;
        for (TrackType type : TrackType.values()) {
            KeyframeTrack track = e.track(type);
            boolean hover = ui.hovered(0, ty, width, TRACK_H);
            ui.rect(0, ty, width, TRACK_H, hover ? 0x20FFFFFF : ((ty / TRACK_H) % 2 == 0 ? 0x10FFFFFF : 0));
            ui.rect(2, ty + 3, 3, TRACK_H - 6, track.enabled ? type.color : 0xFF555555);
            ui.text(ui.trim(type.displayName, LABEL_W - 40), 8, ty + 3, track.enabled ? Ui.TEXT : Ui.TEXT_DIM);
            if (ui.button(
                "+",
                LABEL_W - 28,
                ty + 1,
                12,
                TRACK_H - 2,
                false,
                "Add " + type.displayName + " keyframe at the playhead")) addKeyframe(type);
            if (ui.button(
                track.enabled ? "o" : "-",
                LABEL_W - 14,
                ty + 1,
                12,
                TRACK_H - 2,
                false,
                track.enabled ? "Disable track" : "Enable track")) {
                track.enabled = !track.enabled;
                h.markEditorDirty();
            }
            // segments
            List<Keyframe> ks = track.keyframes;
            for (int i = 0; i + 1 < ks.size(); i++) {
                int a = Math.max(tlX, xAt(ks.get(i).tick));
                int b = Math.min(tlX + tlW, xAt(ks.get(i + 1).tick));
                if (b > a) ui.rect(
                    a,
                    ty + TRACK_H / 2,
                    b - a,
                    1,
                    (type.color & 0x00FFFFFF) | (track.enabled ? 0xA0000000 : 0x50000000));
            }
            for (Keyframe k : ks) {
                int px = xAt(k.tick);
                if (px < tlX - 4 || px > tlX + tlW + 4) continue;
                boolean selected = isSelected(k);
                int color = selected ? 0xFFFFFFFF : (track.enabled ? type.color : 0xFF777777);
                ui.diamond(px, ty + TRACK_H / 2, selected ? 5 : 4, color);
                if (k.interpolation == Interpolation.HOLD) ui.rect(px + 2, ty + TRACK_H / 2 - 1, 3, 3, 0xFF000000);
                if (ui.hovered(px - 4, ty, 9, TRACK_H)) {
                    ui.tooltip = type.displayName + " @ "
                        + k.tick
                        + " ("
                        + ReplayMeta.formatTicks(k.tick)
                        + ")\n"
                        + k.interpolation.displayName
                        + "\nRight-click for options";
                    if (ui.clicked(px - 4, ty, 9, TRACK_H, 1)) openKeyframeMenu(track, k, ui.mouseX, ui.mouseY);
                }
            }
            ty += TRACK_H;
        }

        // playhead
        int playTick = scrubbing && scrubTick >= 0 ? scrubTick : h.getCurrentTick();
        double playPos = scrubbing ? playTick : h.getReplayTime();
        int px = xAt(playPos);
        if (px >= tlX && px <= tlX + tlW) {
            ui.rect(px, rulerY, 1, tracksBottom - rulerY, 0xFFFF5050);
            ui.rect(px - 3, rulerY, 7, 3, 0xFFFF5050);
        }

        // zoom bar
        int zy = tracksBottom + 3;
        ui.rect(tlX, zy, tlW, ZOOM_H - 2, Ui.PANEL_LIGHT);
        int za = tlX + (int) (viewStart / total * tlW);
        int zw = Math.max(6, (int) (viewLen / total * tlW));
        ui.rect(
            za,
            zy,
            Math.min(zw, tlX + tlW - za),
            ZOOM_H - 2,
            ui.hovered(za, zy, zw, ZOOM_H) || draggingZoom ? 0xFF7090C0 : 0xFF506080);

        // clicks on empty timeline area are consumed so they don't fall through
        ui.block(0, tlTop, width, height - tlTop);
    }

    /** Handles presses on the ruler, keyframes, empty track space and the zoom bar. Returns true if consumed. */
    private boolean handleTimelinePress(int x, int y) {
        ReplayHandler h = handler();
        if (h == null || hideUi) return false;
        computeLayout();
        int rulerY = tlTop + CONTROLS_H;
        int tracksBottom = tracksTop + TRACK_H * TrackType.values().length;
        if (x >= tlX && x < tlX + tlW && y >= rulerY && y < rulerY + RULER_H) {
            // markers first
            for (ReplayMeta.Marker m : h.getAllMarkers()) {
                int px = xAt(m.tick);
                if (Math.abs(px - x) <= 3) {
                    h.seek(m.tick);
                    return true;
                }
            }
            scrubbing = true;
            scrubTick = tickAt(x);
            if (scrubTick > h.getCurrentTick()) h.seek(scrubTick);
            return true;
        }
        if (x >= tlX - 4 && x < tlX + tlW + 4 && y >= tracksTop && y < tracksBottom) {
            int row = (y - tracksTop) / TRACK_H;
            TrackType type = TrackType.values()[Math.min(row, TrackType.values().length - 1)];
            KeyframeTrack track = h.editor.track(type);
            Keyframe hit = null;
            for (Keyframe k : track.keyframes) {
                if (Math.abs(xAt(k.tick) - x) <= 4) hit = k;
            }
            if (hit != null) {
                long now = System.currentTimeMillis();
                if (hit == lastClickedKey && now - lastClickTime < 300) {
                    h.seek(hit.tick);
                    if (type == TrackType.CAMERA) previewCameraAt(hit.tick);
                    lastClickedKey = null;
                    return true;
                }
                lastClickedKey = hit;
                lastClickTime = now;
                if (!isSelected(hit)) {
                    if (!isShiftKeyDown()) selection.clear();
                    selection.add(new Selection(track, hit));
                } else if (isShiftKeyDown()) {
                    final Keyframe removed = hit;
                    selection.removeIf(s -> s.keyframe == removed);
                    return true;
                }
                for (Selection s : selection) s.originalTick = s.keyframe.tick;
                draggingKeys = true;
                dragStartX = x;
                if (panel == Panel.NONE || panel == Panel.INSPECTOR) panel = Panel.INSPECTOR;
                return true;
            }
            if (x >= tlX && x < tlX + tlW) {
                if (!isShiftKeyDown()) selection.clear();
                scrubbing = true;
                scrubTick = tickAt(x);
                if (scrubTick > h.getCurrentTick()) h.seek(scrubTick);
                return true;
            }
        }
        int zy = tracksBottom + 3;
        if (x >= tlX && x < tlX + tlW && y >= zy && y < zy + ZOOM_H) {
            int total = Math.max(1, h.getTotalTicks() - 1);
            double clickTick = (x - tlX) * (double) total / tlW;
            if (clickTick < viewStart || clickTick > viewStart + viewLen) {
                viewStart = clickTick - viewLen / 2;
                clampView();
            }
            zoomDragOffset = clickTick - viewStart;
            draggingZoom = true;
            return true;
        }
        return false;
    }

    private int xAt(double tick) {
        return tlX + (int) Math.round((tick - viewStart) / viewLen * tlW);
    }

    private double tickAtD(int x) {
        return viewStart + (x - tlX) * viewLen / Math.max(1, tlW);
    }

    private int tickAt(int x) {
        ReplayHandler h = handler();
        int max = h == null ? 0 : h.getTotalTicks() - 1;
        return MathHelper.clamp_int((int) Math.round(tickAtD(x)), 0, max);
    }

    private void clampView() {
        ReplayHandler h = handler();
        int total = h == null ? 1 : Math.max(1, h.getTotalTicks() - 1);
        viewLen = MathHelper.clamp_double(viewLen, Math.min(20, total), total);
        viewStart = MathHelper.clamp_double(viewStart, 0, Math.max(0, total - viewLen));
    }

    private static float slower(float s) {
        float[] speeds = { 0.05f, 0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 4f, 8f, 16f, 32f, 64f };
        for (int i = speeds.length - 1; i >= 0; i--) if (speeds[i] < s - 0.001f) return speeds[i];
        return speeds[0];
    }

    private static float faster(float s) {
        float[] speeds = { 0.05f, 0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 4f, 8f, 16f, 32f, 64f };
        for (float sp : speeds) if (sp > s + 0.001f) return sp;
        return speeds[speeds.length - 1];
    }

    // ------------------------------------------------------------------ keyframe operations

    private boolean isSelected(Keyframe k) {
        for (Selection s : selection) if (s.keyframe == k) return true;
        return false;
    }

    private void addKeyframe(TrackType type) {
        ReplayHandler h = handler();
        if (h == null) return;
        CameraEntity cam = h.getCamera();
        int tick = h.getCurrentTick();
        double[] values;
        switch (type) {
            case CAMERA: {
                if (cam == null) return;
                if (h.isSpectating()) {
                    Notifications.show("FlashbackNH", "Camera keyframes aren't needed while spectating");
                    return;
                }
                values = new double[] { cam.posX, cam.posY, cam.posZ, cam.rotationYaw, cam.rotationPitch,
                    h.editor.visuals.cameraRoll };
                break;
            }
            case FOV:
                values = new double[] {
                    !Float.isNaN(h.getFovOverride()) ? h.getFovOverride() : mc.gameSettings.fovSetting };
                break;
            case TIME:
                values = new double[] { mc.theWorld == null ? 0 : mc.theWorld.getWorldTime() % 24000 };
                break;
            case SPEED:
                values = new double[] { 1.0 };
                break;
            case SHAKE:
                values = new double[] { 1.0 };
                break;
            default:
                return;
        }
        KeyframeTrack track = h.editor.track(type);
        Keyframe k = track.put(tick, values, Interpolation.parse(Config.defaultInterpolation, Interpolation.SMOOTH));
        selection.clear();
        selection.add(new Selection(track, k));
        h.markEditorDirty();
    }

    private void deleteSelection() {
        ReplayHandler h = handler();
        if (h == null || selection.isEmpty()) return;
        for (Selection s : selection) s.track.keyframes.remove(s.keyframe);
        Notifications.show("FlashbackNH", "Deleted " + selection.size() + " keyframe(s)");
        selection.clear();
        h.markEditorDirty();
    }

    private void copySelection() {
        if (selection.isEmpty()) return;
        clipboard.clear();
        int base = Integer.MAX_VALUE;
        for (Selection s : selection) base = Math.min(base, s.keyframe.tick);
        for (Selection s : selection) {
            Keyframe copy = s.keyframe.copy();
            copy.tick -= base;
            clipboard.add(new Object[] { s.track.type, copy });
        }
        Notifications.show("FlashbackNH", "Copied " + clipboard.size() + " keyframe(s)");
    }

    private void paste() {
        ReplayHandler h = handler();
        if (h == null || clipboard.isEmpty()) return;
        int base = h.getCurrentTick();
        selection.clear();
        for (Object[] o : clipboard) {
            KeyframeTrack track = h.editor.track((TrackType) o[0]);
            Keyframe src = (Keyframe) o[1];
            int tick = MathHelper.clamp_int(base + src.tick, 0, h.getTotalTicks() - 1);
            Keyframe k = track.put(tick, src.values.clone(), src.interpolation);
            selection.add(new Selection(track, k));
        }
        h.markEditorDirty();
        Notifications.show("FlashbackNH", "Pasted " + clipboard.size() + " keyframe(s)");
    }

    private void selectAll() {
        ReplayHandler h = handler();
        if (h == null) return;
        selection.clear();
        for (KeyframeTrack t : h.editor.tracks) for (Keyframe k : t.keyframes) selection.add(new Selection(t, k));
    }

    private void jumpKeyframe(boolean forward) {
        ReplayHandler h = handler();
        if (h == null) return;
        int cur = h.getCurrentTick();
        int best = forward ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        for (KeyframeTrack t : h.editor.tracks) {
            for (Keyframe k : t.keyframes) {
                if (forward && k.tick > cur && k.tick < best) best = k.tick;
                if (!forward && k.tick < cur && k.tick > best) best = k.tick;
            }
        }
        if (best != Integer.MAX_VALUE && best != Integer.MIN_VALUE) h.seek(best);
    }

    private void previewCameraAt(int tick) {
        ReplayHandler h = handler();
        if (h == null) return;
        KeyframeTrack cam = h.editor.track(TrackType.CAMERA);
        CameraEntity camera = h.getCamera();
        if (!cam.isActive() || camera == null || h.isSpectating()) return;
        double[] v = cam.evaluate(tick);
        camera.setCameraPosition(v[0], v[1], v[2], (float) v[3], (float) v[4]);
    }

    private void addMarker() {
        ReplayHandler h = handler();
        if (h == null) return;
        ReplayMeta.Marker m = new ReplayMeta.Marker(h.getCurrentTick(), 0xFFE0C040, "");
        CameraEntity cam = h.getCamera();
        if (cam != null) {
            m.hasPosition = true;
            m.x = cam.posX;
            m.y = cam.posY;
            m.z = cam.posZ;
        }
        h.editor.markers.add(m);
        h.markEditorDirty();
        Notifications.show("FlashbackNH", "Added marker at " + ReplayMeta.formatTicks(m.tick));
    }

    private void openKeyframeMenu(final KeyframeTrack track, final Keyframe k, int x, int y) {
        if (!isSelected(k)) {
            selection.clear();
            selection.add(new Selection(track, k));
        }
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (final Interpolation in : Interpolation.values()) {
            items.add((k.interpolation == in ? "> " : "   ") + in.displayName);
            actions.add(() -> {
                for (Selection s : selection) s.keyframe.interpolation = in;
                markDirty();
            });
        }
        items.add("Jump to keyframe");
        actions.add(() -> {
            ReplayHandler h = handler();
            if (h != null) {
                h.seek(k.tick);
                if (track.type == TrackType.CAMERA) previewCameraAt(k.tick);
            }
        });
        if (track.type == TrackType.CAMERA) {
            items.add("Set to current camera");
            actions.add(() -> {
                ReplayHandler h = handler();
                CameraEntity cam = h == null ? null : h.getCamera();
                if (cam != null) {
                    k.values = new double[] { cam.posX, cam.posY, cam.posZ, cam.rotationYaw, cam.rotationPitch,
                        k.values.length > 5 ? k.values[5] : 0 };
                    markDirty();
                }
            });
        }
        items.add("Edit...");
        actions.add(() -> panel = Panel.INSPECTOR);
        items.add("Copy");
        actions.add(this::copySelection);
        items.add("Delete");
        actions.add(this::deleteSelection);
        showContextMenu(items, actions, x, y);
    }

    private void markDirty() {
        ReplayHandler h = handler();
        if (h != null) h.markEditorDirty();
    }

    // ------------------------------------------------------------------ popups

    private void showContextMenu(List<String> items, List<Runnable> actions, int x, int y) {
        contextItems = items;
        contextActions = actions;
        contextX = x;
        contextY = y;
    }

    private int contextWidth() {
        int w = 60;
        for (String s : contextItems) w = Math.max(w, ui.width(s) + 12);
        return w;
    }

    /** Resolves clicks on open popups before anything else consumes them. */
    private boolean handlePopupClicks() {
        if (contextItems != null) {
            int w = contextWidth();
            int h = contextItems.size() * 12 + 4;
            int x = Math.min(contextX, width - w - 2), y = Math.min(contextY, height - h - 2);
            for (int i = 0; i < contextItems.size(); i++) {
                if (ui.clicked(x, y + 2 + i * 12, w, 12, 0)) {
                    Runnable r = contextActions.get(i);
                    contextItems = null;
                    contextActions = null;
                    r.run();
                    return true;
                }
            }
            if (ui.hasPendingClick()) {
                ui.block(0, 0, width, height);
                contextItems = null;
                return true;
            }
        }
        if (openMenu != null) {
            List<String> items = menuItems(openMenu);
            int w = 150;
            for (int i = 0; i < items.size(); i++) {
                if (ui.clicked(menuX, MENU_H + 2 + i * 12, w, 12, 0)) {
                    String m = openMenu;
                    openMenu = null;
                    menuAction(m, i);
                    return true;
                }
            }
            if (ui.hasPendingClick()) {
                // clicks on the menu bar are left for the bar (switching menus); anything else just closes the menu
                ui.block(0, MENU_H, width, height - MENU_H);
                justClosedMenu = openMenu;
                openMenu = null;
                return true;
            }
        }
        return false;
    }

    private void drawPopups(ReplayHandler h) {
        if (openMenu != null) {
            List<String> items = menuItems(openMenu);
            int w = 150;
            int hh = items.size() * 12 + 4;
            ui.rect(menuX - 1, MENU_H, w + 2, hh + 1, Ui.BORDER);
            ui.rect(menuX, MENU_H, w, hh, 0xF0181820);
            for (int i = 0; i < items.size(); i++) {
                int iy = MENU_H + 2 + i * 12;
                if (ui.hovered(menuX, iy, w, 12)) ui.rect(menuX, iy, w, 12, Ui.HOVER);
                ui.text(items.get(i), menuX + 6, iy + 2, Ui.TEXT);
            }
        }
        if (contextItems != null) {
            int w = contextWidth();
            int hh = contextItems.size() * 12 + 4;
            int x = Math.min(contextX, width - w - 2), y = Math.min(contextY, height - hh - 2);
            ui.rect(x - 1, y - 1, w + 2, hh + 2, Ui.BORDER);
            ui.rect(x, y, w, hh, 0xF0181820);
            for (int i = 0; i < contextItems.size(); i++) {
                int iy = y + 2 + i * 12;
                if (ui.hovered(x, iy, w, 12)) ui.rect(x, iy, w, 12, Ui.HOVER);
                ui.text(contextItems.get(i), x + 6, iy + 2, Ui.TEXT);
            }
        }
    }

    private List<String> menuItems(String menu) {
        List<String> items = new ArrayList<>();
        ReplayHandler h = handler();
        Visuals v = h == null ? new Visuals() : h.editor.visuals;
        switch (menu) {
            case "File":
                items.add("Export Video...  (Ctrl+E)");
                items.add("Save Editor State");
                items.add("Exit Replay");
                break;
            case "Window":
                items.add(check(panel == Panel.PLAYERS) + "Player List");
                items.add(check(panel == Panel.VISUALS) + "Visuals");
                items.add(check(panel == Panel.INSPECTOR) + "Keyframe Inspector");
                items.add(check(panel == Panel.MOVEMENT) + "Movement");
                items.add("Hide UI  (F1)");
                break;
            case "View":
                items.add(check(v.showCameraPath) + "Camera Path");
                items.add(check(v.ruleOfThirds) + "Rule of Thirds Guide");
                items.add(check(v.centerGuide) + "Center Guide");
                items.add("Reset Timeline Zoom");
                break;
            case "Help":
                items.add("Controls...");
                break;
            default:
        }
        return items;
    }

    private static String check(boolean b) {
        return b ? "[x] " : "[  ] ";
    }

    private void menuAction(String menu, int index) {
        ReplayHandler h = handler();
        if (h == null) return;
        Visuals v = h.editor.visuals;
        switch (menu) {
            case "File":
                if (index == 0) mc.displayGuiScreen(new GuiExport(this));
                else if (index == 1) {
                    h.markEditorDirty();
                    h.saveEditorAsync();
                    Notifications.show("FlashbackNH", "Saved editor state");
                } else if (index == 2) h.exit();
                break;
            case "Window":
                Panel[] panels = { Panel.PLAYERS, Panel.VISUALS, Panel.INSPECTOR, Panel.MOVEMENT };
                if (index < panels.length) {
                    panel = panel == panels[index] ? Panel.NONE : panels[index];
                    panelScroll = 0;
                } else hideUi = true;
                break;
            case "View":
                if (index == 0) v.showCameraPath = !v.showCameraPath;
                else if (index == 1) v.ruleOfThirds = !v.ruleOfThirds;
                else if (index == 2) v.centerGuide = !v.centerGuide;
                else {
                    viewStart = 0;
                    viewLen = h.getTotalTicks();
                    clampView();
                }
                h.markEditorDirty();
                break;
            case "Help":
                panel = Panel.HELP;
                break;
            default:
        }
    }

    // ------------------------------------------------------------------ side panels

    private void drawPanel(ReplayHandler h) {
        int x = width - PANEL_W;
        int top = MENU_H;
        int bottom = tlTop;
        ui.rect(x, top, PANEL_W, bottom - top, Ui.BG);
        ui.rect(x, top, 1, bottom - top, Ui.BORDER);
        int px = x + 6;
        int pw = PANEL_W - 12;
        String title = panel == Panel.PLAYERS ? "Player List"
            : panel == Panel.VISUALS ? "Visuals"
                : panel == Panel.INSPECTOR ? "Keyframe Inspector" : panel == Panel.MOVEMENT ? "Movement" : "Controls";
        ui.text(title, px, top + 4, Ui.TEXT);
        if (ui.button("x", x + PANEL_W - 14, top + 2, 11, 11)) {
            panel = Panel.NONE;
            return;
        }
        ui.separator(px, top + 15, pw);
        ui.block(x, top, PANEL_W, 18);
        int scroll = ui.takeScroll(x, top, PANEL_W, bottom - top);
        panelScroll = Math.max(0, panelScroll - scroll * 12);
        int y = top + 20 - panelScroll;
        int clipTop = top + 18;

        // the content may be taller than the space above the timeline; it scrolls and must not draw over it
        ScaledResolution scaled = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int factor = scaled.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * factor, mc.displayHeight - bottom * factor, PANEL_W * factor, (bottom - clipTop) * factor);
        switch (panel) {
            case PLAYERS:
                y = drawPlayers(h, px, y, pw, clipTop, bottom);
                break;
            case VISUALS:
                y = drawVisuals(h, px, y, pw);
                break;
            case INSPECTOR:
                y = drawInspector(h, px, y, pw);
                break;
            case MOVEMENT:
                y = drawMovement(h, px, y, pw);
                break;
            case HELP:
                y = drawHelp(px, y, pw);
                break;
            default:
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        int contentH = y + panelScroll - (top + 20);
        int visible = bottom - top - 22;
        if (panelScroll > Math.max(0, contentH - visible)) panelScroll = Math.max(0, contentH - visible);
        ui.block(x, top, PANEL_W, bottom - top);
    }

    private int drawPlayers(ReplayHandler h, int x, int y, int w, int clipTop, int clipBottom) {
        if (mc.theWorld == null) return y;
        List<Entity> list = new ArrayList<>();
        for (Object o : mc.theWorld.playerEntities) {
            if (!(o instanceof CameraEntity)) list.add((Entity) o);
        }
        if (list.isEmpty()) {
            ui.text("No players", x, y, Ui.TEXT_DIM);
            return y + 12;
        }
        for (Entity e : list) {
            boolean visible = y >= clipTop && y + 24 < clipBottom;
            if (visible) {
                boolean hidden = h.editor.visuals.hiddenEntities.contains(ReplayHandler.entityKey(e));
                String name = e.getCommandSenderName() + (e.getEntityId() == h.getLocalPlayerId() ? " (recorder)" : "");
                ui.text(ui.trim(name, w), x, y, hidden ? Ui.TEXT_DIM : Ui.TEXT);
                int bw = (w - 4) / 3;
                if (ui.button("Spectate", x, y + 10, bw, 12)) h.spectate(e);
                if (ui.button(hidden ? "Show" : "Hide", x + bw + 2, y + 10, bw, 12)) h.toggleHidden(e);
                if (ui.button("TP", x + 2 * (bw + 2), y + 10, bw, 12)) teleportTo(h, e);
            }
            y += 26;
        }
        if (y >= clipTop && y + 12 < clipBottom) {
            if (ui.button("Spectate Mobs Nearby...", x, y, w, 12)) openNearbyMenu(h);
        }
        return y + 14;
    }

    private void openNearbyMenu(final ReplayHandler h) {
        CameraEntity cam = h.getCamera();
        if (cam == null || mc.theWorld == null) return;
        List<Entity> near = new ArrayList<>();
        for (Object o : mc.theWorld.loadedEntityList) {
            Entity e = (Entity) o;
            if (e instanceof EntityLivingBase && !(e instanceof EntityPlayer) && e.getDistanceSqToEntity(cam) < 64 * 64)
                near.add(e);
        }
        near.sort((a, b) -> Double.compare(a.getDistanceSqToEntity(cam), b.getDistanceSqToEntity(cam)));
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (int i = 0; i < Math.min(12, near.size()); i++) {
            final Entity e = near.get(i);
            items.add(String.format("%s (%.0fm)", e.getCommandSenderName(), Math.sqrt(e.getDistanceSqToEntity(cam))));
            actions.add(() -> h.spectate(e));
        }
        if (items.isEmpty()) {
            Notifications.show("FlashbackNH", "No mobs within 64 blocks");
            return;
        }
        showContextMenu(items, actions, ui.mouseX, ui.mouseY);
    }

    private void teleportTo(ReplayHandler h, Entity e) {
        h.stopSpectating();
        CameraEntity cam = h.getCamera();
        if (cam == null) return;
        double yaw = Math.toRadians(e.rotationYaw);
        cam.setCameraPosition(
            e.posX + Math.sin(yaw) * 3,
            e.posY - e.yOffset + 2.5,
            e.posZ - Math.cos(yaw) * 3,
            e.rotationYaw,
            25);
    }

    private int drawVisuals(ReplayHandler h, int x, int y, int w) {
        Visuals v = h.editor.visuals;
        Visuals before = v.copy();
        ui.text("GUI", x, y, Ui.ACCENT);
        y += 11;
        v.showChat = ui.checkbox("Chat", v.showChat, x, y, w);
        y += 12;
        v.showScoreboard = ui.checkbox("Scoreboard", v.showScoreboard, x, y, w);
        y += 12;
        v.showPlayerList = ui.checkbox("Player list (Tab)", v.showPlayerList, x, y, w);
        y += 12;
        v.showBossBar = ui.checkbox("Boss bar", v.showBossBar, x, y, w);
        y += 12;
        v.showRecordedHotbar = ui.checkbox("Recorder hotbar (spectating)", v.showRecordedHotbar, x, y, w);
        y += 16;
        ui.text("World", x, y, Ui.ACCENT);
        y += 11;
        v.renderEntities = ui.checkbox("Render entities", v.renderEntities, x, y, w);
        y += 12;
        v.renderPlayers = ui.checkbox("Render players", v.renderPlayers, x, y, w);
        y += 12;
        v.renderParticles = ui.checkbox("Render particles", v.renderParticles, x, y, w);
        y += 12;
        v.renderSky = ui.checkbox("Render sky", v.renderSky, x, y, w);
        y += 12;
        v.renderClouds = ui.checkbox("Render clouds", v.renderClouds, x, y, w);
        y += 12;
        v.renderNametags = ui.checkbox("Render nametags", v.renderNametags, x, y, w);
        y += 16;
        ui.text("Overrides", x, y, Ui.ACCENT);
        y += 11;
        v.overrideFov = ui.checkbox("Override FOV", v.overrideFov, x, y, w);
        y += 12;
        if (v.overrideFov) {
            v.fov = (float) ui.stepper("FOV", v.fov, 1, 1, 170, "%.0f", x + 8, y, w - 8);
            y += 13;
        }
        v.overrideTime = ui.checkbox("Override time", v.overrideTime, x, y, w);
        y += 12;
        if (v.overrideTime) {
            v.time = (int) ui.stepper("Time", v.time, 500, 0, 23999, "%.0f", x + 8, y, w - 8);
            y += 13;
        }
        if (ui.button("Weather: " + cap(v.weather.name()), x, y, w, 12)) {
            v.weather = Visuals.Weather.values()[(v.weather.ordinal() + 1) % Visuals.Weather.values().length];
        }
        y += 14;
        v.nightVision = ui.checkbox("Night vision", v.nightVision, x, y, w);
        y += 12;
        v.cameraRoll = (float) ui.stepper("Camera roll", v.cameraRoll, 1, -180, 180, "%.0f", x, y, w);
        y += 13;
        v.cameraShake = ui.checkbox("Camera shake", v.cameraShake, x, y, w);
        y += 12;
        if (v.cameraShake || h.editor.track(TrackType.SHAKE)
            .isActive()) {
            v.shakeFrequency = (float) ui.stepper("Frequency", v.shakeFrequency, 0.1, 0.1, 10, "%.1f", x + 8, y, w - 8);
            y += 13;
            v.shakeAmplitude = (float) ui.stepper("Amplitude", v.shakeAmplitude, 0.1, 0, 20, "%.1f", x + 8, y, w - 8);
            y += 13;
        }
        y += 4;
        if (!v.hiddenEntities.isEmpty()) {
            if (ui.button("Unhide all entities (" + v.hiddenEntities.size() + ")", x, y, w, 12))
                v.hiddenEntities.clear();
            y += 14;
        }
        if (!equalsVisuals(before, v)) h.markEditorDirty();
        return y;
    }

    private static boolean equalsVisuals(Visuals a, Visuals b) {
        return a.showChat == b.showChat && a.showScoreboard == b.showScoreboard
            && a.showPlayerList == b.showPlayerList
            && a.showBossBar == b.showBossBar
            && a.showRecordedHotbar == b.showRecordedHotbar
            && a.renderEntities == b.renderEntities
            && a.renderPlayers == b.renderPlayers
            && a.renderParticles == b.renderParticles
            && a.renderSky == b.renderSky
            && a.renderClouds == b.renderClouds
            && a.renderNametags == b.renderNametags
            && a.overrideFov == b.overrideFov
            && a.fov == b.fov
            && a.overrideTime == b.overrideTime
            && a.time == b.time
            && a.weather == b.weather
            && a.nightVision == b.nightVision
            && a.cameraRoll == b.cameraRoll
            && a.cameraShake == b.cameraShake
            && a.shakeFrequency == b.shakeFrequency
            && a.shakeAmplitude == b.shakeAmplitude
            && a.hiddenEntities.equals(b.hiddenEntities);
    }

    private static String cap(String s) {
        return s.charAt(0) + s.substring(1)
            .toLowerCase();
    }

    private int drawInspector(ReplayHandler h, int x, int y, int w) {
        if (selection.isEmpty()) {
            ui.text("No keyframe selected.", x, y, Ui.TEXT_DIM);
            y += 12;
            ui.text("Click a keyframe on the", x, y, Ui.TEXT_DIM);
            y += 10;
            ui.text("timeline to edit it.", x, y, Ui.TEXT_DIM);
            return y + 12;
        }
        if (selection.size() > 1) {
            ui.text(selection.size() + " keyframes selected", x, y, Ui.TEXT);
            y += 14;
            if (ui.button("Interpolation...", x, y, w, 12))
                openKeyframeMenu(selection.get(0).track, selection.get(0).keyframe, x, y);
            y += 14;
            if (ui.button("Delete", x, y, w, 12)) deleteSelection();
            return y + 14;
        }
        Selection s = selection.get(0);
        Keyframe k = s.keyframe;
        TrackType type = s.track.type;
        ui.text(type.displayName + " keyframe", x, y, type.color);
        y += 12;
        int oldTick = k.tick;
        k.tick = (int) ui.stepper("Tick", k.tick, 1, 0, h.getTotalTicks() - 1, "%.0f", x, y, w);
        if (k.tick != oldTick) {
            s.track.sort();
            h.markEditorDirty();
        }
        y += 13;
        if (ui.button("Type: " + k.interpolation.displayName, x, y, w, 12)) {
            k.interpolation = k.interpolation.next();
            h.markEditorDirty();
        }
        y += 15;
        for (int c = 0; c < type.channels.length && c < k.values.length; c++) {
            double step;
            String fmt;
            if (type == TrackType.CAMERA) {
                step = c < 3 ? 0.1 : 1;
                fmt = c < 3 ? "%.2f" : "%.1f";
            } else if (type == TrackType.TIME) {
                step = 100;
                fmt = "%.0f";
            } else if (type == TrackType.FOV) {
                step = 1;
                fmt = "%.0f";
            } else {
                step = 0.05;
                fmt = "%.2f";
            }
            double min = type == TrackType.FOV ? 1
                : type == TrackType.SPEED ? 0.01 : type == TrackType.SHAKE ? 0 : -1e9;
            double max = type == TrackType.FOV ? 170 : 1e9;
            double nv = ui.stepper(type.channels[c], k.values[c], step, min, max, fmt, x, y, w);
            if (nv != k.values[c]) {
                k.values[c] = nv;
                h.markEditorDirty();
            }
            y += 13;
        }
        y += 3;
        if (type == TrackType.CAMERA) {
            if (ui.button("Set to current camera", x, y, w, 12)) {
                CameraEntity cam = h.getCamera();
                if (cam != null) {
                    k.values = new double[] { cam.posX, cam.posY, cam.posZ, cam.rotationYaw, cam.rotationPitch,
                        k.values.length > 5 ? k.values[5] : 0 };
                    h.markEditorDirty();
                }
            }
            y += 14;
        }
        if (ui.button("Jump to", x, y, (w - 2) / 2, 12)) {
            h.seek(k.tick);
            if (type == TrackType.CAMERA) previewCameraAt(k.tick);
        }
        if (ui.button("Delete", x + (w - 2) / 2 + 2, y, (w - 2) / 2, 12)) deleteSelection();
        y += 16;
        if (type == TrackType.SPEED) {
            ui.text("Speed keyframes change the", x, y, Ui.TEXT_DIM);
            y += 10;
            ui.text("playback/export speed.", x, y, Ui.TEXT_DIM);
            y += 12;
        }
        return y;
    }

    private int drawMovement(ReplayHandler h, int x, int y, int w) {
        ui.text(String.format("Flying speed: %.1f", h.cameraController.getSpeed()), x, y, Ui.TEXT);
        y += 12;
        if (ui.button("Slower", x, y, (w - 2) / 2, 12)) h.cameraController.adjustSpeed(-1);
        if (ui.button("Faster", x + (w - 2) / 2 + 2, y, (w - 2) / 2, 12)) h.cameraController.adjustSpeed(1);
        y += 16;
        boolean momentum = ui.checkbox("Momentum", Config.cameraMomentum, x, y, w);
        if (momentum != Config.cameraMomentum) Config.setAndSave(Config.CAT_REPLAY, "cameraMomentum", momentum);
        y += 12;
        if (ui.button("Direction: " + (h.cameraController.horizontalMovement ? "Horizontal" : "Camera"), x, y, w, 12)) {
            h.cameraController.horizontalMovement = !h.cameraController.horizontalMovement;
        }
        y += 15;
        h.cameraController.lockX = ui.checkbox("Lock X", h.cameraController.lockX, x, y, w);
        y += 12;
        h.cameraController.lockY = ui.checkbox("Lock Y", h.cameraController.lockY, x, y, w);
        y += 12;
        h.cameraController.lockZ = ui.checkbox("Lock Z", h.cameraController.lockZ, x, y, w);
        y += 16;
        CameraEntity cam = h.getCamera();
        if (cam != null) {
            ui.text(String.format("X %.2f", cam.posX), x, y, Ui.TEXT_DIM);
            y += 10;
            ui.text(String.format("Y %.2f", cam.posY), x, y, Ui.TEXT_DIM);
            y += 10;
            ui.text(String.format("Z %.2f", cam.posZ), x, y, Ui.TEXT_DIM);
            y += 10;
            ui.text(
                String.format(
                    "Yaw %.1f  Pitch %.1f",
                    MathHelper.wrapAngleTo180_float(cam.rotationYaw),
                    cam.rotationPitch),
                x,
                y,
                Ui.TEXT_DIM);
            y += 12;
        }
        return y;
    }

    private int drawHelp(int x, int y, int w) {
        String[] lines = { "Right mouse (hold): fly", "WASD / Space / Shift: move", "Scroll while flying: speed",
            "Ctrl while flying: boost", "Space: play / pause", "Left / Right: -/+ 1 tick", "Shift+Left/Right: -/+ 1 s",
            "Up / Down: next/prev keyframe", "I / O: in / out point", "K: camera keyframe", "M: add marker",
            "Del: delete keyframes", "Ctrl+C / Ctrl+V: copy/paste", "Ctrl+A: select all", "Ctrl+drag playhead: preview",
            "Scroll on timeline: zoom", "F1: hide editor UI", "Ctrl+E: export", "Esc: replay menu" };
        for (String l : lines) {
            ui.text(ui.trim(l, w), x, y, Ui.TEXT_DIM);
            y += 10;
        }
        return y;
    }

    @SuppressWarnings("unused")
    private static boolean isOtherPlayer(Entity e) {
        return e instanceof EntityOtherPlayerMP;
    }
}
