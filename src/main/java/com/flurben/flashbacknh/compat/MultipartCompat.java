package com.flurben.flashbacknh.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import com.flurben.flashbacknh.FlashbackNH;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.ByteBuf;

/**
 * Forge Multipart (microblocks and other multiparts). Its blocks have no tile entity of their own on the client: a
 * server sends a chunk's multiparts in a packet of its own when a player starts watching the chunk, and the id table of
 * part types once after joining. A recording that starts in the middle of a session has neither, so the world snapshot
 * makes them from the client's multiparts with Forge Multipart's own code. Optional: does nothing without the mod.
 */
public final class MultipartCompat {

    private static boolean initialised;
    private static boolean available;
    private static Class<?> tileClass;
    private static Object sph, cph, tileCompanion, registry;
    private static Method getDescPacket, registryChannel, writeIdMap, handlePacket, createFromNbt, toPacket;
    private static Method nameMap, beforeServerStart;
    private static java.lang.reflect.Constructor<?> newPacket, readPacket;
    private static boolean warned;

    private MultipartCompat() {}

    private static synchronized boolean init() {
        if (initialised) return available;
        initialised = true;
        try {
            tileClass = Class.forName("codechicken.multipart.TileMultipart");
            Class<?> sphClass = Class.forName("codechicken.multipart.handler.MultipartSPH$");
            sph = sphClass.getField("MODULE$")
                .get(null);
            Class<?> cphClass = Class.forName("codechicken.multipart.handler.MultipartCPH$");
            cph = cphClass.getField("MODULE$")
                .get(null);
            Class<?> tileCompanionClass = Class.forName("codechicken.multipart.TileMultipart$");
            tileCompanion = tileCompanionClass.getField("MODULE$")
                .get(null);
            Class<?> registryClass = Class.forName("codechicken.multipart.MultiPartRegistry$");
            registry = registryClass.getField("MODULE$")
                .get(null);
            Class<?> packetClass = Class.forName("codechicken.lib.packet.PacketCustom");
            getDescPacket = sphClass.getMethod("getDescPacket", Chunk.class, Iterator.class);
            registryChannel = sphClass.getMethod("registryChannel");
            writeIdMap = registryClass.getMethod("writeIDMap", packetClass);
            handlePacket = cphClass
                .getMethod("handlePacket", packetClass, Minecraft.class, INetHandlerPlayClient.class);
            createFromNbt = tileCompanionClass.getMethod("createFromNBT", NBTTagCompound.class);
            toPacket = packetClass.getMethod("toPacket");
            nameMap = registryClass.getMethod("codechicken$multipart$MultiPartRegistry$$nameMap");
            beforeServerStart = registryClass.getMethod("beforeServerStart");
            newPacket = packetClass.getConstructor(Object.class, int.class);
            readPacket = packetClass.getConstructor(ByteBuf.class);
            available = true;
        } catch (ClassNotFoundException e) {
            available = false;
        } catch (Throwable t) {
            FlashbackNH.LOG.warn("Forge Multipart found but not usable, microblocks may be missing in replays", t);
            available = false;
        }
        return available;
    }

    public static boolean isMultipart(TileEntity te) {
        return te != null && init() && tileClass.isInstance(te);
    }

    /**
     * The packets a server sends for these chunks' multiparts, and the part id table before them. Client thread.
     *
     * @param chunks the chunks, each with its multipart tile entities
     */
    public static List<Packet> snapshotPackets(List<Chunk> chunks, List<List<TileEntity>> tiles) {
        if (chunks.isEmpty() || !init()) return Collections.emptyList();
        List<Packet> out = new ArrayList<>();
        try {
            Object ids = newPacket.newInstance(registryChannel.invoke(sph), 1);
            writeIdMap.invoke(registry, ids);
            out.add((Packet) toPacket.invoke(ids));
        } catch (Throwable t) {
            warn("part id table", t);
            return out;
        }
        for (int i = 0; i < chunks.size(); i++) {
            try {
                Object packet = getDescPacket.invoke(
                    sph,
                    chunks.get(i),
                    tiles.get(i)
                        .iterator());
                if (packet != null) out.add((Packet) toPacket.invoke(packet));
            } catch (Throwable t) {
                warn("chunk " + chunks.get(i).xPosition + "," + chunks.get(i).zPosition, t);
            }
        }
        return out;
    }

    /** Whether saved tile entity data is a multipart's, which the client cannot load as it is. */
    public static boolean isSavedMultipart(NBTTagCompound tag) {
        return "savedMultipart".equals(tag.getString("id")) && init();
    }

    /**
     * Puts multiparts into a chunk of a replay from their saved data, through the same packet a server would have
     * sent. For replays recorded before the world snapshot included those packets. Client thread.
     */
    public static void restore(World world, int chunkX, int chunkZ, List<NBTTagCompound> tags) {
        if (tags.isEmpty() || !init()) return;
        try {
            // part ids are written and read back here with the same table, but there has to be one: a client that
            // has not joined a world since starting has none yet, and then gets the one a server would make
            Object names = nameMap.invoke(registry);
            if ((Boolean) names.getClass()
                .getMethod("isEmpty")
                .invoke(names)) beforeServerStart.invoke(registry);
            List<TileEntity> parts = new ArrayList<>();
            for (NBTTagCompound tag : tags) {
                TileEntity te = (TileEntity) createFromNbt.invoke(tileCompanion, tag);
                if (te != null) parts.add(te);
            }
            if (parts.isEmpty()) return;
            Object packet = getDescPacket.invoke(sph, world.getChunkFromChunkCoords(chunkX, chunkZ), parts.iterator());
            if (packet != null) deliver((FMLProxyPacket) toPacket.invoke(packet));
        } catch (Throwable t) {
            warn("saved multiparts in chunk " + chunkX + "," + chunkZ, t);
        }
    }

    private static void deliver(FMLProxyPacket proxy) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        Object read = readPacket.newInstance(
            proxy.payload()
                .copy());
        handlePacket.invoke(cph, read, mc, mc.getNetHandler());
    }

    private static void warn(String what, Throwable t) {
        if (warned) {
            FlashbackNH.LOG.debug("Forge Multipart: unable to handle {}", what, t);
            return;
        }
        warned = true;
        FlashbackNH.LOG.warn("Forge Multipart: unable to handle {}, some microblocks may be missing", what, t);
    }
}
