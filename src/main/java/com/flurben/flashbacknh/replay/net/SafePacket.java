package com.flurben.flashbacknh.replay.net;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;

import com.flurben.flashbacknh.FlashbackNH;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;

/**
 * Wraps every replayed packet so that one that throws while being handled (a mod reacting badly to being replayed, or
 * vanilla state that differs from the original session) only logs an error instead of crashing the game.
 */
public final class SafePacket extends Packet {

    private static final Set<String> warned = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final Packet packet;

    public SafePacket(Packet packet) {
        this.packet = packet;
    }

    @Override
    public void readPacketData(PacketBuffer data) throws IOException {}

    @Override
    public void writePacketData(PacketBuffer data) throws IOException {}

    @Override
    public void processPacket(INetHandler handler) {
        String key = null;
        try {
            if (packet instanceof FMLProxyPacket) {
                key = "channel " + ((FMLProxyPacket) packet).channel();
                ((FMLProxyPacket) packet).payload()
                    .resetReaderIndex();
            }
            packet.processPacket(handler);
        } catch (Throwable t) {
            if (key == null) key = packet.getClass()
                .getName();
            if (warned.add(key)) {
                FlashbackNH.LOG.error("Packet {} failed during replay (further errors hidden)", key, t);
            }
        }
    }
}
