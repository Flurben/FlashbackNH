package com.flurben.flashbacknh.recording;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.flurben.flashbacknh.replay.ReplayHandler;

/**
 * The last tile entity data packet the server sent for tile entities whose description the client cannot make itself.
 * A world snapshot normally asks each tile entity on the client for the packet the server would send, but some mods
 * build that packet from server-only state. Applied Energistics does: a cable's connections come from its grid, which
 * only exists on the server, so a cable described by the client has none. For those the snapshot takes the server's
 * own packet from here, which works on any server, not only in singleplayer.
 */
public final class ServerTileData {

    /** Classes whose state reaches the client only through these packets, so the last one is always current. */
    private static final String[] SYNCED_BY_PACKET = { "appeng.tile.AEBaseTile" };

    private static final Map<Class<?>, Boolean> KINDS = new ConcurrentHashMap<>();
    private static final Map<Long, S35PacketUpdateTileEntity> PACKETS = new ConcurrentHashMap<>();
    private static volatile WeakReference<World> world = new WeakReference<>(null);

    private ServerTileData() {}

    /** A packet the client is about to handle. Client thread. */
    public static void received(S35PacketUpdateTileEntity packet) {
        if (ReplayHandler.isReplaying()) return;
        World current = Minecraft.getMinecraft().theWorld;
        if (current == null) return;
        if (world.get() != current) {
            PACKETS.clear();
            world = new WeakReference<>(current);
        }
        int x = packet.func_148856_c(), y = packet.func_148855_d(), z = packet.func_148854_e();
        TileEntity te = current.getTileEntity(x, y, z);
        if (te == null || !isSyncedByPacket(te.getClass())) return;
        PACKETS.put(key(x, y, z), packet);
    }

    /** The server's last packet for this tile entity, or null to ask the tile entity. Any thread. */
    public static Packet packetFor(TileEntity te) {
        if (te.getWorldObj() != world.get() || !isSyncedByPacket(te.getClass())) return null;
        return PACKETS.get(key(te.xCoord, te.yCoord, te.zCoord));
    }

    private static boolean isSyncedByPacket(Class<?> type) {
        return KINDS.computeIfAbsent(type, t -> {
            for (Class<?> c = t; c != null; c = c.getSuperclass()) {
                for (String name : SYNCED_BY_PACKET) if (c.getName()
                    .equals(name)) return true;
            }
            return false;
        });
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
