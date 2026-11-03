package com.flurben.flashbacknh.replay.net;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

import com.flurben.flashbacknh.net.VarInts;
import com.flurben.flashbacknh.replay.ReplayConstants;

/**
 * Byte-level filtering of recorded packets before they are decoded. Packets that only make sense for the player who
 * recorded (inventory, health, abilities, GUIs) are dropped; packets that set up the client player are rewritten so the
 * viewer becomes a free camera with its own entity id.
 */
public final class PacketFilter {

    public static final int GAMEMODE_CREATIVE = 1;

    /** Mod channels ignored during playback (from the config). */
    public static volatile Set<String> ignoredChannels = Collections.emptySet();

    public interface Listener {

        /** The recording's join game packet, with the entity id of the recording player. */
        void onJoinGame(int originalEntityId);

        /** A teleport of the recording player (dropped, but useful to place the camera). */
        void onPlayerPosLook(double x, double y, double z, float yaw, float pitch);
    }

    private PacketFilter() {}

    /** Returns the packet bytes to deliver (possibly modified), or null to drop the packet. */
    public static byte[] filter(byte[] data, Listener listener) {
        int id = VarInts.peekId(data);
        int off = VarInts.idLength(data);
        switch (id) {
            case 0x00: // keep alive
            case 0x06: // update health
            case 0x09: // held item change
            case 0x1F: // set experience
            case 0x2D: // open window
            case 0x2E: // close window
            case 0x2F: // set slot
            case 0x30: // window items
            case 0x31: // window property
            case 0x32: // confirm transaction
            case 0x36: // sign editor open
            case 0x37: // statistics
            case 0x39: // player abilities
            case 0x3A: // tab complete
            case 0x40: // disconnect
                return null;
            case 0x01: { // join game: become the camera
                if (data.length < off + 5) return null;
                byte[] copy = data.clone();
                int original = readInt(copy, off);
                writeInt(copy, off, ReplayConstants.CAMERA_ENTITY_ID);
                int mode = copy[off + 4] & 0xFF;
                copy[off + 4] = (byte) ((mode & 8) | GAMEMODE_CREATIVE);
                if (listener != null) listener.onJoinGame(original);
                return copy;
            }
            case 0x07: { // respawn
                if (data.length < off + 6) return data;
                byte[] copy = data.clone();
                copy[off + 5] = (byte) GAMEMODE_CREATIVE;
                return copy;
            }
            case 0x08: { // player position: belongs to the recording player, not the camera
                if (listener != null && data.length >= off + 8 * 3 + 8) {
                    double x = Double.longBitsToDouble(readLong(data, off));
                    double y = Double.longBitsToDouble(readLong(data, off + 8));
                    double z = Double.longBitsToDouble(readLong(data, off + 16));
                    float yaw = Float.intBitsToFloat(readInt(data, off + 24));
                    float pitch = Float.intBitsToFloat(readInt(data, off + 28));
                    listener.onPlayerPosLook(x, y, z, yaw, pitch);
                }
                return null;
            }
            case 0x2B: { // change game state
                if (data.length <= off) return null;
                int reason = data[off] & 0xFF;
                // keep weather (1, 2, 7, 8) and arrow hit sound (6); drop bed message, game mode, credits, demo
                return reason == 1 || reason == 2 || reason == 6 || reason == 7 || reason == 8 ? data : null;
            }
            case 0x3F: { // custom payload
                String channel = readString(data, off);
                if (channel == null) return data;
                if ("MC|BOpen".equals(channel) || "MC|TrList".equals(channel)) return null;
                if (ignoredChannels.contains(channel)) return null;
                return data;
            }
            default:
                return data;
        }
    }

    static String readString(byte[] data, int off) {
        int len = 0;
        int shift = 0;
        int i = off;
        while (i < data.length) {
            int b = data[i++] & 0xFF;
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 35) return null;
        }
        if (len < 0 || i + len > data.length) return null;
        return new String(data, i, len, StandardCharsets.UTF_8);
    }

    static int readInt(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    static long readLong(byte[] d, int o) {
        return ((long) readInt(d, o) << 32) | (readInt(d, o + 4) & 0xFFFFFFFFL);
    }

    static void writeInt(byte[] d, int o, int v) {
        d[o] = (byte) (v >>> 24);
        d[o + 1] = (byte) (v >>> 16);
        d[o + 2] = (byte) (v >>> 8);
        d[o + 3] = (byte) v;
    }
}
