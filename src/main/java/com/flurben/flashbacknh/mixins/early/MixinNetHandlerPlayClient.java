package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S40PacketDisconnect;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashbacknh.replay.ReplayHandler;

/**
 * A replay has no server to be disconnected from. Mods call this themselves when data does not fit what the client
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
}
