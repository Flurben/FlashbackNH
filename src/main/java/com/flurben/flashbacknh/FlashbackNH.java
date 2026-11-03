package com.flurben.flashbacknh;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = FlashbackNH.MODID,
    version = Tags.VERSION,
    name = "FlashbackNH",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*",
    guiFactory = "com.flurben.flashbacknh.gui.ConfigGuiFactory")
public class FlashbackNH {

    public static final String MODID = "flashbacknh";
    public static final Logger LOG = LogManager.getLogger("FlashbackNH");

    @SidedProxy(clientSide = "com.flurben.flashbacknh.ClientProxy", serverSide = "com.flurben.flashbacknh.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }
}
