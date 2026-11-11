package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.network.play.server.S40PacketDisconnect;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashbacknh.recording.ServerTileData;
import com.flurben.flashbacknh.replay.ReplayHandler;

/**
 * Tile entity data packets are noted for world snapshots. A replay has no server to be disconnected from. Mods call
 * this themselves when data does not fit what the client
 * has, Forge Multipart for one, which would end the replay; the replay goes on instead.
 */
@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClient {

    @Inject(
        method = "handleDisconnect(Lnet/minecraft/network/play/server/S40PacketDisconnect;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void flashbacknh$ignoreDisconnect(S40PacketDisconnect packet, CallbackInfo ci) {
        if (ReplayHandler.ignoresDisconnect((NetHandlerPlayClient) (Object) this, packet)) ci.cancel();
    }

    /** Keeps what a world snapshot needs from tile entity packets, see {@link ServerTileData}. */
    @Inject(
        method = "handleUpdateTileEntity(Lnet/minecraft/network/play/server/S35PacketUpdateTileEntity;)V",
        at = @At("HEAD"))
    private void flashbacknh$rememberTileData(S35PacketUpdateTileEntity packet, CallbackInfo ci) {
        ServerTileData.received(packet);
    }
}
