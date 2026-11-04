package com.flurben.flashbacknh.compat;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;

import org.apache.commons.io.FileUtils;

import com.flurben.flashbacknh.Config;
import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.recording.RecordingManager;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * Lets replays show the Distant Horizons LODs of the world they were recorded in. Uses only DH's public API, by
 * reflection, so DH stays optional: {@code IDhApiSaveStructure} tells DH which folder a level uses, and
 * {@code IDhApiWorldProxy.setReadOnly} stops it from adding replayed chunks to the data. A replay reads a temporary
 * copy, so the player's real data is never touched.
 * <p>
 * While playing normally the override changes nothing and only notes the folder DH chose for each dimension. Recordings
 * store those folders, relative to the game directory, so no absolute paths end up in a replay file.
 */
public final class DistantHorizonsCompat {

    private static final String API = "com.seibel.distanthorizons.api.";
    private static final String CORE = "com.seibel.distanthorizons.core.";
    /** Above DH's own default, so a replay's folder wins. */
    private static final int OVERRIDE_PRIORITY = 10;
    private static final String DATABASE = "DistantHorizons.sqlite";
    private static final String WAL_SUFFIX = "-wal";
    private static final AtomicInteger nextFolder = new AtomicInteger();

    private static final Map<String, File> liveFolders = new ConcurrentHashMap<>();
    /** The current replay's copy of each recorded folder. */
    private static final Map<String, File> scratchCopies = new HashMap<>();
    private static volatile Map<String, String> replayFolders;
    private static volatile long lastLevelLoad;
    private static volatile boolean preparing;

    private static boolean available;
    private static Method dimensionName;
    private static java.lang.reflect.Field worldProxyField;
    private static Method worldLoaded;
    private static Method getReadOnly;
    private static Method setReadOnly;
    private static Method wrapLevel;
    private static Method loadLevel;
    private static Object clientApiInstance;

    private DistantHorizonsCompat() {}

    /** Binds the override if DH is installed. Safe to call without DH. */
    public static void init() {
        try {
            Class<?> dhApi = Class.forName(API + "DhApi");
            Class<?> levelWrapper = Class.forName(API + "interfaces.world.IDhApiLevelWrapper");
            Class<?> worldProxy = Class.forName(API + "interfaces.world.IDhApiWorldProxy");
            Class<?> saveStructure = Class.forName(API + "interfaces.override.levelHandling.IDhApiSaveStructure");
            Class<?> overrideable = Class.forName(API + "interfaces.override.IDhApiOverrideable");

            dimensionName = levelWrapper.getMethod("getDimensionName");
            worldProxyField = Class.forName(API + "DhApi$Delayed")
                .getField("worldProxy");
            worldLoaded = worldProxy.getMethod("worldLoaded");
            getReadOnly = worldProxy.getMethod("getReadOnly");
            setReadOnly = worldProxy.getMethod("setReadOnly", boolean.class);

            Object override = Proxy.newProxyInstance(
                saveStructure.getClassLoader(),
                new Class<?>[] { saveStructure },
                (proxy, method, args) -> handleOverrideCall(proxy, method, args));

            Object injector = dhApi.getField("overrides")
                .get(null);
            Method bind = injector.getClass()
                .getMethod("bind", Class.class, overrideable);
            bind.invoke(injector, saveStructure, override);
            available = true;
            FlashbackNH.LOG.info("Distant Horizons detected: replays use the LODs of the recorded world");
            bindLevelReload();
        } catch (ClassNotFoundException e) {
            // DH is not installed
        } catch (Throwable t) {
            FlashbackNH.LOG.warn("Unable to hook into Distant Horizons, replays will not show LODs", t);
        }
    }

