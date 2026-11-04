package com.flurben.flashbacknh.recording;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

import com.flurben.flashbacknh.Config;
import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.Tags;
import com.flurben.flashbacknh.format.RecordWriter;
import com.flurben.flashbacknh.format.RegistrySnapshot;
import com.flurben.flashbacknh.format.ReplayFormat;
import com.flurben.flashbacknh.format.ReplayMeta;
import com.flurben.flashbacknh.mixins.early.AccessorNetworkManager;
import com.flurben.flashbacknh.net.PacketCodec;
import com.flurben.flashbacknh.util.Notifications;
import com.flurben.flashbacknh.util.ServerTasks;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** A single in-progress recording. */
public final class Recorder {

    public static final String WORK_STREAM = ReplayFormat.ENTRY_STREAM;
    public static final String WORK_META = ReplayFormat.ENTRY_META;
    public static final String WORK_REGISTRY = ReplayFormat.ENTRY_REGISTRY;
    public static final String WORK_THUMB = ReplayFormat.ENTRY_THUMBNAIL;

    private final Minecraft mc;
    private final NetworkManager manager;
    private final boolean fromJoin;
    private final File workDir;
    private final RecordWriter writer;
    private final ReplayMeta meta = new ReplayMeta();
    private final LocalPlayerRecorder localPlayer;
    private final Object lock = new Object();

    private final List<byte[]> preStartBuffer = new ArrayList<>();
    private final List<Packet> queuedAtAttach = new ArrayList<>();
    private CaptureHandler handler;
    private volatile boolean started;
    private volatile boolean paused;
    private boolean finished;
    private long lastFlush;
    private long lastMetaSave;
    private int pendingThumbnailTicks = 100; // after the "started recording" notification is gone
    private int failures;

    public Recorder(Minecraft mc, NetworkManager manager, boolean fromJoin, File recordingsRoot) throws IOException {
        this.mc = mc;
        this.manager = manager;
        this.fromJoin = fromJoin;
        this.workDir = new File(
            recordingsRoot,
            UUID.randomUUID()
                .toString());
        if (!workDir.mkdirs()) throw new IOException("Unable to create " + workDir);
        this.writer = new RecordWriter(new File(workDir, WORK_STREAM), false);
        GameProfile profile = mc.getSession()
            .func_148256_e();
        this.localPlayer = new LocalPlayerRecorder(this, profile);
        fetchProfileProperties(profile);

        meta.modVersion = Tags.VERSION;
        meta.recordedBy = profile.getName();
        meta.recordedByUuid = profile.getId() == null ? ""
            : profile.getId()
                .toString();
        meta.singleplayer = manager.isLocalChannel();
        if (mc.isIntegratedServerRunning() && mc.getIntegratedServer() != null) {
            meta.worldName = mc.getIntegratedServer()
                .getWorldName();
        } else if (mc.func_147104_D() != null) {
            meta.serverAddress = mc.func_147104_D().serverIP;
            meta.worldName = mc.func_147104_D().serverName;
        }
        meta.name = (meta.worldName == null || meta.worldName.isEmpty() ? "Replay" : meta.worldName);
        for (ModContainer mod : Loader.instance()
            .getActiveModList()) {
            meta.mods.add(mod.getModId() + "@" + mod.getVersion());
        }
    }

    private void fetchProfileProperties(final GameProfile profile) {
        if (profile.getId() == null) return;
        Thread t = new Thread(() -> {
            try {
                GameProfile filled = mc.func_152347_ac()
                    .fillProfileProperties(new GameProfile(profile.getId(), profile.getName()), true);
                if (filled != null && !filled.getProperties()
                    .isEmpty()) {
                    localPlayer.setProfile(filled);
                }
            } catch (Throwable e) {
                FlashbackNH.LOG.debug("Unable to fetch skin properties", e);
            }
        }, "FlashbackNH profile fetch");
        t.setDaemon(true);
        t.start();
    }

    public File getWorkDir() {
        return workDir;
    }

