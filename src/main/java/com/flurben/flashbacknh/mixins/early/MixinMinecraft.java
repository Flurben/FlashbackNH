package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashbacknh.replay.ReplayHandler;

/**
 * Replay tick gating: ticks that do not advance the replay run with the world frozen, exactly like the singleplayer
 * pause menu freezes it, while input and GUIs keep working. Interaction with the world is disabled in replays.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Shadow
    private boolean isGamePaused;

    @Inject(method = "runTick", at = @At("HEAD"))
    private void flashbacknh$tickStart(CallbackInfo ci) {
        ReplayHandler handler = ReplayHandler.get();
        if (handler != null) {
            this.isGamePaused = !handler.onRunTickStart();
        }
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void flashbacknh$tickEnd(CallbackInfo ci) {
        ReplayHandler handler = ReplayHandler.get();
        if (handler != null) {
            handler.onRunTickEnd();
            this.isGamePaused = false;
        }
    }

    @Inject(method = "func_147116_af", at = @At("HEAD"), cancellable = true)
    private void flashbacknh$noAttack(CallbackInfo ci) {
        if (ReplayHandler.isReplaying()) ci.cancel();
    }

    @Inject(method = "func_147121_ag", at = @At("HEAD"), cancellable = true)
    private void flashbacknh$noUse(CallbackInfo ci) {
        if (ReplayHandler.isReplaying()) ci.cancel();
    }

    @Inject(method = "func_147112_ai", at = @At("HEAD"), cancellable = true)
    private void flashbacknh$noPick(CallbackInfo ci) {
        if (ReplayHandler.isReplaying()) ci.cancel();
    }

    @Inject(method = "func_147115_a", at = @At("HEAD"), cancellable = true)
    private void flashbacknh$noMining(boolean leftClick, CallbackInfo ci) {
        if (ReplayHandler.isReplaying()) ci.cancel();
    }
}
