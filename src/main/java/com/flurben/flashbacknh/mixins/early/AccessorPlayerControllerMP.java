package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.multiplayer.PlayerControllerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerControllerMP.class)
public interface AccessorPlayerControllerMP {

    @Accessor("isHittingBlock")
    boolean flashbacknh$isHittingBlock();

    @Accessor("curBlockDamageMP")
    float flashbacknh$getCurBlockDamage();

    @Accessor("currentBlockX")
    int flashbacknh$getCurrentBlockX();

    @Accessor("currentBlockY")
    int flashbacknh$getCurrentBlockY();

    @Accessor("currentblockZ")
    int flashbacknh$getCurrentBlockZ();
}
