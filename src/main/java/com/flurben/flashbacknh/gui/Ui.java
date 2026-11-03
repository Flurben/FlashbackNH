package com.flurben.flashbacknh.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;

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
    public String tooltip;
    private int scrollDelta;

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
    }

    /** Drops clicks nobody consumed. Returns true if an unconsumed click existed. */
    public boolean end() {
        boolean any = false;
        for (Click c : clicks) any |= !c.consumed;
        clicks.clear();
        scrollDelta = 0;
        return any;
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
        int vx = x + w - bw * 2 - 44;
        boolean minus = button("-", vx, y, bw, 11);
        String v = String.format(format, value);
        textCentered(v, vx + bw + 22, y + 2, TEXT);
        boolean plus = button("+", vx + bw + 44, y, bw, 11);
        double mult = net.minecraft.client.gui.GuiScreen.isShiftKeyDown() ? 10
            : net.minecraft.client.gui.GuiScreen.isCtrlKeyDown() ? 0.1 : 1;
        if (minus) value -= step * mult;
        if (plus) value += step * mult;
        int scroll = takeScroll(vx, y, bw * 2 + 44, 11);
        if (scroll != 0) value += Math.signum(scroll) * step * mult;
        return Math.max(min, Math.min(max, value));
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
