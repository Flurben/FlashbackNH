package com.flurben.flashbacknh.replay.net;

import java.io.IOException;
import java.util.Queue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import com.flurben.flashbacknh.mixins.early.AccessorNetworkManager;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/**
 * An in-memory connection that looks like a server connection to the rest of the game. Recorded packets are fired
 * into its pipeline and reach the normal {@link NetHandlerPlayClient}; everything the client tries to send is dropped.
 */
public final class ReplayConnection {

    public final NetworkManager manager;
    public final EmbeddedChannel channel;
    public final NetHandlerPlayClient handler;
    public final ReplayDispatcher dispatcher;

    public ReplayConnection(Minecraft mc) {
        this.manager = new NetworkManager(true);
        this.channel = new EmbeddedChannel(new DropOutbound(), new ModPacketRouter(this), manager);
        if (manager.channel() == null) {
            channel.pipeline()
                .fireChannelActive();
        }
        // setConnectionState remembers the previous state and processReceivedPackets reports a difference to the net
        // handler, which NetHandlerPlayClient rejects. Setting it twice leaves no pending transition.
        manager.setConnectionState(EnumConnectionState.PLAY);
        manager.setConnectionState(EnumConnectionState.PLAY);
        this.dispatcher = new ReplayDispatcher(manager);
        channel.attr(NetworkDispatcher.FML_DISPATCHER)
            .set(dispatcher);
        channel.attr(NetworkDispatcher.IS_LOCAL)
            .set(false);
        this.handler = new NetHandlerPlayClient(mc, null, manager);
        manager.setNetHandler(handler);
        FMLClientHandler.instance()
            .setPlayClient(handler);
    }

    /** Queues a packet for processing on the next client tick (or {@link #processAll()}). */
    public void deliver(Packet packet) {
        channel.pipeline()
            .fireChannelRead(packet);
    }

    @SuppressWarnings("unchecked")
    public Queue<Packet> queue() {
        return (Queue<Packet>) (Queue<?>) ((AccessorNetworkManager) manager).flashbacknh$getReceivedPacketsQueue();
    }

    /** Processes every queued packet immediately. */
    public void processAll() {
        int guard = 0;
        while (!queue().isEmpty() && guard++ < 1_000_000) {
            manager.processReceivedPackets();
        }
    }

    public void close() {
        try {
            channel.close();
        } catch (Throwable ignored) {}
    }

    /** Swallows every outbound write so nothing piles up in the embedded channel. */
    private static final class DropOutbound extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {}
    }

    /** Routes custom payloads of registered mod channels to FML, like the real network dispatcher does. */
    private static final class ModPacketRouter extends ChannelInboundHandlerAdapter {

        private final ReplayConnection connection;

        ModPacketRouter(ReplayConnection connection) {
            this.connection = connection;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof S3FPacketCustomPayload) {
                S3FPacketCustomPayload payload = (S3FPacketCustomPayload) msg;
                String channelName = payload.func_149169_c();
                if ("FML|HS".equals(channelName) || "REGISTER".equals(channelName)
                    || "UNREGISTER".equals(channelName)) {
                    return; // handshake traffic has no meaning in a replay
                }
                if (NetworkRegistry.INSTANCE.hasChannel(channelName, Side.CLIENT)) {
                    FMLProxyPacket proxy = new FMLProxyPacket(payload);
                    proxy.setDispatcher(connection.dispatcher);
                    proxy.setTarget(Side.CLIENT);
                    ctx.fireChannelRead(new SafePacket(proxy));
                    return;
                }
            }
            ctx.fireChannelRead(msg instanceof Packet ? new SafePacket((Packet) msg) : msg);
        }
    }

    /** A replay-internal record processed in order with the recorded packets. */
    public static final class ActionPacket extends Packet {

        private final Runnable action;

        public ActionPacket(Runnable action) {
            this.action = action;
        }

        @Override
        public void readPacketData(PacketBuffer data) throws IOException {}

        @Override
        public void writePacketData(PacketBuffer data) throws IOException {}

        @Override
        public void processPacket(INetHandler handler) {
            action.run();
        }
    }
}
