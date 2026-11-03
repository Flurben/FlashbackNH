package com.flurben.flashbacknh.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.opengl.GL11;

/** Small toast-style messages in the top right corner. */
public final class Notifications {

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private static final class Entry {

        final String title;
        final String text;
        final long created = System.currentTimeMillis();
        final long duration;

        Entry(String title, String text, long duration) {
            this.title = title;
            this.text = text;
            this.duration = duration;
        }
    }

    private Notifications() {}

    public static synchronized void show(String title, String text) {
        show(title, text, 3500);
    }

    public static synchronized void show(String title, String text, long durationMs) {
        ENTRIES.add(new Entry(title, text, durationMs));
        while (ENTRIES.size() > 5) ENTRIES.remove(0);
    }

    public static synchronized void render() {
        if (ENTRIES.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        FontRenderer font = mc.fontRenderer;
        long now = System.currentTimeMillis();
        int y = 4;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        for (Iterator<Entry> it = ENTRIES.iterator(); it.hasNext();) {
            Entry e = it.next();
            long age = now - e.created;
            if (age > e.duration) {
                it.remove();
                continue;
            }
            float alpha = 1.0f;
            if (age < 200) alpha = age / 200.0f;
            else if (age > e.duration - 400) alpha = (e.duration - age) / 400.0f;
            int a = Math.max(4, (int) (alpha * 255));
            int width = Math.max(font.getStringWidth(e.title), e.text == null ? 0 : font.getStringWidth(e.text)) + 12;
            int height = e.text == null || e.text.isEmpty() ? 16 : 26;
            int x = res.getScaledWidth() - width - 4;
            Gui.drawRect(x, y, x + width, y + height, ((int) (alpha * 0xC0) << 24) | 0x101018);
            Gui.drawRect(x, y, x + 2, y + height, (a << 24) | 0xE04040);
            font.drawStringWithShadow(e.title, x + 6, y + 4, (a << 24) | 0xFFFFFF);
            if (height > 16) font.drawStringWithShadow(e.text, x + 6, y + 15, (a << 24) | 0xAAAAAA);
            y += height + 3;
        }
        GL11.glPopAttrib();
    }
}
