package com.flurben.flashbacknh.mixins.early;

import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashbacknh.replay.ReplayHandler;

/**
 * Server Utilities shows server messages (backups, entering claimed chunks, restarts, ...) as notifications of its own
 * instead of in chat. In a replay they follow the chat setting of the visuals panel.
 */
@Pseudo
@Mixin(targets = "serverutils.client.NotificationHandler", remap = false)
public class MixinServerUtilitiesNotifications {

    @Inject(method = "onNotify", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void flashbacknh$hideInReplay(IChatComponent message, CallbackInfo ci) {
        if (ReplayHandler.hidesServerMessages()) ci.cancel();
    }
}
