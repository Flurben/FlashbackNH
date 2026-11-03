package com.flurben.flashbacknh.net;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;

import com.flurben.flashbacknh.FlashbackNH;
import com.google.common.collect.BiMap;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Converts clientbound PLAY packets to and from their wire representation ({@code varint id + payload}). */
public final class PacketCodec {

    private static final Set<Class<?>> warnedClasses = Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    private PacketCodec() {}

    @SuppressWarnings("unchecked")
    public static BiMap<Integer, Class<?>> clientbound() {
        return (BiMap<Integer, Class<?>>) EnumConnectionState.PLAY.func_150755_b();
    }

    public static Integer idOf(Packet packet) {
        return clientbound().inverse()
            .get(packet.getClass());
    }

    /**
     * Serializes a packet. Returns null if the packet is not a known clientbound PLAY packet.
     */
    public static byte[] encode(Packet packet) throws IOException {
        if (packet instanceof FMLProxyPacket) {
            packet = ((FMLProxyPacket) packet).toS3FPacket();
        }
        Integer id = idOf(packet);
        if (id == null) {
            if (warnedClasses.add(packet.getClass())) {
                FlashbackNH.LOG.warn("Cannot record unknown packet class {}", packet.getClass().getName());
            }
            return null;
        }
        ByteBuf raw = Unpooled.buffer(256);
        try {
            PacketBuffer buf = new PacketBuffer(raw);
            buf.writeVarIntToBuffer(id);
            packet.writePacketData(buf);
            byte[] out = new byte[raw.readableBytes()];
            raw.getBytes(raw.readerIndex(), out);
            return out;
        } finally {
            raw.release();
        }
    }

    public static Packet decode(byte[] data) throws IOException {
        PacketBuffer buf = new PacketBuffer(Unpooled.wrappedBuffer(data));
        int id = buf.readVarIntFromBuffer();
        Packet packet = Packet.generatePacket(clientbound(), id);
        if (packet == null) throw new IOException("Unknown packet id " + id);
        packet.readPacketData(buf);
        return packet;
    }

    public static int peekId(byte[] data) {
        return VarInts.peekId(data);
    }

    /** Number of bytes taken by the leading packet id varint. */
    public static int idLength(byte[] data) {
        return VarInts.idLength(data);
    }
}
