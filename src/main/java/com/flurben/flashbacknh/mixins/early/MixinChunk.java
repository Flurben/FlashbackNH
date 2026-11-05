package com.flurben.flashbacknh.mixins.early;

import net.minecraft.block.Block;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.flurben.flashbacknh.replay.ReplayHandler;

/**
 * Tells a replay which chunks really change. A server resends blocks all the time without them being different, and
 * blocks also change through mods and on the client alone, so the recorded packets cannot say this.
 */
@Mixin(Chunk.class)
public class MixinChunk {

    @Shadow
    @Final
    public int xPosition;

    @Shadow
    @Final
    public int zPosition;

    @Inject(method = "func_150807_a", at = @At("RETURN"))
    private void flashbacknh$blockSet(int x, int y, int z, Block block, int meta, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) ReplayHandler.onChunkChanged(xPosition, zPosition);
    }

    @Inject(method = "setBlockMetadata", at = @At("RETURN"))
    private void flashbacknh$metaSet(int x, int y, int z, int meta, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) ReplayHandler.onChunkChanged(xPosition, zPosition);
    }
}
