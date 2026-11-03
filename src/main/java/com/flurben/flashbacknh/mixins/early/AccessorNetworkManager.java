package com.flurben.flashbacknh.mixins.early;

import java.util.Queue;

import net.minecraft.network.NetworkManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NetworkManager.class)
public interface AccessorNetworkManager {

    @Accessor("receivedPacketsQueue")
    Queue<?> flashbacknh$getReceivedPacketsQueue();
}
