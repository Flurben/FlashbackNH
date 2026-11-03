package com.flurben.flashbacknh.mixins.early;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Timer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface AccessorMinecraft {

    @Accessor("timer")
    Timer flashbacknh$getTimer();

    @Invoker("resize")
    void flashbacknh$resize(int width, int height);
}
