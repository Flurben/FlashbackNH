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
 * Wraps a mod packet so it is processed in order with vanilla packets on the client thread, and so that a mod that
 * throws while handling a replayed packet only logs an error instead of breaking the replay.
 */
public final class SafeProxyPacket extends Packet {

    private static final Set<String> warned = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final FMLProxyPacket proxy;

    public SafeProxyPacket(FMLProxyPacket proxy) {
        this.proxy = proxy;
    }

    @Override
    public void readPacketData(PacketBuffer data) throws IOException {}

    @Override
    public void writePacketData(PacketBuffer data) throws IOException {}

    @Override
    public void processPacket(INetHandler handler) {
        try {
            proxy.payload()
                .resetReaderIndex();
            proxy.processPacket(handler);
        } catch (Throwable t) {
            if (warned.add(proxy.channel())) {
                FlashbackNH.LOG.error("Mod packet on channel '{}' failed during replay (further errors hidden)", proxy.channel(), t);
            }
        }
    }
}
