package com.flurben.flashbacknh.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;

import com.flurben.flashbacknh.Config;
import com.flurben.flashbacknh.FlashbackNH;

import cpw.mods.fml.client.IModGuiFactory;
import cpw.mods.fml.client.config.GuiConfig;
import cpw.mods.fml.client.config.IConfigElement;

/** Forge mod-list "Config" button. */
public class ConfigGuiFactory implements IModGuiFactory {

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static List<IConfigElement> elements() {
        List<IConfigElement> list = new ArrayList<>();
        if (Config.config == null) return list;
        for (String cat : new String[] { Config.CAT_RECORDING, Config.CAT_REPLAY, Config.CAT_EXPORT,
            Config.CAT_ADVANCED }) {
            list.add(new ConfigElement(Config.config.getCategory(cat)));
        }
        return list;
    }

    public static class ConfigScreen extends GuiConfig {

        public ConfigScreen(GuiScreen parent) {
            super(parent, elements(), FlashbackNH.MODID, false, false, "FlashbackNH");
        }
    }

    @Override
    public void initialize(Minecraft minecraftInstance) {}

    @Override
    public Class<? extends GuiScreen> mainConfigGuiClass() {
        return ConfigScreen.class;
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return null;
    }

    @Override
    public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) {
        return null;
    }
}