    /** These two are DH internals, as its API has no way to do this. Without them only backward seeks lose LODs. */
    private static void bindLevelReload() {
        try {
            Class<?> levelWrapper = Class.forName(CORE + "wrapperInterfaces.world.IClientLevelWrapper");
            Class<?> clientApi = Class.forName(CORE + "api.internal.ClientApi");
            wrapLevel = Class.forName("com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper")
                .getMethod("getWrapper", WorldClient.class);
            clientApiInstance = clientApi.getField("INSTANCE")
                .get(null);
            loadLevel = clientApi.getMethod("clientLevelLoadEvent", levelWrapper);
        } catch (Throwable t) {
            FlashbackNH.LOG.warn("This Distant Horizons version will not show LODs after a backward seek", t);
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    /**
     * Call after a replay reconnected and caught up. DH starts a new world of its own on the join packet, after the
     * packet's new level was already loaded into the old one, which leaves it with no level to render.
     */
    public static void reloadLevel(WorldClient world) {
        if (replayFolders == null || loadLevel == null || world == null) return;
        try {
            loadLevel.invoke(clientApiInstance, wrapLevel.invoke(null, world));
        } catch (Throwable t) {
            FlashbackNH.LOG
                .warn("Unable to reload the Distant Horizons level, LODs stay hidden until the next seek", t);
        }
    }

    private static Object handleOverrideCall(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "overrideFilePath":
                return overrideFilePath((File) args[0], args[1]);
            case "getPriority":
                return OVERRIDE_PRIORITY;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            case "toString":
                return "FlashbackNH DH save structure";
            default:
                // nothing else on these interfaces needs a value of ours
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                return null;
        }
    }

    /** Called by DH on load of each level, before it opens anything in the folder. */
    private static File overrideFilePath(File current, Object level) {
        String dimension = dimensionOf(level);
        Map<String, String> replay = replayFolders;
        if (replay == null) {
            if (current != null && dimension != null) liveFolders.put(dimension, current);
            return null;
        }
        // before the level exists, so no chunk can be queued while the folder is writable
        applyReadOnly(true);
        preparing = true;
        try {
            File source = resolve(replay.get(dimension));
            if (source == null) {
                FlashbackNH.LOG.info("No Distant Horizons data for dimension {} in this replay", dimension);
                return null;
            }
            FlashbackNH.LOG.info("Replay uses Distant Horizons data of dimension {} through {}", dimension, source);
            return source;
        } finally {
            // the wait for LODs starts once DH can read them, however long a large copy took
            lastLevelLoad = System.currentTimeMillis();
            preparing = false;
        }
    }

    private static String dimensionOf(Object level) {
        try {
            return (String) dimensionName.invoke(level);
        } catch (Throwable t) {
            FlashbackNH.LOG.debug("Unable to read the DH dimension name", t);
            return null;
        }
    }

    /**
     * Gives DH a folder for one level load, or null if the recorded world's data can't be used.
     * <p>
     * DH always writes to an open database, if only to close it, so a replay never opens the player's real one: each
     * dimension is copied once per replay. And DH moves a level's folder onto itself on each load, which fails while
     * its previous connection is still open, as happens when a backward seek reloads the world. So each load also gets
     * its own folder, holding a hard link to the copy.
     */
    private static synchronized File resolve(String relative) {
        if (relative == null) return null;
        File database = new File(new File(Minecraft.getMinecraft().mcDataDir, relative), DATABASE);
        if (!database.isFile()) return null;
        try {
            File scratch = scratchCopies.get(relative);
            boolean firstLoad = scratch == null;
            if (firstLoad) {
                scratch = copyDatabase(database);
                if (scratch == null) return null;
                scratchCopies.put(relative, scratch);
            }
            File folder = newCacheFolder("load-");
            File link = new File(folder, DATABASE);
            try {
                Files.createLink(link.toPath(), scratch.toPath());
            } catch (IOException | UnsupportedOperationException e) {
                // no hard links on this file system
                Files.copy(scratch.toPath(), link.toPath());
            }
            // SQLite looks for this next to the path it opens, and only needs it once
            File wal = new File(database.getPath() + WAL_SUFFIX);
            if (firstLoad && wal.length() > 0) {
                Files.copy(wal.toPath(), new File(folder, DATABASE + WAL_SUFFIX).toPath());
            }
            return folder;
        } catch (IOException e) {
            FlashbackNH.LOG.warn("Unable to copy the Distant Horizons data, this replay will not show LODs", e);
            return null;
        }
    }

    private static File copyDatabase(File database) throws IOException {
        File folder = newCacheFolder("copy-");
        long needed = database.length() + database.length() / 10;
        if (folder.getUsableSpace() < needed) {
            FlashbackNH.LOG.warn(
                "This replay will not show LODs: copying the Distant Horizons data needs {} MB of free disk space",
                needed >> 20);
            return null;
        }
        long start = System.currentTimeMillis();
        File copy = new File(folder, DATABASE);
        Files.copy(database.toPath(), copy.toPath());
        FlashbackNH.LOG.info(
            "Copied {} MB of Distant Horizons data for this replay in {} ms",
            database.length() >> 20,
            System.currentTimeMillis() - start);
        return copy;
    }

    private static File newCacheFolder(String prefix) throws IOException {
        File folder = new File(cacheRoot(), prefix + nextFolder.incrementAndGet());
        Files.createDirectories(folder.toPath());
        return folder;
    }

    private static File cacheRoot() {
        return new File(RecordingManager.rootDir(), "cache/dh");
    }

    /**
     * The folders of the current session, for storing in a recording. Paths outside the game directory are left out.
     */
    public static Map<String, String> currentFolders() {
        if (!available || liveFolders.isEmpty()) return Collections.emptyMap();
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, File> e : liveFolders.entrySet()) {
            String relative = relativeToGameDir(e.getValue());
            if (relative != null) out.put(e.getKey(), relative);
        }
        return out;
    }

