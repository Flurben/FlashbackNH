package com.flurben.flashbacknh.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ChatAllowedCharacters;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * A tiny immediate-mode widget helper for the replay editor. Clicks are queued by the screen's input handlers and
 * resolved while widgets are drawn; the first widget containing a click consumes it.
 */
public final class Ui {

    public static final int BG = 0xE0141418;
    public static final int PANEL = 0xE01C1C22;
    public static final int PANEL_LIGHT = 0xFF2A2A33;
    public static final int BORDER = 0xFF3A3A46;
    public static final int TEXT = 0xFFE8E8EE;
    public static final int TEXT_DIM = 0xFF9A9AA8;
    public static final int ACCENT = 0xFF4F8FE0;
    public static final int HOVER = 0xFF3A3A48;
    public static final int ACTIVE = 0xFF35507A;

    private static final class Click {

        final int x, y, button;
        boolean consumed;

        Click(int x, int y, int button) {
            this.x = x;
            this.y = y;
            this.button = button;
        }
    }

    private final List<Click> clicks = new ArrayList<>();
    public int mouseX, mouseY;
    /** Where the click that {@link #clicked} last took was. */
    public int clickX, clickY;
    public String tooltip;
    private int scrollDelta;

    /** The number being typed into, if any, and what was typed so far. */
    private String editId;
    private String editText = "";
    private boolean editCommit;
    /** Nothing typed yet: the first key replaces the whole value, as in a selected text field. */
    private boolean editFresh;
    private boolean editSeen;
    /** The number being dragged with the mouse, where the drag started and the value then. */
    private String pressId;
    private int pressX;
    private double pressValue;
    private boolean pressDragged;
    private String lastClickId;
    private long lastClickTime;
    /** The text field being typed into keeps letters, not only digits. */
    private boolean editIsText;
    private int editMaxLength;
    /** Set for one frame when typing was accepted with Enter. */
    public boolean enterPressed;

    /** The open dropdown list, and the pick made in it, which its dropdown takes when it is drawn next. */
    private String dropId;
    private List<String> dropItems;
    private int dropX, dropY, dropW, dropSelected;
    private String pickedId;
    private int picked;

    public FontRenderer font() {
        return Minecraft.getMinecraft().fontRenderer;
    }

    public void queueClick(int x, int y, int button) {
        clicks.add(new Click(x, y, button));
    }

    public void queueScroll(int delta) {
        scrollDelta += delta;
    }

