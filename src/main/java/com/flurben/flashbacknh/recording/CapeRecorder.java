package com.flurben.flashbacknh.recording;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.format.CapeData;
import com.flurben.flashbacknh.format.ReplayFormat;

/** Notes the cape of every player the recorder sees, see {@link CapeData}. Runs on the client thread. */
final class CapeRecorder {

    private static final int INTERVAL = 20;
    private static final int MAX_IMAGE_TRIES = 10;
    private static final int MAX_IMAGE_SIZE = 4096;

    private final CapeData data = new CapeData();
    private final Map<String, Integer> imageTries = new HashMap<>();
    private int ticks;
    private boolean dirty;

    void tick(Minecraft mc) {
        if (mc.theWorld == null || ticks++ % INTERVAL != 0) return;
        if (mc.thePlayer != null) note(mc, mc.thePlayer);
        for (Object o : mc.theWorld.playerEntities) {
            if (o instanceof AbstractClientPlayer) note(mc, (AbstractClientPlayer) o);
        }
    }

    private void note(Minecraft mc, AbstractClientPlayer player) {
        UUID id = player.getUniqueID();
        if (id == null) return;
        ResourceLocation cape;
        try {
            cape = player.func_152122_n() ? player.getLocationCape() : null;
        } catch (RuntimeException e) {
            return;
        }
        // A vanilla cape comes with the player's profile, which the replay has as well.
        if (cape == null || cape.getResourcePath()
            .startsWith("skins/")) return;
        String name = cape.toString();
        String key = id.toString();
        if (!name.equals(data.players.get(key))) {
            data.players.put(key, name);
            dirty = true;
        }
        if (!data.images.containsKey(name) && !isResource(mc, cape)) {
            int tries = imageTries.merge(name, 1, Integer::sum);
            if (tries <= MAX_IMAGE_TRIES) {
                String png = readTexture(mc, cape);
                if (png != null) {
                    data.images.put(name, png);
                    dirty = true;
                }
            }
        }
    }

    private static boolean isResource(Minecraft mc, ResourceLocation location) {
        try {
            mc.getResourceManager()
                .getResource(location)
                .getInputStream()
                .close();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Reads back a texture that only exists on the graphics card, as a base64 PNG. */
    private static String readTexture(Minecraft mc, ResourceLocation location) {
        ITextureObject texture = mc.getTextureManager()
            .getTexture(location);
        if (texture == null) return null;
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getGlTextureId());
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            // a download that has not finished yet shows a blank placeholder
            if (w <= 1 || h <= 1 || w > MAX_IMAGE_SIZE || h > MAX_IMAGE_SIZE) return null;
            IntBuffer pixels = BufferUtils.createIntBuffer(w * h);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
            int[] argb = new int[w * h];
            pixels.get(argb);
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, w, h, argb, 0, w);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return Base64.getEncoder()
                .encodeToString(out.toByteArray());
        } catch (Throwable t) {
            FlashbackNH.LOG.debug("Unable to read cape texture {}", location, t);
            return null;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    /** Writes capes.json into the work directory when something new was seen. */
    void save(File workDir) {
        if (!dirty) return;
        dirty = false;
        try (FileOutputStream out = new FileOutputStream(new File(workDir, ReplayFormat.ENTRY_CAPES))) {
            out.write(
                data.toJson()
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            FlashbackNH.LOG.debug("Unable to save capes", e);
        }
    }
}
