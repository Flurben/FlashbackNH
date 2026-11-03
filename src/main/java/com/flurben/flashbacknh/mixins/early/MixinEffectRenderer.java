package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashbacknh.replay.ReplayHandler;

/** "Render Particles" toggle: stops new particles from spawning. */
@Mixin(EffectRenderer.class)
public class MixinEffectRenderer {

    @Inject(method = "addEffect", at = @At("HEAD"), cancellable = true)
    private void flashbacknh$particles(EntityFX effect, CallbackInfo ci) {
        if (ReplayHandler.isReplaying() && !ReplayHandler.visuals().renderParticles) ci.cancel();
    }
}
