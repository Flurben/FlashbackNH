package com.flurben.flashbacknh.mixins.early;

import java.util.Map;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NetHandlerPlayClient.class)
public interface AccessorNetHandlerPlayClient {

    @Accessor("clientWorldController")
    void flashbacknh$setClientWorld(WorldClient world);

    @Accessor("playerInfoMap")
    Map<?, ?> flashbacknh$getPlayerInfoMap();
}
