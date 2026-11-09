package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.flurben.flashbacknh.replay.ReplayHandler;

/**
 * Gives players in a replay the cape they wore while recording. Mods that add capes change these two methods as well,
 * so this applies late and has the last word, but only for players that had a recorded cape.
 */
@Mixin(value = AbstractClientPlayer.class, priority = 2000)
public abstract class MixinAbstractClientPlayer {

    @Inject(method = "getLocationCape", at = @At("RETURN"), cancellable = true)
    private void flashbacknh$recordedCape(CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation cape = ReplayHandler.recordedCape((AbstractClientPlayer) (Object) this);
        if (cape != null) cir.setReturnValue(cape);
    }

    @Inject(method = "func_152122_n", at = @At("RETURN"), cancellable = true)
    private void flashbacknh$hasRecordedCape(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && ReplayHandler.recordedCape((AbstractClientPlayer) (Object) this) != null) {
            cir.setReturnValue(true);
        }
    }
}
