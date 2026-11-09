package com.flurben.flashbacknh.format;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;

/**
 * The capes players wore while recording, saved as capes.json in the replay archive. Capes that mods give players
 * (GregTech, Galacticraft, ...) reach a client through mod packets or downloads that a replay does not repeat, so the
 * recorder keeps what each player actually wore instead.
 */
public final class CapeData {

    private static final Gson GSON = new Gson();

    /** Player uuid to the texture of its cape. */
    public Map<String, String> players = new HashMap<>();
    /** Textures that are not in any resource pack, such as downloaded capes: texture name to base64 PNG. */
    public Map<String, String> images = new HashMap<>();

    public String toJson() {
        return GSON.toJson(this);
    }

    public static CapeData fromJson(String json) {
        CapeData data;
        try {
            data = GSON.fromJson(json, CapeData.class);
        } catch (RuntimeException e) {
            data = null;
        }
        if (data == null) data = new CapeData();
        if (data.players == null) data.players = new HashMap<>();
        if (data.images == null) data.images = new HashMap<>();
        return data;
    }
}
