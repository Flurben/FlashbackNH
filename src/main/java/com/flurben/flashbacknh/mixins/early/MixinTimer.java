package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Timer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashbacknh.replay.ReplayHandler;

/** Lets the replay clock decide how many ticks run per frame and the world's partial tick. */
@Mixin(Timer.class)
public class MixinTimer {

    @Inject(method = "updateTimer", at = @At("RETURN"))
    private void flashbacknh$replayClock(CallbackInfo ci) {
        ReplayHandler handler = ReplayHandler.get();
        if (handler == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || (Object) this != ((AccessorMinecraft) mc).flashbacknh$getTimer()) return;
        handler.onTimerUpdate((Timer) (Object) this);
    }
}
