package com.flurben.flashbacknh.mixins.early;

import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.network.Packet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityTrackerEntry.class)
public interface InvokerEntityTrackerEntry {

    /** The vanilla/FML spawn packet for the tracked entity. */
    @Invoker("func_151260_c")
    Packet flashbacknh$createSpawnPacket();
}
