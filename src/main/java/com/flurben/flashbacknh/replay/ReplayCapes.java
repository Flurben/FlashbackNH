package com.flurben.flashbacknh.replay;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.format.CapeData;

/** The recorded capes of a replay, handed to the players the replay spawns. See {@link CapeData}. */
public final class ReplayCapes {

    private final Map<UUID, String> players = new HashMap<>();
    private final Map<String, String> images;
    private final Map<String, ResourceLocation> textures = new HashMap<>();

    public ReplayCapes(CapeData data) {
        for (Map.Entry<String, String> e : data.players.entrySet()) {
            try {
                players.put(UUID.fromString(e.getKey()), e.getValue());
            } catch (IllegalArgumentException ignored) {}
        }
        images = data.images;
    }

    public boolean isEmpty() {
        return players.isEmpty();
    }

    /** The cape texture of a player, or null to leave the player's cape alone. Client thread only. */
    public ResourceLocation capeOf(UUID player) {
        if (player == null) return null;
        String name = players.get(player);
        if (name == null) return null;
        if (textures.containsKey(name)) return textures.get(name);
        ResourceLocation location = load(name);
        textures.put(name, location);
        return location;
    }

    private ResourceLocation load(String name) {
        String png = images.get(name);
        if (png == null) {
            // from a resource pack: only if the mod it came from is installed, or the cape would show as missing
            ResourceLocation location = new ResourceLocation(name);
            try {
                Minecraft.getMinecraft()
                    .getResourceManager()
                    .getResource(location)
                    .getInputStream()
                    .close();
                return location;
            } catch (Exception e) {
                FlashbackNH.LOG.info("Recorded cape {} is not installed, showing the normal cape", name);
                return null;
            }
        }
        try {
            BufferedImage image = ImageIO.read(
                new ByteArrayInputStream(
                    Base64.getDecoder()
                        .decode(png)));
            if (image == null) return null;
            ResourceLocation location = new ResourceLocation(
                "flashbacknh",
                "replay_capes/" + Integer.toHexString(name.hashCode()) + "_" + textures.size());
            Minecraft.getMinecraft()
                .getTextureManager()
                .loadTexture(location, new DynamicTexture(image));
            return location;
        } catch (Throwable t) {
            FlashbackNH.LOG.warn("Unable to load recorded cape {}", name, t);
            return null;
        }
    }

    /** Frees the textures made for downloaded capes. */
    public void release() {
        for (Map.Entry<String, ResourceLocation> e : textures.entrySet()) {
            if (e.getValue() != null && images.containsKey(e.getKey())) {
                Minecraft.getMinecraft()
                    .getTextureManager()
                    .deleteTexture(e.getValue());
            }
        }
        textures.clear();
    }
}
