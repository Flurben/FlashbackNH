package com.flurben.flashbacknh.recording;

import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.net.PacketCodec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

/**
 * Netty handlers that tee every clientbound packet into the active {@link Recorder}.
 *
 * <p>
 * On a remote connection the handler sits right after the frame splitter and copies the exact wire bytes, which is
 * lossless for every packet including mod packets. On the integrated server (memory channel) packets are passed as
 * objects, so they are serialized with their own {@code writePacketData}, exactly like the integrated server would do
 * for a LAN client.
 */
public abstract class CaptureHandler extends ChannelInboundHandlerAdapter {

    public static final String NAME = "flashbacknh_capture";

    protected final Recorder recorder;

    protected CaptureHandler(Recorder recorder) {
        this.recorder = recorder;
    }

    public static CaptureHandler create(Recorder recorder, NetworkManager manager) {
        return manager.isLocalChannel() ? new ObjectCapture(recorder) : new RawCapture(recorder);
    }

    /** Inserts the handler into the pipeline. Must run on the channel's event loop. */
    public void install(ChannelPipeline pipeline) {
        if (pipeline.get(NAME) != null) pipeline.remove(NAME);
        if (this instanceof RawCapture && pipeline.get("splitter") != null) {
            pipeline.addAfter("splitter", NAME, this);
        } else if (pipeline.get("fml:packet_handler") != null) {
            pipeline.addBefore("fml:packet_handler", NAME, this);
        } else {
            pipeline.addBefore("packet_handler", NAME, this);
        }
    }

    public static void uninstall(ChannelPipeline pipeline) {
        try {
            if (pipeline.get(NAME) != null) pipeline.remove(NAME);
        } catch (Exception ignored) {}
    }

    /** Packets that are never useful in a replay. */
    static boolean isIgnoredId(int id) {
        return id == 0x00 // keep alive
            || id == 0x37 // statistics
            || id == 0x3A; // tab complete
    }

    static final class RawCapture extends CaptureHandler {

        RawCapture(Recorder recorder) {
            super(recorder);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                if (msg instanceof ByteBuf && ctx.channel()
                    .attr(NetworkManager.attrKeyConnectionState)
                    .get() == EnumConnectionState.PLAY) {
                    ByteBuf buf = (ByteBuf) msg;
                    int len = buf.readableBytes();
                    if (len > 0) {
                        byte[] data = new byte[len];
                        buf.getBytes(buf.readerIndex(), data);
                        if (!isIgnoredId(PacketCodec.peekId(data))) recorder.capture(data);
                    }
                }
            } catch (Throwable t) {
                FlashbackNH.LOG.error("Failed to capture packet", t);
            }
            ctx.fireChannelRead(msg);
        }
    }

    static final class ObjectCapture extends CaptureHandler {

        ObjectCapture(Recorder recorder) {
            super(recorder);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Packet) {
                try {
                    byte[] data = PacketCodec.encode((Packet) msg);
                    if (data != null && !isIgnoredId(PacketCodec.peekId(data))) recorder.capture(data);
                } catch (Throwable t) {
                    FlashbackNH.LOG.error(
                        "Failed to capture packet {}",
                        msg.getClass()
                            .getName(),
                        t);
                }
            }
            ctx.fireChannelRead(msg);
        }
    }
}