    public ReplayMeta getMeta() {
        return meta;
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isPaused() {
        return paused;
    }

    public int getTicks() {
        return writer.getTicks();
    }

    public NetworkManager getManager() {
        return manager;
    }

    /** Installs the capture handler. Atomic with respect to the network thread. */
    public void attach() throws Exception {
        final AccessorNetworkManager acc = (AccessorNetworkManager) manager;
        Callable<Void> task = () -> {
            handler = CaptureHandler.create(Recorder.this, manager);
            handler.install(
                manager.channel()
                    .pipeline());
            synchronized (lock) {
                queuedAtAttach.clear();
                for (Object o : acc.flashbacknh$getReceivedPacketsQueue()) {
                    if (o instanceof Packet) queuedAtAttach.add((Packet) o);
                }
            }
            return null;
        };
        if (manager.channel()
            .eventLoop()
            .inEventLoop()) {
            task.call();
        } else {
            Future<Void> f = manager.channel()
                .eventLoop()
                .submit(task);
            f.get(10, TimeUnit.SECONDS);
        }
    }

    /** Called from the network thread for every clientbound packet. */
    void capture(byte[] data) {
        synchronized (lock) {
            if (finished) return;
            if (!started) {
                preStartBuffer.add(data);
                return;
            }
            if (paused) return;
            try {
                writer.writeRecord(ReplayFormat.REC_PACKET, data);
            } catch (IOException e) {
                if (failures++ < 5) FlashbackNH.LOG.error("Failed to write packet", e);
            }
        }
    }

    void writeRaw(byte type, byte[] data) throws IOException {
        synchronized (lock) {
            if (finished) return;
            writer.writeRecord(type, data);
        }
    }

    /** Starts writing. Called on the client thread once the player is in a world. */
    private void begin() throws IOException {
        writeRegistry();
        synchronized (lock) {
            int joinIndex = -1;
            if (fromJoin) {
                for (int i = 0; i < preStartBuffer.size(); i++) {
                    if (PacketCodec.peekId(preStartBuffer.get(i)) == 0x01) {
                        joinIndex = i;
                        break;
                    }
                }
            }
            List<int[]> missingDescriptions = null;
            if (joinIndex >= 0) {
                // We saw the whole login: the stream itself is an exact record of the world
                for (int i = joinIndex; i < preStartBuffer.size(); i++) {
                    writer.writeRecord(ReplayFormat.REC_PACKET, preStartBuffer.get(i));
                }
            } else {
                WorldSnapshot snapshot = WorldSnapshot.create(mc, true, null);
                for (WorldSnapshot.Rec rec : snapshot.records) writer.writeRecord(rec.type, rec.data);
                missingDescriptions = snapshot.tilesWithoutDescription;
                for (Packet p : queuedAtAttach) {
                    try {
                        byte[] data = PacketCodec.encode(p);
                        if (data != null) writer.writeRecord(ReplayFormat.REC_PACKET, data);
                    } catch (Throwable t) {
                        FlashbackNH.LOG.debug("Unable to record queued packet {}", p, t);
                    }
                }
                for (byte[] data : preStartBuffer) writer.writeRecord(ReplayFormat.REC_PACKET, data);
            }
            preStartBuffer.clear();
            queuedAtAttach.clear();
            started = true;
            localPlayer.forceRespawn();
            if (missingDescriptions != null && !missingDescriptions.isEmpty()) {
                requestServerDescriptions(missingDescriptions);
            }
        }
        saveMeta();
        FlashbackNH.LOG.info("Recording started ({})", fromJoin ? "from join" : "snapshot");
    }

    /**
     * In singleplayer, ask the integrated server for the description packets of tile entities that the client could
     * not describe itself. Mods that sync tile data with their own packets react to this the same way as to a player
     * starting to watch a chunk.
     */
    private void requestServerDescriptions(final List<int[]> positions) {
        final IntegratedServer server = mc.getIntegratedServer();
        if (server == null || !manager.isLocalChannel() || mc.thePlayer == null) return;
        final int dim = mc.thePlayer.dimension;
        ServerTasks.schedule(() -> {
            WorldServer ws = server.worldServerForDimension(dim);
            if (ws == null) return;
            for (int[] pos : positions) {
                try {
                    TileEntity te = ws.getTileEntity(pos[0], pos[1], pos[2]);
                    if (te == null) continue;
                    Packet p = te.getDescriptionPacket();
                    if (p != null) {
                        byte[] data = PacketCodec.encode(p);
                        if (data != null) capture(data);
                    }
                } catch (Throwable t) {
                    FlashbackNH.LOG.debug("Server description failed at {},{},{}", pos[0], pos[1], pos[2], t);
                }
            }
        });
    }

    private void writeRegistry() {
        try {
            byte[] data = RegistrySnapshot.capture()
                .toBytes();
            try (FileOutputStream out = new FileOutputStream(new File(workDir, WORK_REGISTRY))) {
                out.write(data);
            }
        } catch (Throwable t) {
            FlashbackNH.LOG.error("Unable to save id map", t);
        }
    }

    /** Client tick (end). */
    public void tick() {
        if (finished) return;
        if (!started) {
            if (mc.theWorld != null && mc.thePlayer != null) {
                try {
                    begin();
                } catch (Throwable e) {
                    FlashbackNH.LOG.error("Unable to start recording", e);
                    Notifications.show("FlashbackNH", "Unable to start recording: " + e);
                    RecordingManager.INSTANCE.cancel();
                }
            }
            return;
        }
        if (paused || mc.isGamePaused() || mc.theWorld == null) return;
        try {
            localPlayer.tick(mc);
            synchronized (lock) {
                writer.writeTick();
            }
        } catch (Throwable e) {
            if (failures++ < 5) FlashbackNH.LOG.error("Recording tick failed", e);
        }
        long now = System.currentTimeMillis();
        if (now - lastFlush > 1000) {
            lastFlush = now;
            try {
                writer.flush();
            } catch (IOException ignored) {}
        }
        if (now - lastMetaSave > 10000) {
            saveMeta();
        }
        if (pendingThumbnailTicks > 0) pendingThumbnailTicks--;
    }

    public boolean wantsThumbnail() {
        return started && pendingThumbnailTicks == 0 && !new File(workDir, WORK_THUMB).exists();
    }

    public void saveThumbnail(byte[] png) {
        try (FileOutputStream out = new FileOutputStream(new File(workDir, WORK_THUMB))) {
            out.write(png);
        } catch (IOException e) {
            FlashbackNH.LOG.debug("Unable to save thumbnail", e);
        }
        pendingThumbnailTicks = -1;
    }

    void onDimensionChanged(int newDim) {
        if (Config.markDimensionChanges) {
            ReplayMeta.Marker m = new ReplayMeta.Marker(getTicks(), 0xFF8040FF, "Dimension " + newDim);
            synchronized (meta) {
                meta.markers.add(m);
            }
        }
    }

    public void addMarker(int color, String description, boolean withPosition) {
        ReplayMeta.Marker m = new ReplayMeta.Marker(getTicks(), color, description);
        if (withPosition && mc.thePlayer != null) {
            m.hasPosition = true;
            m.x = mc.thePlayer.posX;
            m.y = mc.thePlayer.posY;
            m.z = mc.thePlayer.posZ;
            m.dimension = mc.thePlayer.dimension;
        }
        synchronized (meta) {
            meta.markers.add(m);
        }
    }

    public void setPaused(boolean pause) {
        if (pause == paused || !started) return;
        if (pause) {
            paused = true;
            return;
        }
        synchronized (lock) {
            try {
                if (mc.theWorld != null && mc.thePlayer != null) {
                    writer.writeRecord(ReplayFormat.REC_CLEAR_WORLD, new byte[0]);
                    int lastDim = localPlayer.getLastDimension();
                    WorldSnapshot snapshot = WorldSnapshot
                        .create(mc, false, lastDim == Integer.MIN_VALUE ? null : lastDim);
                    for (WorldSnapshot.Rec rec : snapshot.records) writer.writeRecord(rec.type, rec.data);
                    if (!snapshot.tilesWithoutDescription.isEmpty()) {
                        requestServerDescriptions(snapshot.tilesWithoutDescription);
                    }
                }
            } catch (IOException e) {
                FlashbackNH.LOG.error("Failed to write resume snapshot", e);
            }
            localPlayer.forceRespawn();
            paused = false;
        }
    }

    public void saveMeta() {
        lastMetaSave = System.currentTimeMillis();
        String json;
        synchronized (meta) {
            meta.durationTicks = writer.getTicks();
            meta.dhFolders.putAll(com.flurben.flashbacknh.compat.DistantHorizonsCompat.currentFolders());
            json = meta.toJson();
        }
        try (FileOutputStream out = new FileOutputStream(new File(workDir, WORK_META))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            FlashbackNH.LOG.debug("Unable to save metadata", e);
        }
    }

    /** Stops recording and closes files. The work directory is left for packaging/recovery. */
    public void stop() {
        synchronized (lock) {
            if (finished) return;
            finished = true;
        }
        try {
            if (manager.channel() != null && manager.channel()
                .isOpen()) {
                manager.channel()
                    .eventLoop()
                    .execute(
                        () -> CaptureHandler.uninstall(
                            manager.channel()
                                .pipeline()));
            }
        } catch (Throwable ignored) {}
        try {
            writer.close();
        } catch (IOException e) {
            FlashbackNH.LOG.error("Failed to close recording", e);
        }
        saveMeta();
    }

    public static int readInt(byte[] data) {
        try {
            return new DataInputStream(new ByteArrayInputStream(data)).readInt();
        } catch (IOException e) {
            return 0;
        }
    }
}
