package com.flurben.flashbacknh.replay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraftforge.client.IRenderHandler;

import com.flurben.flashbacknh.editor.Visuals;

/**
 * Applies visual settings that map onto game state (clouds, sky renderer) and restores the original state when the
 * replay closes. Everything else is applied through events and small render hooks.
 */
public final class VisualsApplier {

    private static final IRenderHandler EMPTY_SKY = new IRenderHandler() {

        @Override
        public void render(float partialTicks, WorldClient world, Minecraft mc) {}
    };

    private static Boolean savedClouds;
    private static WorldClient skyWorld;
    private static IRenderHandler savedSky;

    private VisualsApplier() {}

    public static void apply(Visuals v, boolean exporting) {
        Minecraft mc = Minecraft.getMinecraft();
        if (savedClouds == null) savedClouds = mc.gameSettings.clouds;
        mc.gameSettings.clouds = savedClouds && v.renderClouds;

        WorldClient world = mc.theWorld;
        if (world == null) return;
        if (!v.renderSky) {
            if (skyWorld != world) {
                skyWorld = world;
                savedSky = world.provider.getSkyRenderer();
            }
            if (world.provider.getSkyRenderer() != EMPTY_SKY) world.provider.setSkyRenderer(EMPTY_SKY);
        } else if (skyWorld == world && world.provider.getSkyRenderer() == EMPTY_SKY) {
            world.provider.setSkyRenderer(savedSky);
            skyWorld = null;
            savedSky = null;
        }
    }

    public static void restore() {
        Minecraft mc = Minecraft.getMinecraft();
        if (savedClouds != null) {
            mc.gameSettings.clouds = savedClouds;
            savedClouds = null;
        }
        skyWorld = null;
        savedSky = null;
    }
}
