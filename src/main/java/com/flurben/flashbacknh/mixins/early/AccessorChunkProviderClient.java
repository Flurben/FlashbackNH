package com.flurben.flashbacknh.mixins.early;

import java.util.List;

import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkProviderClient.class)
public interface AccessorChunkProviderClient {

    @Accessor("chunkListing")
    List<Chunk> flashbacknh$getChunkListing();
}
