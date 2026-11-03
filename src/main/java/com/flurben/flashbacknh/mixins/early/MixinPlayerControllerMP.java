package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.flurben.flashbacknh.replay.CameraEntity;
import com.flurben.flashbacknh.replay.ReplayHandler;

/** In a replay the client player is the free camera. */
@Mixin(PlayerControllerMP.class)
public class MixinPlayerControllerMP {

    @Shadow
    @Final
    private Minecraft mc;

    @Shadow
    @Final
    private NetHandlerPlayClient netClientHandler;

    @Inject(method = "func_147493_a", at = @At("HEAD"), cancellable = true)
    private void flashbacknh$createCamera(World world, StatFileWriter stats,
        CallbackInfoReturnable<EntityClientPlayerMP> cir) {
        if (ReplayHandler.isReplaying()) {
            cir.setReturnValue(new CameraEntity(mc, world, netClientHandler, stats));
        }
    }
}