    public void begin(int mouseX, int mouseY) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.tooltip = null;
        editSeen = false;
    }

    /** Drops clicks nobody consumed. Returns true if an unconsumed click existed. */
    public boolean end() {
        boolean any = false;
        for (Click c : clicks) any |= !c.consumed;
        clicks.clear();
        scrollDelta = 0;
        // keys arrive between frames, after end and before begin, so Enter stays set for the frame that follows it
        enterPressed = false;
        // a number that is no longer drawn (its panel closed) stops being typed into
        if (editId != null && !editSeen) stopEditing();
        if (pressId != null && !Mouse.isButtonDown(0)) pressId = null;
        return any;
    }

    /** Whether a number is being typed into, which then gets the keyboard. */
    public boolean isEditing() {
        return editId != null;
    }

    /** Takes a key while {@link #isEditing}. Enter accepts, Escape cancels. */
    public void keyTyped(char c, int key) {
        if (editId == null) return;
        if (key == Keyboard.KEY_ESCAPE) {
            stopEditing();
        } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER || key == Keyboard.KEY_TAB) {
            editCommit = true;
            if (key != Keyboard.KEY_TAB) enterPressed = true;
        } else if (key == Keyboard.KEY_BACK) {
            if (editFresh) editText = "";
            else if (!editText.isEmpty()) editText = editText.substring(0, editText.length() - 1);
            editFresh = false;
        } else if (key == Keyboard.KEY_A && GuiScreen.isCtrlKeyDown()) {
            editText = "";
            editFresh = false;
        } else if (editIsText) {
            if (ChatAllowedCharacters.isAllowedCharacter(c)) {
                if (editFresh) editText = "";
                editFresh = false;
                if (editText.length() < editMaxLength) editText += c;
            }
        } else if ((c >= '0' && c <= '9') || c == '.' || c == ',' || c == '-' || c == '+') {
            if (editFresh) editText = "";
            editFresh = false;
            if (editText.length() < 24) editText += c == ',' ? '.' : c;
        }
    }

    private void startEditing(String id, double value, String format) {
        editIsText = false;
        editId = id;
        editText = String.format(format, value)
            .replaceAll("[^0-9.,+-]", "")
            .replace(',', '.');
        editCommit = false;
        editFresh = true;
        // its box is drawn from the next frame on; this frame counts as drawn
        editSeen = true;
        Keyboard.enableRepeatEvents(true);
    }

    private void stopEditing() {
        editId = null;
        editCommit = false;
        Keyboard.enableRepeatEvents(false);
    }

    private static double parse(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Whether there is a click nobody took that is outside this rectangle. */
    private boolean clickedOutside(int x, int y, int w, int h) {
        for (Click c : clicks) {
            if (!c.consumed && !(c.x >= x && c.x < x + w && c.y >= y && c.y < y + h)) return true;
        }
        return false;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double multiplier() {
        return GuiScreen.isShiftKeyDown() ? 10 : GuiScreen.isCtrlKeyDown() ? 0.1 : 1;
    }

    /**
     * Draws the box of a number being typed into and returns the typed value once accepted, or NaN while typing.
     */
    private double editBox(int x, int y, int w, int h, double value, double min, double max) {
        editSeen = true;
        rect(x, y, w, h, 0xFF101014);
        outline(x, y, w, h, ACCENT);
        boolean caret = System.currentTimeMillis() / 500 % 2 == 0;
        String shown = editText + (caret ? "_" : " ");
        int tw = width(shown);
        int tx = tw > w - 4 ? x + w - 2 - tw : x + 2;
        if (editFresh && !editText.isEmpty()) rect(tx - 1, y + 1, width(editText) + 1, h - 2, 0xFF35507A);
        text(shown, tx, y + (h - 8) / 2, TEXT);
        if (clickedOutside(x, y, w, h)) editCommit = true;
        block(x, y, w, h);
        if (!editCommit) return Double.NaN;
        double typed = parse(editText, value);
        stopEditing();
        return clamp(typed, min, max);
    }

    /**
     * A number box: drag sideways to change the value, click to type one, scroll to step it. Shift steps ten times
     * as far, Ctrl a tenth.
     */
    public double number(String id, double value, double step, double min, double max, String format, int x, int y,
        int w, int h) {
        if (id.equals(editId)) {
            double typed = editBox(x, y, w, h, value, min, max);
            return Double.isNaN(typed) ? value : typed;
        }
        boolean hover = hovered(x, y, w, h);
        if (clicked(x, y, w, h, 0)) {
            pressId = id;
            pressX = mouseX;
            pressValue = value;
            pressDragged = false;
        }
        if (id.equals(pressId)) {
            if (Mouse.isButtonDown(0)) {
                int dx = mouseX - pressX;
                if (Math.abs(dx) >= 3) pressDragged = true;
                if (pressDragged) value = clamp(pressValue + Math.round(dx / 2.0) * step * multiplier(), min, max);
            } else {
                pressId = null;
                if (!pressDragged) startEditing(id, value, format);
            }
        }
        int scroll = takeScroll(x, y, w, h);
        if (scroll != 0) value = clamp(value + Math.signum(scroll) * step * multiplier(), min, max);
        boolean active = id.equals(pressId);
        rect(x, y, w, h, active ? ACTIVE : hover ? HOVER : 0xFF22222A);
        outline(x, y, w, h, active || hover ? ACCENT : BORDER);
        textCentered(trim(String.format(format, value), w - 2), x + w / 2, y + (h - 8) / 2, TEXT);
        if (hover && tooltip == null) tooltip = "Drag or scroll to change, click to type";
        return value;
    }

    /**
     * A slider between two values: click or drag to set, scroll to step, double-click or right-click to type a value.
     */
    public double slider(String id, double value, double min, double max, double step, String format, int x, int y,
        int w, int h) {
        if (id.equals(editId)) {
            double typed = editBox(x, y, w, h, value, min, max);
            return Double.isNaN(typed) ? value : typed;
        }
        boolean hover = hovered(x, y, w, h);
        if (clicked(x, y, w, h, 1)) {
            startEditing(id, value, format);
            return value;
        }
        if (clicked(x, y, w, h, 0)) {
            long now = System.currentTimeMillis();
            if (id.equals(lastClickId) && now - lastClickTime < 350) {
                lastClickId = null;
                startEditing(id, value, format);
                return value;
            }
            lastClickId = id;
            lastClickTime = now;
            pressId = id;
        }
        if (id.equals(pressId)) {
            if (Mouse.isButtonDown(0)) {
                double f = clamp((mouseX - x - 2) / (double) Math.max(1, w - 4), 0, 1);
                value = min + f * (max - min);
                if (step > 0) value = Math.round(value / step) * step;
                value = clamp(value, min, max);
            } else {
                pressId = null;
            }
        }
        int scroll = takeScroll(x, y, w, h);
        if (scroll != 0) value = clamp(value + Math.signum(scroll) * Math.max(step, 1e-9) * multiplier(), min, max);
        boolean active = id.equals(pressId);
        rect(x, y, w, h, 0xFF22222A);
        double f = max > min ? clamp((value - min) / (max - min), 0, 1) : 0;
        int fill = (int) Math.round((w - 2) * f);
        rect(x + 1, y + 1, fill, h - 2, active ? 0xFF4A70A8 : 0xFF35507A);
        rect(x + 1 + Math.max(0, Math.min(w - 4, fill - 1)), y + 1, 2, h - 2, active || hover ? 0xFFFFFFFF : ACCENT);
        outline(x, y, w, h, active || hover ? ACCENT : BORDER);
        textCentered(trim(String.format(format, value), w - 2), x + w / 2, y + (h - 8) / 2, TEXT);
        if (hover && tooltip == null) tooltip = "Drag to set, scroll to step\nDouble-click or right-click to type";
        return value;
    }

    /**
     * A text field: click to type, Enter or a click elsewhere to accept, Escape to cancel. Returns the accepted text,
     * or the value while typing.
     */
    public String textField(String id, String value, int x, int y, int w, int h, int maxLength, String placeholder) {
        if (id.equals(editId)) {
            editSeen = true;
            rect(x, y, w, h, 0xFF101014);
            outline(x, y, w, h, ACCENT);
            boolean caret = System.currentTimeMillis() / 500 % 2 == 0;
            String shown = editText + (caret ? "_" : " ");
            int tw = width(shown);
            int tx = tw > w - 6 ? x + w - 3 - tw : x + 3;
            if (editFresh && !editText.isEmpty()) rect(tx - 1, y + 1, width(editText) + 1, h - 2, ACTIVE);
            text(shown, tx, y + (h - 8) / 2, TEXT);
            if (clickedOutside(x, y, w, h)) editCommit = true;
            block(x, y, w, h);
            if (!editCommit) return value;
            String typed = editText;
            stopEditing();
            return typed;
        }
        boolean hover = hovered(x, y, w, h);
        if (clicked(x, y, w, h, 0)) focusText(id, value, maxLength);
        rect(x, y, w, h, 0xFF101014);
        outline(x, y, w, h, hover ? ACCENT : BORDER);
        boolean empty = value == null || value.isEmpty();
        text(trim(empty ? placeholder : value, w - 6), x + 3, y + (h - 8) / 2, empty ? TEXT_DIM : TEXT);
        if (hover && tooltip == null) tooltip = "Click to type";
        return value;
    }

    /** Starts typing into a text field, as if it had been clicked. */
    public void focusText(String id, String value, int maxLength) {
        editId = id;
        editIsText = true;
        editMaxLength = maxLength;
        editText = value == null ? "" : value;
        editCommit = false;
        editFresh = true;
        editSeen = true;
        Keyboard.enableRepeatEvents(true);
    }

    /**
     * A dropdown: shows the item at index and opens its list on click. Returns the index picked, or index. The list
     * is drawn by {@link #drawPopups} and takes clicks first through {@link #popupClicks}.
     */
    public int dropdown(String id, List<String> items, int index, int x, int y, int w, int h, boolean enabled,
        String tip) {
        if (id.equals(pickedId)) {
            pickedId = null;
            index = picked;
        }
        boolean open = id.equals(dropId);
        boolean hover = enabled && hovered(x, y, w, h);
        rect(x, y, w, h, !enabled ? 0xFF1A1A20 : open ? ACTIVE : hover ? HOVER : PANEL_LIGHT);
        outline(x, y, w, h, open || hover ? ACCENT : BORDER);
        String label = index >= 0 && index < items.size() ? items.get(index) : "";
        text(trim(label, w - 14), x + 4, y + (h - 8) / 2, enabled ? TEXT : TEXT_DIM);
        // a small down arrow
        int ax = x + w - 9, ay = y + h / 2 - 1;
        int ac = enabled ? TEXT_DIM : BORDER;
        rect(ax, ay, 5, 1, ac);
        rect(ax + 1, ay + 1, 3, 1, ac);
        rect(ax + 2, ay + 2, 1, 1, ac);
        if (hover && tip != null) tooltip = tip;
        if (enabled && clicked(x, y, w, h, 0)) {
            dropId = id;
            dropItems = items;
            dropX = x;
            dropY = y + h;
            dropW = w;
            dropSelected = index;
        }
        return index;
    }

    /** Opens a list of actions at a point, like a context menu. The pick comes back from {@link #takePick}. */
    public void openMenu(String id, List<String> items, int x, int y) {
        dropId = id;
        dropItems = items;
        dropX = x;
        dropY = y;
        dropW = 60;
        dropSelected = -1;
    }

    /** The item picked in the menu opened with this id, or -1. */
    public int takePick(String id) {
        if (!id.equals(pickedId)) return -1;
        pickedId = null;
        return picked;
    }

    /** Whether a dropdown list is open. */
    public boolean popupOpen() {
        return dropId != null;
    }

    public void closePopups() {
        dropId = null;
    }

    private int[] dropRect(int screenW, int screenH) {
        int w = dropW;
        for (String s : dropItems) w = Math.max(w, width(s) + 12);
        int h = dropItems.size() * 12 + 2;
        int x = Math.max(0, Math.min(dropX, screenW - w - 1));
        int y = dropY + h > screenH ? Math.max(0, dropY - h - 14) : dropY;
        return new int[] { x, y, w, h };
    }

    /** Resolves clicks on an open dropdown list before any widget sees them. Call right after {@link #begin}. */
    public void popupClicks(int screenW, int screenH) {
        if (dropId == null) return;
        int[] r = dropRect(screenW, screenH);
        for (int i = 0; i < dropItems.size(); i++) {
            if (clicked(r[0], r[1] + 1 + i * 12, r[2], 12, 0)) {
                pickedId = dropId;
                picked = i;
                dropId = null;
                return;
            }
        }
        if (hasPendingClick()) {
            // a click anywhere else only closes the list
            block(0, 0, screenW, screenH);
            dropId = null;
        }
    }

    /** Draws an open dropdown list over everything. Call last. */
    public void drawPopups(int screenW, int screenH) {
        if (dropId == null) return;
        int[] r = dropRect(screenW, screenH);
        rect(r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, BORDER);
        rect(r[0], r[1], r[2], r[3], 0xF8141418);
        for (int i = 0; i < dropItems.size(); i++) {
            int iy = r[1] + 1 + i * 12;
            boolean hover = hovered(r[0], iy, r[2], 12);
            if (hover) rect(r[0], iy, r[2], 12, HOVER);
            if (i == dropSelected) rect(r[0], iy, 2, 12, ACCENT);
            text(dropItems.get(i), r[0] + 6, iy + 2, i == dropSelected ? TEXT : 0xFFC8C8D2);
        }
        // no tooltips from widgets under the list
        if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) tooltip = null;
    }

    /** A labelled {@link #slider} filling one panel row. */
    public double sliderRow(String label, double value, double min, double max, double step, String format, int x,
        int y, int w) {
        int lw = Math.min(64, w / 2 - 4);
        text(trim(label, lw), x, y + 2, TEXT_DIM);
        return slider(label + "@" + x, value, min, max, step, format, x + lw + 4, y, w - lw - 4, 11);
    }

    /** A labelled {@link #number} filling one panel row. */
    public double numberRow(String label, double value, double step, double min, double max, String format, int x,
        int y, int w) {
        int lw = Math.min(64, w / 2 - 4);
        text(trim(label, lw), x, y + 2, TEXT_DIM);
        return number(label + "@" + x, value, step, min, max, format, x + lw + 4, y, w - lw - 4, 11);
    }

    /**
     * Takes the clicks and scroll of this frame away from the widgets drawn next, for a dialog drawn on top that
     * must get them instead. Give them back with {@link #releaseInput}.
     */
    public Object holdInput() {
        Object[] held = { new ArrayList<>(clicks), scrollDelta };
        clicks.clear();
        scrollDelta = 0;
        return held;
    }

    @SuppressWarnings("unchecked")
    public void releaseInput(Object held) {
        Object[] h = (Object[]) held;
        clicks.clear();
        clicks.addAll((List<Click>) h[0]);
        scrollDelta = (Integer) h[1];
    }

    public boolean hasPendingClick() {
        for (Click c : clicks) if (!c.consumed) return true;
        return false;
    }

    public boolean hovered(int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Consumes a click with the given button inside the rectangle. */
    public boolean clicked(int x, int y, int w, int h, int button) {
        for (Click c : clicks) {
            if (!c.consumed && c.button == button && c.x >= x && c.x < x + w && c.y >= y && c.y < y + h) {
                c.consumed = true;
                clickX = c.x;
                clickY = c.y;
                return true;
            }
        }
        return false;
    }

    /** Consumes any click inside the rectangle (so it does not fall through to things below). */
    public void block(int x, int y, int w, int h) {
        for (Click c : clicks) {
            if (!c.consumed && c.x >= x && c.x < x + w && c.y >= y && c.y < y + h) c.consumed = true;
        }
    }

    public int takeScroll(int x, int y, int w, int h) {
        if (scrollDelta != 0 && hovered(x, y, w, h)) {
            int d = scrollDelta;
            scrollDelta = 0;
            return d;
        }
        return 0;
    }

    public void rect(int x, int y, int w, int h, int color) {
        Gui.drawRect(x, y, x + w, y + h, color);
    }

    public void outline(int x, int y, int w, int h, int color) {
        rect(x, y, w, 1, color);
        rect(x, y + h - 1, w, 1, color);
        rect(x, y, 1, h, color);
        rect(x + w - 1, y, 1, h, color);
    }

    public void text(String s, int x, int y, int color) {
        font().drawStringWithShadow(s, x, y, color);
    }

    public void textCentered(String s, int cx, int y, int color) {
        font().drawStringWithShadow(s, cx - font().getStringWidth(s) / 2, y, color);
    }

    public int width(String s) {
        return font().getStringWidth(s);
    }

    public String trim(String s, int width) {
        if (width(s) <= width) return s;
        return font().trimStringToWidth(s, Math.max(0, width - width("..."))) + "...";
    }

    public boolean button(String label, int x, int y, int w, int h) {
        return button(label, x, y, w, h, false, null);
    }

    public boolean button(String label, int x, int y, int w, int h, boolean active, String tip) {
        boolean hover = hovered(x, y, w, h);
        rect(x, y, w, h, active ? ACTIVE : hover ? HOVER : PANEL_LIGHT);
        outline(x, y, w, h, active ? ACCENT : BORDER);
        textCentered(trim(label, w - 4), x + w / 2, y + (h - 8) / 2, TEXT);
        if (hover && tip != null) tooltip = tip;
        return clicked(x, y, w, h, 0);
    }

    /** A checkbox with a label. Returns the (possibly toggled) value. */
    public boolean checkbox(String label, boolean value, int x, int y, int w) {
        int h = 11;
        boolean hover = hovered(x, y, w, h);
        if (hover) rect(x - 1, y - 1, w + 2, h + 1, 0x30FFFFFF);
        rect(x, y + 1, 9, 9, PANEL_LIGHT);
        outline(x, y + 1, 9, 9, hover ? ACCENT : BORDER);
        if (value) rect(x + 2, y + 3, 5, 5, ACCENT);
        text(trim(label, w - 13), x + 13, y + 2, TEXT);
        if (clicked(x, y, w, h, 0)) return !value;
        return value;
    }

    /** "label [-] value [+]" style numeric editor. Returns the new value. */
    public double stepper(String label, double value, double step, double min, double max, String format, int x, int y,
        int w) {
        int bw = 11;
        text(trim(label, w - 70), x, y + 2, TEXT_DIM);
        int vx = x + w - bw * 2 - 48;
        boolean minus = button("-", vx, y, bw, 11);
        boolean plus = button("+", vx + bw + 48, y, bw, 11);
        value = number(label + "@" + x, value, step, min, max, format, vx + bw + 1, y, 46, 11);
        if (minus) value -= step * multiplier();
        if (plus) value += step * multiplier();
        return clamp(value, min, max);
    }

    public void separator(int x, int y, int w) {
        rect(x, y, w, 1, BORDER);
    }

    public void diamond(int cx, int cy, int r, int color) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        float a = (color >>> 24 & 255) / 255.0F;
        float rr = (color >> 16 & 255) / 255.0F;
        float g = (color >> 8 & 255) / 255.0F;
        float b = (color & 255) / 255.0F;
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA_F(rr, g, b, a);
        t.addVertex(cx, cy - r, 0);
        t.addVertex(cx - r, cy, 0);
        t.addVertex(cx, cy + r, 0);
        t.addVertex(cx + r, cy, 0);
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
    }

    public void drawTooltip(int screenWidth, int screenHeight) {
        if (tooltip == null) return;
        String[] lines = tooltip.split("\n");
        int w = 0;
        for (String l : lines) w = Math.max(w, width(l));
        int h = lines.length * 10 + 4;
        int x = Math.min(mouseX + 10, screenWidth - w - 8);
        int y = Math.max(2, Math.min(mouseY - h - 4, screenHeight - h - 2));
        rect(x - 1, y - 1, w + 8, h + 2, BORDER);
        rect(x, y, w + 6, h, 0xF0101014);
        for (int i = 0; i < lines.length; i++) text(lines[i], x + 3, y + 3 + i * 10, TEXT);
    }
}
