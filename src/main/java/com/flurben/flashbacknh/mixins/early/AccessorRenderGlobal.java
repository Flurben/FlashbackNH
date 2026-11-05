package com.flurben.flashbacknh.mixins.early;

import java.util.List;
import java.util.Map;

import net.minecraft.client.renderer.RenderGlobal;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderGlobal.class)
public interface AccessorRenderGlobal {

    @Accessor("worldRenderersToUpdate")
    List<?> flashbacknh$getWorldRenderersToUpdate();

    @Accessor("damagedBlocks")
    Map<?, ?> flashbacknh$getDamagedBlocks();
}
