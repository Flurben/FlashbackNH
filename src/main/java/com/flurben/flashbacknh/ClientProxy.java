package com.flurben.flashbacknh;

import java.io.File;

import net.minecraftforge.common.MinecraftForge;

import org.apache.commons.io.FileUtils;

import com.flurben.flashbacknh.client.ClientEvents;
import com.flurben.flashbacknh.client.KeyBindings;
import com.flurben.flashbacknh.compat.DistantHorizonsCompat;
import com.flurben.flashbacknh.recording.RecordingManager;
import com.flurben.flashbacknh.util.ServerTasks;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        Config.load(event.getSuggestedConfigurationFile());
        // leftovers from a previous session's playback
        FileUtils.deleteQuietly(new File(RecordingManager.rootDir(), "cache"));
    }

    @Override
    public void init(FMLInitializationEvent event) {
        KeyBindings.register();
        ClientEvents events = new ClientEvents();
        MinecraftForge.EVENT_BUS.register(events);
        FMLCommonHandler.instance()
            .bus()
            .register(events);
        FMLCommonHandler.instance()
            .bus()
            .register(RecordingManager.INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(new ServerTasks());
        DistantHorizonsCompat.init();
        if (DistantHorizonsCompat.isAvailable()) {
            FMLCommonHandler.instance()
                .bus()
                .register(new DistantHorizonsCompat.Events());
        }
        if (com.flurben.flashbacknh.dev.DevDriver.enabled()) {
            com.flurben.flashbacknh.dev.DevDriver driver = new com.flurben.flashbacknh.dev.DevDriver();
            FMLCommonHandler.instance()
                .bus()
                .register(driver);
            MinecraftForge.EVENT_BUS.register(driver);
        }
        FlashbackNH.LOG.info("FlashbackNH {} loaded", Tags.VERSION);
    }
}
