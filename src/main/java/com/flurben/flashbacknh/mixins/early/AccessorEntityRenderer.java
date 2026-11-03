package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.renderer.EntityRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityRenderer.class)
public interface AccessorEntityRenderer {

    @Accessor("lightmapUpdateNeeded")
    void flashbacknh$setLightmapUpdateNeeded(boolean needed);

    @Accessor("fogColor1")
    void flashbacknh$setFogColor1(float value);

    @Accessor("fogColor2")
    void flashbacknh$setFogColor2(float value);
}