    private static String relativeToGameDir(File folder) {
        try {
            String root = Minecraft.getMinecraft().mcDataDir.getCanonicalFile()
                .toURI()
                .getPath();
            String path = folder.getCanonicalFile()
                .toURI()
                .getPath();
            return path.startsWith(root) ? path.substring(root.length()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * True for a short time after a replay level loaded. DH has no public signal for when its LODs have finished
     * loading, so exports wait a configurable time instead ({@link Config#dhLodWaitSeconds}).
     */
    public static boolean isLoadingLods() {
        if (replayFolders == null) return false;
        return preparing || System.currentTimeMillis() - lastLevelLoad < Config.dhLodWaitSeconds * 1000L;
    }

    /**
     * Call after changing the game's size. While a shader pack is active, Angelica 2.1.16 and DH stop drawing LODs
     * after
     * any resize, even in normal play, until the shaders are reloaded.
     */
    public static void reloadShadersAfterResize() {
        if (!available) return;
        try {
            Class<?> iris = Class.forName("net.coderbot.iris.Iris");
            Object config = iris.getMethod("getIrisConfig")
                .invoke(null);
            boolean enabled = (Boolean) config.getClass()
                .getMethod("areShadersEnabled")
                .invoke(config);
            if (enabled) iris.getMethod("reload")
                .invoke(null);
        } catch (ClassNotFoundException e) {
            // no Angelica shaders
        } catch (Throwable t) {
            FlashbackNH.LOG.warn("Unable to reload the shaders, this export may not show LODs", t);
        }
    }

    /** Call before a replay creates its first world. */
    public static void enterReplay(Map<String, String> folders) {
        replayFolders = folders == null ? Collections.<String, String>emptyMap() : folders;
    }

    /** Call after a replay's world is gone. */
    public static void exitReplay() {
        if (replayFolders == null) return;
        replayFolders = null;
        applyReadOnly(false);
        synchronized (DistantHorizonsCompat.class) {
            scratchCopies.clear();
            // whatever DH still holds open goes when the cache is emptied on the next game start
            FileUtils.deleteQuietly(cacheRoot());
        }
    }

    private static void applyReadOnly(boolean readOnly) {
        if (!available) return;
        try {
            Object proxy = worldProxyField.get(null);
            if (proxy != null && (Boolean) worldLoaded.invoke(proxy)) setReadOnly.invoke(proxy, readOnly);
        } catch (Throwable t) {
            FlashbackNH.LOG.debug("Unable to set the DH read-only flag", t);
        }
    }

    /** Registered only when DH is installed. */
    public static final class Events {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            // DH resets the flag with each of its worlds, which can be created after the level callback
            if (event.phase != TickEvent.Phase.START || replayFolders == null) return;
            try {
                Object proxy = worldProxyField.get(null);
                if (proxy != null && (Boolean) worldLoaded.invoke(proxy) && !(Boolean) getReadOnly.invoke(proxy)) {
                    setReadOnly.invoke(proxy, true);
                }
            } catch (Throwable t) {
                FlashbackNH.LOG.debug("Unable to check the DH read-only flag", t);
            }
        }

        @SubscribeEvent
        public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            // the next world has its own folders
            if (replayFolders == null) liveFolders.clear();
        }
    }
}
