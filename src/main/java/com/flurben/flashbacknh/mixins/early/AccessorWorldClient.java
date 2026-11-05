package com.flurben.flashbacknh.mixins.early;

import java.util.Set;

import net.minecraft.client.multiplayer.WorldClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldClient.class)
public interface AccessorWorldClient {

    @Accessor("entityList")
    Set<?> flashbacknh$getEntityList();

    @Accessor("entitySpawnQueue")
    Set<?> flashbacknh$getEntitySpawnQueue();
}
