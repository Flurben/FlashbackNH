package com.flurben.flashbacknh.recording;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;

import org.apache.commons.io.FileUtils;

import com.flurben.flashbacknh.Config;
import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.format.ReplayArchive;
import com.flurben.flashbacknh.format.ReplayFormat;
import com.flurben.flashbacknh.format.ReplayMeta;
import com.flurben.flashbacknh.gui.GuiSaveReplay;
import com.flurben.flashbacknh.replay.ReplayHandler;
import com.flurben.flashbacknh.util.Notifications;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/** Owns the active recording and reacts to connection events. */
public final class RecordingManager {

    public static final RecordingManager INSTANCE = new RecordingManager();

    private volatile Recorder current;

    private RecordingManager() {}

    public static File rootDir() {
        return new File(Minecraft.getMinecraft().mcDataDir, "flashbacknh");
    }

    public static File recordingDir() {
        File f = new File(rootDir(), "recording");
        f.mkdirs();
        return f;
    }

    public static File replaysDir() {
        File f = new File(rootDir(), "replays");
        f.mkdirs();
        return f;
    }

    public Recorder getCurrent() {
        return current;
    }

    public boolean isRecording() {
        return current != null;
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        if (!Config.autoStartRecording || current != null || ReplayHandler.isReplaying()) return;
        try {
            Recorder r = new Recorder(Minecraft.getMinecraft(), event.manager, true, recordingDir());
            r.attach();
            current = r;
        } catch (Throwable t) {
            FlashbackNH.LOG.error("Unable to auto-start recording", t);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Recorder r = current;
        if (r == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        boolean disconnected = !r.getManager()
            .isChannelOpen() || (r.isStarted() && mc.theWorld == null);
        if (disconnected) {
            if (r.isStarted() && Config.autoFinishRecording) {
                finish();
            } else if (r.isStarted()) {
                finish();
            } else {
                cancel();
            }
            return;
        }
        boolean wasStarted = r.isStarted();
        r.tick();
        if (!wasStarted && r.isStarted() && Config.showRecordingToasts) {
            Notifications.show("FlashbackNH", "Started recording");
        }
    }

    // ---------------------------------------------------------------- controls

    public void start() {
        Minecraft mc = Minecraft.getMinecraft();
        if (current != null) {
            Notifications.show("Already Recording", "Cannot start a new recording while recording");
            return;
        }
        if (ReplayHandler.isReplaying() || mc.getNetHandler() == null || mc.theWorld == null) return;
        NetworkManager manager = mc.getNetHandler()
            .getNetworkManager();
        try {
            Recorder r = new Recorder(mc, manager, false, recordingDir());
            r.attach();
            current = r;
            r.tick(); // begin immediately (snapshot)
            if (Config.showRecordingToasts) Notifications.show("FlashbackNH", "Started recording");
        } catch (Throwable t) {
            FlashbackNH.LOG.error("Unable to start recording", t);
            Notifications.show("FlashbackNH", "Unable to start recording: " + t.getMessage());
        }
    }

    public void togglePause() {
        Recorder r = current;
        if (r == null) return;
        r.setPaused(!r.isPaused());
        if (Config.showRecordingToasts) {
            Notifications.show("FlashbackNH", r.isPaused() ? "Paused recording" : "Unpaused recording");
        }
    }

    public void cancel() {
        Recorder r = current;
        if (r == null) return;
        current = null;
        r.stop();
        try {
            FileUtils.deleteDirectory(r.getWorkDir());
        } catch (IOException e) {
            FlashbackNH.LOG.warn("Unable to delete {}", r.getWorkDir(), e);
        }
        if (Config.showRecordingToasts && r.isStarted()) Notifications.show("FlashbackNH", "Cancelled recording");
    }

    /** Stops the recording and either quicksaves it or asks for a name. */
    public void finish() {
        Recorder r = current;
        if (r == null) {
            Notifications.show("Not Recording", "Cannot finish recording when not recording");
            return;
        }
        current = null;
        r.stop();
        if (!r.isStarted() || r.getTicks() == 0) {
            try {
                FileUtils.deleteDirectory(r.getWorkDir());
            } catch (IOException ignored) {}
            return;
        }
        if (Config.showRecordingToasts) Notifications.show("FlashbackNH", "Finished recording");
        ReplayMeta meta = r.getMeta();
        if (Config.quicksave) {
            saveAsync(r.getWorkDir(), meta, defaultName(meta));
        } else {
            GuiSaveReplay.open(r.getWorkDir(), meta);
        }
    }

    public void addMarker(int index) {
        Recorder r = current;
        if (r == null || !r.isStarted()) {
            Notifications.show("Not Recording", "Markers can only be added while recording");
            return;
        }
        int[] colors = { 0xFFD94040, 0xFF40A0E0, 0xFF50D050, 0xFFE0C040 };
        r.addMarker(colors[Math.max(0, Math.min(3, index))], "", true);
        Notifications.show("FlashbackNH", "Added marker #" + (index + 1));
    }

    public static String defaultName(ReplayMeta meta) {
        String base = meta.worldName == null || meta.worldName.isEmpty() ? "Replay" : meta.worldName;
        return base + " " + new SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(new Date(meta.createdAt));
    }

    public static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
            .trim();
        if (s.isEmpty()) s = "Replay";
        if (s.length() > 100) s = s.substring(0, 100);
        return s;
    }

    /** Packages a finished work directory into a replay archive on a background thread. */
    public static void saveAsync(final File workDir, final ReplayMeta meta, final String name) {
        meta.name = name;
        Thread t = new Thread(() -> {
            try {
                File target = uniqueFile(replaysDir(), sanitize(name), ".zip");
                Map<String, Object> extra = new HashMap<>();
                File registry = new File(workDir, ReplayFormat.ENTRY_REGISTRY);
                if (registry.exists()) extra.put(ReplayFormat.ENTRY_REGISTRY, registry);
                File thumb = new File(workDir, ReplayFormat.ENTRY_THUMBNAIL);
                if (thumb.exists()) extra.put(ReplayFormat.ENTRY_THUMBNAIL, thumb);
                File capes = new File(workDir, ReplayFormat.ENTRY_CAPES);
                if (capes.exists()) extra.put(ReplayFormat.ENTRY_CAPES, capes);
                long start = System.nanoTime();
                ReplayArchive.write(target, meta, new File(workDir, ReplayFormat.ENTRY_STREAM), extra);
                FlashbackNH.LOG.info(
                    "Saved {} in {} ms: {} bytes",
                    target.getName(),
                    (System.nanoTime() - start) / 1_000_000L,
                    target.length());
                FileUtils.deleteDirectory(workDir);
                Notifications.show("Replay saved", target.getName());
            } catch (Throwable e) {
                FlashbackNH.LOG.error("Unable to save replay", e);
                Notifications.show("Error Saving Replay", String.valueOf(e.getMessage()), 8000);
            }
        }, "FlashbackNH replay save");
        t.start();
    }

    public static File uniqueFile(File dir, String base, String ext) {
        File f = new File(dir, base + ext);
        int i = 1;
        while (f.exists()) f = new File(dir, base + " (" + (i++) + ")" + ext);
        return f;
    }

    /** Work directories left behind by a crash. */
    public List<File> findRecoverable() {
        List<File> out = new ArrayList<>();
        File[] dirs = recordingDir().listFiles();
        if (dirs == null) return out;
        Recorder r = current;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            if (r != null && d.equals(r.getWorkDir())) continue;
            if (new File(d, ReplayFormat.ENTRY_STREAM).exists()) out.add(d);
        }
        return out;
    }
}
