package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.renderer.EntityRenderer;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.flurben.flashbacknh.replay.ReplayHandler;

/**
 * Camera FOV, roll and shake for replays. Both are applied through the regular camera setup, so shader packs
 * (Angelica/Iris)
 * see exactly the same projection and view matrices as the vanilla renderer.
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {

    @Inject(method = "getFOVModifier", at = @At("RETURN"), cancellable = true)
    private void flashbacknh$fov(float partialTicks, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
        if (!useFovSetting) return;
        ReplayHandler handler = ReplayHandler.get();
        if (handler == null) return;
        float fov = handler.getFovOverride();
        if (!Float.isNaN(fov)) cir.setReturnValue(Math.max(1.0f, Math.min(179.0f, fov)));
    }

    @Inject(method = "orientCamera", at = @At("HEAD"))
    private void flashbacknh$roll(float partialTicks, CallbackInfo ci) {
        ReplayHandler handler = ReplayHandler.get();
        if (handler == null || handler.isSpectating()) return;
        float roll = handler.getRoll();
        if (roll != 0.0f) GL11.glRotatef(roll, 0.0f, 0.0f, 1.0f);
        float shakePitch = handler.getShakePitch(), shakeYaw = handler.getShakeYaw();
        if (shakePitch != 0.0f) GL11.glRotatef(shakePitch, 1.0f, 0.0f, 0.0f);
        if (shakeYaw != 0.0f) GL11.glRotatef(shakeYaw, 0.0f, 1.0f, 0.0f);
    }
}
