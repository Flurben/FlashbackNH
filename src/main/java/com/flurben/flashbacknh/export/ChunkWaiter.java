package com.flurben.flashbacknh.export;

import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.client.Minecraft;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.mixins.early.AccessorRenderGlobal;

/**
 * Tells whether all visible chunks have finished building, so exported frames never contain holes. Supports the
 * vanilla renderer and Angelica's Celeritas renderer (read-only reflection, no Angelica dependency).
 */
public final class ChunkWaiter {

    private static boolean angelicaChecked;
    private static Method angelicaInstance;
    private static Method angelicaComplete;

    private ChunkWaiter() {}

    public static boolean isComplete() {
        Object celeritas = celeritasRenderer();
        if (celeritas != null) {
            try {
                return (Boolean) angelicaComplete.invoke(celeritas);
            } catch (Throwable t) {
                FlashbackNH.LOG.debug("Celeritas completion check failed", t);
                angelicaComplete = null;
            }
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            List<?> pending = ((AccessorRenderGlobal) mc.renderGlobal).flashbacknh$getWorldRenderersToUpdate();
            return pending == null || pending.isEmpty();
        } catch (Throwable t) {
            return true;
        }
    }

    private static Object celeritasRenderer() {
        if (!angelicaChecked) {
            angelicaChecked = true;
            try {
                Class<?> c = Class.forName("com.gtnewhorizons.angelica.rendering.celeritas.CeleritasWorldRenderer");
                // older Angelica 2.x only has getInstance, which throws until the renderer exists
                try {
                    angelicaInstance = c.getMethod("getInstanceOrNull");
                } catch (NoSuchMethodException e) {
                    angelicaInstance = c.getMethod("getInstance");
                }
                angelicaComplete = c.getMethod("isTerrainRenderComplete");
                FlashbackNH.LOG.info("Angelica detected: export waits for Celeritas chunk builds");
            } catch (Throwable ignored) {
                angelicaInstance = null;
            }
        }
        if (angelicaInstance == null || angelicaComplete == null) return null;
        try {
            return angelicaInstance.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
