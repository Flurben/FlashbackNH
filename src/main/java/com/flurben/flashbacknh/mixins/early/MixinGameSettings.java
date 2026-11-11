package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.settings.GameSettings;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashbacknh.export.Exporter;

/** An export turns vsync and the frame limit off for itself; options.txt keeps the user's values. */
@Mixin(GameSettings.class)
public class MixinGameSettings {

    @Inject(method = "saveOptions", at = @At("HEAD"))
    private void flashbacknh$userValues(CallbackInfo ci) {
        Exporter.beforeOptionsSave();
    }

    @Inject(method = "saveOptions", at = @At("RETURN"))
    private void flashbacknh$exportValues(CallbackInfo ci) {
        Exporter.afterOptionsSave();
    }
}
