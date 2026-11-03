package com.flurben.flashbacknh.mixins.early;

import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityLivingBase.class)
public interface AccessorEntityLivingBase {

    @Accessor("newPosRotationIncrements")
    int flashbacknh$getNewPosRotationIncrements();

    @Accessor("newPosRotationIncrements")
    void flashbacknh$setNewPosRotationIncrements(int value);

    @Accessor("newPosX")
    double flashbacknh$getNewPosX();

    @Accessor("newPosY")
    double flashbacknh$getNewPosY();

    @Accessor("newPosZ")
    double flashbacknh$getNewPosZ();

    @Accessor("newRotationYaw")
    double flashbacknh$getNewRotationYaw();

    @Accessor("newRotationPitch")
    double flashbacknh$getNewRotationPitch();
}
