package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.flurben.flashbacknh.replay.ReplayHandler;

/** Render filter: hidden entities are skipped (including their shadows and in shader shadow passes). */
@Mixin(RenderManager.class)
public class MixinRenderManager {

    @Inject(method = "func_147939_a", at = @At("HEAD"), cancellable = true)
    private void flashbacknh$hideEntity(Entity entity, double x, double y, double z, float yaw, float partialTicks,
        boolean hitboxes, CallbackInfoReturnable<Boolean> cir) {
        ReplayHandler handler = ReplayHandler.get();
        if (handler != null && handler.isHidden(entity)) cir.setReturnValue(false);
    }
}
