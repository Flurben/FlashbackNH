package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.scoreboard.ScoreObjective;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashbacknh.replay.ReplayHandler;

/** The scoreboard sidebar has no overlay event in 1.7.10, so the "Scoreboard" visual toggle hooks it directly. */
@Mixin(GuiIngame.class)
public class MixinGuiIngame {

    @Inject(method = "func_96136_a", at = @At("HEAD"), cancellable = true)
    private void flashbacknh$scoreboard(ScoreObjective objective, int height, int width, FontRenderer font,
        CallbackInfo ci) {
        if (ReplayHandler.isReplaying() && !ReplayHandler.visuals().showScoreboard) ci.cancel();
    }
}
