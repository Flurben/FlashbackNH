package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.entity.EntityOtherPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityOtherPlayerMP.class)
public interface AccessorEntityOtherPlayerMP {

    @Accessor("otherPlayerMPPosRotationIncrements")
    int flashbacknh$getIncrements();

    @Accessor("otherPlayerMPPosRotationIncrements")
    void flashbacknh$setIncrements(int value);

    @Accessor("otherPlayerMPX")
    double flashbacknh$getX();

    @Accessor("otherPlayerMPY")
    double flashbacknh$getY();

    @Accessor("otherPlayerMPZ")
    double flashbacknh$getZ();

    @Accessor("otherPlayerMPYaw")
    double flashbacknh$getYaw();

    @Accessor("otherPlayerMPPitch")
    double flashbacknh$getPitch();
}
