package com.flurben.flashbacknh.client;

import net.minecraft.client.settings.KeyBinding;

import cpw.mods.fml.client.registry.ClientRegistry;

/** Recording keybinds. All unbound by default to avoid clashing with GTNH's many keybinds. */
public final class KeyBindings {

    public static final String CATEGORY = "key.categories.flashbacknh";

    public static KeyBinding toggleRecording;
    public static KeyBinding pauseRecording;
    public static KeyBinding openReplays;
    public static final KeyBinding[] markers = new KeyBinding[4];

    private KeyBindings() {}

    public static void register() {
        toggleRecording = new KeyBinding("key.flashbacknh.toggle_recording", 0, CATEGORY);
        pauseRecording = new KeyBinding("key.flashbacknh.pause_recording", 0, CATEGORY);
        openReplays = new KeyBinding("key.flashbacknh.open_replays", 0, CATEGORY);
        ClientRegistry.registerKeyBinding(toggleRecording);
        ClientRegistry.registerKeyBinding(pauseRecording);
        ClientRegistry.registerKeyBinding(openReplays);
        for (int i = 0; i < markers.length; i++) {
            markers[i] = new KeyBinding("key.flashbacknh.marker_" + (i + 1), 0, CATEGORY);
            ClientRegistry.registerKeyBinding(markers[i]);
        }
    }
}
