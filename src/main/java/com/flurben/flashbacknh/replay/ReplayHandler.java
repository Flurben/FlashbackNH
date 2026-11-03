package com.flurben.flashbacknh.replay;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.Packet;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Timer;
import net.minecraft.world.WorldSettings;

import org.apache.commons.io.FileUtils;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.editor.EditorState;
import com.flurben.flashbacknh.editor.KeyframeTrack;
import com.flurben.flashbacknh.editor.TrackType;
import com.flurben.flashbacknh.editor.Visuals;
import com.flurben.flashbacknh.format.RegistrySnapshot;
import com.flurben.flashbacknh.format.ReplayArchive;
import com.flurben.flashbacknh.format.ReplayFormat;
import com.flurben.flashbacknh.format.ReplayMeta;
import com.flurben.flashbacknh.format.ReplayStream;
import com.flurben.flashbacknh.gui.GuiReplayEditor;
import com.flurben.flashbacknh.gui.GuiReplaySelect;
import com.flurben.flashbacknh.mixins.early.AccessorEntityLivingBase;
import com.flurben.flashbacknh.mixins.early.AccessorEntityOtherPlayerMP;
import com.flurben.flashbacknh.mixins.early.AccessorEntityRenderer;
import com.flurben.flashbacknh.mixins.early.AccessorNetHandlerPlayClient;
import com.flurben.flashbacknh.net.PacketCodec;
import com.flurben.flashbacknh.recording.HotbarState;
import com.flurben.flashbacknh.recording.RecordingManager;
import com.flurben.flashbacknh.replay.net.PacketFilter;
import com.flurben.flashbacknh.replay.net.ReplayConnection;
import com.flurben.flashbacknh.util.Notifications;

/**
 * Plays back a replay. There is at most one active instance.
 *
 * <p>
 * Timing: the replay has its own clock ({@link #replayTime}, in ticks). Every frame the game's timer is told how many
 * client ticks to run; ticks that advance the replay ("world ticks") deliver the next recorded tick of packets and let
 * the world update normally, while extra "input ticks" keep the GUI and keyboard responsive with the world frozen (the
 * same mechanism the game uses for its pause menu). The world's partial tick is replaced by the replay's, so pausing
 * and slow motion are perfectly smooth.
 */
public final class ReplayHandler implements PacketFilter.Listener {

    private static ReplayHandler active;
    private static final Visuals DEFAULT_VISUALS = new Visuals();
    private static final int MAX_WORLD_TICKS_PER_FRAME = 30;

    public static boolean isReplaying() {
        return active != null;
    }

    public static ReplayHandler get() {
        return active;
    }

    public static Visuals visuals() {
        ReplayHandler h = active;
        return h != null ? h.editor.visuals : DEFAULT_VISUALS;
    }

    /** Implemented by the exporter to drive the replay clock frame by frame. */
    public interface ClockDriver {

        /** Returns the replay time (in ticks) for the frame about to be rendered. */
        double nextFrameTime();
    }

    private final Minecraft mc = Minecraft.getMinecraft();
    public final File archive;
    public final ReplayMeta meta;
    private final File streamFile;
    private final ReplayStream stream;
    private final RegistrySnapshot registry;
    public final EditorState editor;

    private ReplayConnection connection;
    private int currentTick = -1;
    private double replayTime;
    private boolean paused = true;
    private float speed = 1.0f;
    private int pendingWorldTicks;
    private double inputAccumulator;
    private long lastFrameNanos;
    private boolean worldTick;
    private boolean seeking;

    private int localPlayerId = Integer.MIN_VALUE;
    private HotbarState hotbar;
    private int spectatingId = Integer.MIN_VALUE;
    private double[] pendingCameraPos;
    private boolean cameraPlaced;

    private long recordedWorldTime = -1;
    private int recordedWorldTimeTick;
    private float recordedRain, recordedThunder;
    private boolean timeOverridden, weatherOverridden;

    private ClockDriver clockDriver;
    private boolean applyKeyframes = true;
    private boolean editorDirty;
    private float currentFov = Float.NaN;
    private float currentRoll;
    private float shakeYaw, shakePitch;
    private int decodeFailures;

    public final CameraController cameraController = new CameraController();

    private ReplayHandler(File archive, ReplayMeta meta, File streamFile, ReplayStream stream,
        RegistrySnapshot registry, EditorState editor) {
        this.archive = archive;
        this.meta = meta;
        this.streamFile = streamFile;
        this.stream = stream;
        this.registry = registry;
        this.editor = editor;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Opens a replay. Must be called from the client thread while no world is loaded. */
    public static void open(File archive) throws IOException {
        Minecraft mc = Minecraft.getMinecraft();
        if (active != null) throw new IllegalStateException("A replay is already open");
        if (mc.theWorld != null) throw new IllegalStateException("Leave the world before opening a replay");
        if (RecordingManager.INSTANCE.isRecording()) RecordingManager.INSTANCE.finish();

        ReplayMeta meta = ReplayArchive.readMeta(archive);
        File cacheDir = new File(RecordingManager.rootDir(), "cache");
        cacheDir.mkdirs();
        File streamFile = new File(cacheDir, "playback-" + System.nanoTime() + ".fbnh");
        if (!ReplayArchive.extractEntry(archive, ReplayFormat.ENTRY_STREAM, streamFile)) {
            throw new IOException("Replay has no stream");
        }
        ReplayStream stream = ReplayStream.open(streamFile);
        if (stream.getTickCount() == 0) {
            stream.close();
            throw new IOException("Replay is empty");
        }

        RegistrySnapshot registry = null;
        byte[] reg = ReplayArchive.readEntry(archive, ReplayFormat.ENTRY_REGISTRY);
        if (reg != null) registry = RegistrySnapshot.fromBytes(reg);

        EditorState editor;
        byte[] ed = ReplayArchive.readEntry(archive, ReplayFormat.ENTRY_EDITOR);
        editor = ed != null ? EditorState.fromJson(new String(ed, StandardCharsets.UTF_8))
            : EditorState.createDefault();

        ReplayHandler handler = new ReplayHandler(archive, meta, streamFile, stream, registry, editor);
        if (registry != null) {
            List<String> missing = registry.inject();
            if (missing != null && !missing.isEmpty()) {
                FlashbackNH.LOG
                    .warn("Replay references {} blocks/items that are not installed: {}", missing.size(), missing);
                Notifications.show(
                    "Registry mismatch",
                    missing.size() + " missing blocks/items, the replay may look wrong",
                    8000);
            }
        }
        active = handler;
        CameraEntity.forgetLast();
        try {
            handler.startConnection();
            handler.fastForwardTo(0);
            if (mc.theWorld == null || mc.thePlayer == null) {
                throw new IOException("Replay did not create a world (corrupt or unsupported recording)");
            }
            handler.placeInitialCamera();
        } catch (Throwable t) {
            handler.close(false);
            throw t instanceof IOException ? (IOException) t : new IOException(t);
        }
        handler.lastFrameNanos = System.nanoTime();
        mc.displayGuiScreen(new GuiReplayEditor());
    }

    /** Leaves the replay and returns to the replay list. */
    public void exit() {
        close(true);
        mc.displayGuiScreen(new GuiReplaySelect(new GuiMainMenu()));
    }

    private void close(boolean saveEditor) {
        if (saveEditor) saveEditorAsync();
        clockDriver = null;
        try {
            mc.renderViewEntity = mc.thePlayer;
            if (connection != null) connection.close();
            if (mc.theWorld != null) mc.loadWorld(null);
        } catch (Throwable t) {
            FlashbackNH.LOG.error("Error while closing replay world", t);
        }
        active = null;
        RegistrySnapshot.revert();
        VisualsApplier.restore();
        try {
            stream.close();
        } catch (IOException ignored) {}
        FileUtils.deleteQuietly(streamFile);
        CameraEntity.forgetLast();
    }

    public void markEditorDirty() {
        editorDirty = true;
    }

    public void saveEditorAsync() {
        if (!editorDirty) return;
        editorDirty = false;
        final byte[] json = editor.toJson()
            .getBytes(StandardCharsets.UTF_8);
        final File file = archive;
        Thread t = new Thread(() -> {
            try {
                Map<String, byte[]> map = new HashMap<>();
                map.put(ReplayFormat.ENTRY_EDITOR, json);
                synchronized (ReplayHandler.class) {
                    ReplayArchive.update(file, map);
                }
            } catch (Throwable e) {
                FlashbackNH.LOG.error("Unable to save editor state", e);
            }
        }, "FlashbackNH editor save");
        t.start();
    }

    private void startConnection() {
        if (mc.thePlayer instanceof CameraEntity) ((CameraEntity) mc.thePlayer).remember();
        if (connection != null) connection.close();
        mc.thePlayer = null; // a fresh camera is created with the new world
        mc.renderViewEntity = null;
        connection = new ReplayConnection(mc);
        currentTick = -1;
        localPlayerId = Integer.MIN_VALUE;
        hotbar = null;
        recordedWorldTime = -1;
        timeOverridden = false;
        weatherOverridden = false;
    }

    private void placeInitialCamera() {
        if (CameraEntity.hasLast) return;
        CameraEntity camera = getCamera();
        if (camera == null) return;
        Entity local = localPlayerId != Integer.MIN_VALUE ? mc.theWorld.getEntityByID(localPlayerId) : null;
        if (local != null) {
            // a little behind the recording player, looking the same way, rather than inside their head
            double yaw = Math.toRadians(local.rotationYaw);
            double pitch = Math.toRadians(local.rotationPitch);
            double back = 3.0D;
            camera.setCameraPosition(
                local.posX + Math.sin(yaw) * Math.cos(pitch) * back,
                local.boundingBox.minY + eyeHeight(local) + Math.sin(pitch) * back,
                local.posZ - Math.cos(yaw) * Math.cos(pitch) * back,
                local.rotationYaw,
                local.rotationPitch);
        } else if (pendingCameraPos != null) {
            camera.setCameraPosition(
                pendingCameraPos[0],
                pendingCameraPos[1],
                pendingCameraPos[2],
                (float) pendingCameraPos[3],
                (float) pendingCameraPos[4]);
        }
        cameraPlaced = true;
    }

    // ------------------------------------------------------------------ accessors

    public CameraEntity getCamera() {
        return mc.thePlayer instanceof CameraEntity ? (CameraEntity) mc.thePlayer : null;
    }

    public int getCurrentTick() {
        return Math.max(0, currentTick);
    }

    public double getReplayTime() {
        return replayTime;
    }

    public int getTotalTicks() {
        return stream.getTickCount();
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        if (!paused && replayTime >= getTotalTicks() - 1) {
            seek(editor.inPoint >= 0 ? editor.inPoint : 0);
        }
        this.paused = paused;
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = MathHelper.clamp_float(speed, 0.05f, 64f);
    }

    public boolean isSeeking() {
        return seeking;
    }

    public boolean isApplyKeyframes() {
        return applyKeyframes;
    }

    public void setApplyKeyframes(boolean apply) {
        this.applyKeyframes = apply;
    }

    public int getLocalPlayerId() {
        return localPlayerId;
    }

    public HotbarState getHotbar() {
        return hotbar;
    }

    public boolean isWorldTick() {
        return worldTick;
    }

    public boolean isExporting() {
        return clockDriver != null;
    }

    public void setClockDriver(ClockDriver driver) {
        this.clockDriver = driver;
        this.lastFrameNanos = System.nanoTime();
    }

    public float getFovOverride() {
        return currentFov;
    }

    public float getRoll() {
        return currentRoll;
    }

    public float getShakeYaw() {
        return shakeYaw;
    }

    public float getShakePitch() {
        return shakePitch;
    }

    public List<ReplayMeta.Marker> getAllMarkers() {
        List<ReplayMeta.Marker> all = new ArrayList<>(meta.markers);
        all.addAll(editor.markers);
        Collections.sort(all, (a, b) -> a.tick - b.tick);
        return all;
    }

    // ------------------------------------------------------------------ spectating

    public void spectate(Entity entity) {
        if (!(entity instanceof EntityLivingBase) || entity == mc.thePlayer) {
            stopSpectating();
            return;
        }
        spectatingId = entity.getEntityId();
        mc.renderViewEntity = (EntityLivingBase) entity;
    }

    public void stopSpectating() {
        if (spectatingId != Integer.MIN_VALUE) {
            Entity e = mc.renderViewEntity;
            CameraEntity camera = getCamera();
            if (camera != null && e != null && e != camera) {
                camera.setCameraPosition(
                    e.posX,
                    e.posY - e.yOffset + eyeHeight(e),
                    e.posZ,
                    e.rotationYaw,
                    e.rotationPitch);
            }
        }
        spectatingId = Integer.MIN_VALUE;
        mc.renderViewEntity = mc.thePlayer;
    }

    /** Eye height above the feet (players report a tiny eye height in 1.7.10 because their posY is at the eyes). */
    public static double eyeHeight(Entity e) {
        return e instanceof EntityPlayer ? 1.62 : e.getEyeHeight();
    }

    public Entity getSpectating() {
        if (spectatingId == Integer.MIN_VALUE || mc.theWorld == null) return null;
        return mc.theWorld.getEntityByID(spectatingId);
    }

    public boolean isSpectating() {
        return spectatingId != Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ seeking

    /** Jumps to the given tick. Backwards jumps reload the replay from its start. */
    public void seek(int tick) {
        tick = MathHelper.clamp_int(tick, 0, getTotalTicks() - 1);
        seeking = true;
        try {
            if (tick < currentTick) {
                startConnection();
            }
            fastForwardTo(tick);
            replayTime = tick;
            pendingWorldTicks = 0;
            if (mc.thePlayer == null || mc.theWorld == null) {
                throw new IllegalStateException("Replay world was not created while seeking");
            }
            if (spectatingId != Integer.MIN_VALUE) {
                Entity e = getSpectating();
                if (e instanceof EntityLivingBase) mc.renderViewEntity = (EntityLivingBase) e;
            }
        } catch (Throwable t) {
            FlashbackNH.LOG.error("Seek failed", t);
            Notifications.show("FlashbackNH", "Seek failed: " + t.getMessage(), 6000);
            if (mc.theWorld == null || mc.thePlayer == null) {
                close(true);
                mc.displayGuiScreen(new GuiReplaySelect(new GuiMainMenu()));
            }
        } finally {
            seeking = false;
            lastFrameNanos = System.nanoTime();
        }
    }

    public void seekRelative(int ticks) {
        seek(getCurrentTick() + ticks);
    }

    /** Delivers and processes every tick up to and including {@code target}, without running world ticks. */
    private void fastForwardTo(int target) {
        target = Math.min(target, getTotalTicks() - 1);
        int delivered = 0;
        while (currentTick < target) {
            currentTick++;
            deliverTick(currentTick);
            if (++delivered % 100 == 0) connection.processAll();
        }
        connection.processAll();
        settleEntities();
        if (!cameraPlaced && mc.theWorld != null) placeInitialCamera();
    }

    /** After a jump, put interpolating entities at their final positions. */
    private void settleEntities() {
        WorldClient world = mc.theWorld;
        if (world == null) return;
        for (Object o : new ArrayList<>(world.loadedEntityList)) {
            Entity e = (Entity) o;
            if (e instanceof CameraEntity) continue;
            try {
                if (e instanceof EntityOtherPlayerMP) {
                    AccessorEntityOtherPlayerMP acc = (AccessorEntityOtherPlayerMP) e;
                    if (acc.flashbacknh$getIncrements() > 0) {
                        e.setPosition(acc.flashbacknh$getX(), acc.flashbacknh$getY(), acc.flashbacknh$getZ());
                        e.rotationYaw = (float) acc.flashbacknh$getYaw();
                        e.rotationPitch = (float) acc.flashbacknh$getPitch();
                        acc.flashbacknh$setIncrements(0);
                    }
                } else if (e instanceof EntityLivingBase) {
                    AccessorEntityLivingBase acc = (AccessorEntityLivingBase) e;
                    if (acc.flashbacknh$getNewPosRotationIncrements() > 0) {
                        e.setPosition(
                            acc.flashbacknh$getNewPosX(),
                            acc.flashbacknh$getNewPosY(),
                            acc.flashbacknh$getNewPosZ());
                        e.rotationYaw = (float) acc.flashbacknh$getNewRotationYaw();
                        e.rotationPitch = (float) acc.flashbacknh$getNewRotationPitch();
                        acc.flashbacknh$setNewPosRotationIncrements(0);
                    }
                    EntityLivingBase l = (EntityLivingBase) e;
                    l.prevRotationYawHead = l.rotationYawHead;
                    l.prevRenderYawOffset = l.renderYawOffset;
                }
                e.lastTickPosX = e.prevPosX = e.posX;
                e.lastTickPosY = e.prevPosY = e.posY;
                e.lastTickPosZ = e.prevPosZ = e.posZ;
                e.prevRotationYaw = e.rotationYaw;
                e.prevRotationPitch = e.rotationPitch;
            } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ packet delivery

    private void deliverTick(int tick) {
        try {
            stream.readTick(tick, this::handleRecord);
        } catch (IOException e) {
            FlashbackNH.LOG.error("Failed to read tick {}", tick, e);
        }
    }

    private void handleRecord(ReplayStream.Record rec) {
        switch (rec.type) {
            case ReplayFormat.REC_PACKET: {
                byte[] data = PacketFilter.filter(rec.data, this);
                if (data == null) return;
                int id = PacketCodec.peekId(data);
                if (id == 0x03) trackTime(data);
                else if (id == 0x2B) trackWeather(data);
                Packet packet;
                try {
                    packet = PacketCodec.decode(data);
                } catch (Throwable t) {
                    if (decodeFailures++ < 20)
                        FlashbackNH.LOG.warn("Unable to decode recorded packet 0x{}", Integer.toHexString(id), t);
                    return;
                }
                connection.deliver(packet);
                return;
            }
            case ReplayFormat.REC_LOCAL_PLAYER:
                localPlayerId = readInt(rec.data, 0);
                return;
            case ReplayFormat.REC_LOCAL_MOVE: {
                final byte[] d = rec.data;
                connection.deliver(new ReplayConnection.ActionPacket(() -> applyLocalMove(d)));
                return;
            }
            case ReplayFormat.REC_TILE_NBT: {
                final byte[] d = rec.data;
                connection.deliver(new ReplayConnection.ActionPacket(() -> applyTileNbt(d)));
                return;
            }
            case ReplayFormat.REC_CLEAR_WORLD:
                connection.deliver(new ReplayConnection.ActionPacket(this::clearWorld));
                return;
            case ReplayFormat.REC_HOTBAR: {
                final byte[] d = rec.data;
                connection.deliver(new ReplayConnection.ActionPacket(() -> hotbar = HotbarState.read(d)));
                return;
            }
            default:
                // unknown record type from a newer version: ignore
        }
    }

    private void trackTime(byte[] data) {
        int off = PacketCodec.idLength(data);
        if (data.length < off + 16) return;
        long time = 0;
        for (int i = 0; i < 8; i++) time = (time << 8) | (data[off + 8 + i] & 0xFF);
        recordedWorldTime = Math.abs(time);
        recordedWorldTimeTick = currentTick;
    }

    private void trackWeather(byte[] data) {
        int off = PacketCodec.idLength(data);
        if (data.length < off + 5) return;
        int reason = data[off] & 0xFF;
        float value = Float.intBitsToFloat(readInt(data, off + 1));
        if (reason == 1) recordedRain = 0;
        else if (reason == 2) recordedRain = 1;
        else if (reason == 7) recordedRain = value;
        else if (reason == 8) recordedThunder = value;
    }

    @Override
    public void onJoinGame(int originalEntityId) {
        if (localPlayerId == Integer.MIN_VALUE) localPlayerId = originalEntityId;
    }

    @Override
    public void onPlayerPosLook(double x, double y, double z, float yaw, float pitch) {
        if (!cameraPlaced) pendingCameraPos = new double[] { x, y, z, yaw, pitch };
    }

    private void applyLocalMove(byte[] d) {
        if (mc.theWorld == null) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(d));
            int id = in.readInt();
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            float yaw = in.readFloat(), pitch = in.readFloat(), head = in.readFloat();
            boolean onGround = in.readBoolean();
            Entity e = mc.theWorld.getEntityByID(id);
            if (e == null || e instanceof CameraEntity) return;
            e.serverPosX = MathHelper.floor_double(x * 32.0D);
            e.serverPosY = MathHelper.floor_double(y * 32.0D);
            e.serverPosZ = MathHelper.floor_double(z * 32.0D);
            e.setPositionAndRotation2(x, y, z, yaw, pitch, 1);
            e.onGround = onGround;
            if (e instanceof EntityLivingBase) ((EntityLivingBase) e).setRotationYawHead(head);
        } catch (IOException ignored) {}
    }

    private void applyTileNbt(byte[] d) {
        if (mc.theWorld == null) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(d));
            int x = in.readInt(), y = in.readInt(), z = in.readInt();
            NBTTagCompound tag = CompressedStreamTools.read(in);
            TileEntity te = mc.theWorld.getTileEntity(x, y, z);
            if (te != null) {
                te.readFromNBT(tag);
                mc.theWorld.markBlockForUpdate(x, y, z);
            }
        } catch (Throwable t) {
            FlashbackNH.LOG.debug("Unable to restore tile entity state", t);
        }
    }

    /**
     * Resets the client world, the way a dimension change does. Chunks and entities cannot be cleared in place: their
     * removal only takes effect on the next tick, after the snapshot that follows this record re-added them.
     */
    private void clearWorld() {
        WorldClient old = mc.theWorld;
        if (old == null || mc.thePlayer == null) return;
        if (mc.thePlayer instanceof CameraEntity) ((CameraEntity) mc.thePlayer).remember();
        int dimension = mc.thePlayer.dimension;
        WorldClient fresh = new WorldClient(
            connection.handler,
            new WorldSettings(
                0L,
                WorldSettings.GameType.CREATIVE,
                false,
                old.getWorldInfo()
                    .isHardcoreModeEnabled(),
                old.getWorldInfo()
                    .getTerrainType()),
            dimension,
            old.difficultySetting,
            mc.mcProfiler);
        fresh.isRemote = true;
        ((AccessorNetHandlerPlayClient) connection.handler).flashbacknh$setClientWorld(fresh);
        mc.loadWorld(fresh);
        mc.thePlayer.dimension = dimension;
        mc.setDimensionAndSpawnPlayer(dimension);
        if (mc.currentScreen instanceof GuiDownloadTerrain) mc.displayGuiScreen(new GuiReplayEditor());
    }

    private static int readInt(byte[] d, int o) {
        if (d.length < o + 4) return 0;
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    // ------------------------------------------------------------------ hooks called from mixins

    /** Called after the game's timer updated each frame. */
    public void onTimerUpdate(Timer timer) {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1.0e9;
        lastFrameNanos = now;
        dt = Math.max(0, Math.min(dt, 0.25));
        int total = getTotalTicks();

        if (clockDriver != null) {
            replayTime = Math.max(0, Math.min(clockDriver.nextFrameTime(), total - 1));
        } else if (!paused && !seeking) {
            replayTime += dt * 20.0 * effectiveSpeed();
            if (replayTime >= total - 1) {
                replayTime = total - 1;
                paused = true;
            }
        }

        int target = (int) Math.floor(replayTime);
        if (target < currentTick) {
            // the clock moved backwards (export start or keyframe preview): reload
            double keep = replayTime;
            seek(target);
            replayTime = keep;
        }
        int needed = target - currentTick;
        if (needed > MAX_WORLD_TICKS_PER_FRAME) {
            fastForwardTo(target - 1);
            needed = target - currentTick;
        }
        pendingWorldTicks = Math.max(0, needed);

        int inputTicks = 0;
        if (clockDriver == null) {
            inputAccumulator += dt * 20.0;
            inputTicks = (int) inputAccumulator;
            inputAccumulator -= inputTicks;
            inputTicks = Math.min(inputTicks, 5);
        }
        timer.elapsedTicks = Math.max(pendingWorldTicks, inputTicks);
        float partial = (float) (replayTime - target);
        timer.renderPartialTicks = MathHelper.clamp_float(partial, 0.0f, 0.9999f);
    }

    private double effectiveSpeed() {
        KeyframeTrack speedTrack = editor.track(TrackType.SPEED);
        if (applyKeyframes && speedTrack.isActive()) {
            double[] v = speedTrack.evaluate(replayTime);
            if (v != null) return Math.max(0.01, v[0]) * speed;
        }
        return speed;
    }

    /** Called at the start of every client tick. Returns true if this tick advances the replay. */
    public boolean onRunTickStart() {
        worldTick = false;
        if (seeking || mc.theWorld == null) return false;
        if (pendingWorldTicks > 0 && currentTick < getTotalTicks() - 1) {
            pendingWorldTicks--;
            currentTick++;
            deliverTick(currentTick);
            worldTick = true;
        }
        return worldTick;
    }

    public void onRunTickEnd() {
        worldTick = false;
    }

    /** Called every frame before the world is rendered. */
    public void onRenderFrame(float partialTicks) {
        if (mc.theWorld == null) return;
        CameraEntity camera = getCamera();
        if (camera == null) return;
        double time = replayTime;

        // Spectating
        if (spectatingId != Integer.MIN_VALUE) {
            Entity e = getSpectating();
            if (e instanceof EntityLivingBase && !e.isDead) {
                mc.renderViewEntity = (EntityLivingBase) e;
                double ex = e.lastTickPosX + (e.posX - e.lastTickPosX) * partialTicks;
                double ey = e.lastTickPosY + (e.posY - e.lastTickPosY) * partialTicks;
                double ez = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * partialTicks;
                camera.setPosition(ex, ey - e.yOffset + eyeHeight(e), ez);
                camera.snapInterpolation();
            } else if (mc.renderViewEntity != camera) {
                mc.renderViewEntity = camera;
            }
        } else if (mc.renderViewEntity != camera) {
            mc.renderViewEntity = camera;
        }

        // Camera path
        KeyframeTrack camTrack = editor.track(TrackType.CAMERA);
        boolean followPath = spectatingId == Integer.MIN_VALUE && camTrack.isActive()
            && (clockDriver != null || (applyKeyframes && !paused));
        if (followPath) {
            double[] v = camTrack.evaluate(time);
            camera.setCameraPosition(v[0], v[1], v[2], (float) v[3], (float) v[4]);
            currentRoll = (float) v[5];
            cameraController.reset();
        } else {
            if (spectatingId == Integer.MIN_VALUE && clockDriver == null) cameraController.update(camera);
            currentRoll = editor.visuals.cameraRoll;
        }

        // FOV
        KeyframeTrack fovTrack = editor.track(TrackType.FOV);
        if (fovTrack.isActive() && (clockDriver != null || applyKeyframes)) {
            currentFov = (float) fovTrack.evaluate(time)[0];
        } else if (editor.visuals.overrideFov) {
            currentFov = editor.visuals.fov;
        } else {
            currentFov = Float.NaN;
        }

        // Camera shake
        KeyframeTrack shakeTrack = editor.track(TrackType.SHAKE);
        double shake = 0;
        if (shakeTrack.isActive() && (clockDriver != null || applyKeyframes)) shake = shakeTrack.evaluate(time)[0];
        else if (editor.visuals.cameraShake) shake = 1;
        if (shake > 0 && spectatingId == Integer.MIN_VALUE) {
            double t = time / 20.0 * editor.visuals.shakeFrequency;
            float amp = (float) (shake * editor.visuals.shakeAmplitude);
            // applied as a view-space rotation in the camera setup, so it never accumulates into the camera itself
            shakeYaw = (float) (Math.sin(t * 7.1) * 0.6 + Math.sin(t * 13.7) * 0.4) * amp;
            shakePitch = (float) (Math.sin(t * 5.3 + 1.3) * 0.6 + Math.sin(t * 11.9 + 0.7) * 0.4) * amp;
        } else {
            shakeYaw = 0;
            shakePitch = 0;
        }

        // Time of day
        KeyframeTrack timeTrack = editor.track(TrackType.TIME);
        long overrideTime = -1;
        if (timeTrack.isActive() && (clockDriver != null || applyKeyframes)) {
            overrideTime = (long) timeTrack.evaluate(time)[0];
        } else if (editor.visuals.overrideTime) {
            overrideTime = editor.visuals.time;
        }
        if (overrideTime >= 0) {
            mc.theWorld.getWorldInfo()
                .setWorldTime(((overrideTime % 24000) + 24000) % 24000);
            timeOverridden = true;
        } else if (timeOverridden) {
            timeOverridden = false;
            if (recordedWorldTime >= 0) {
                boolean cycle = mc.theWorld.getGameRules()
                    .getGameRuleBooleanValue("doDaylightCycle");
                long t = recordedWorldTime + (cycle ? Math.max(0, currentTick - recordedWorldTimeTick) : 0);
                mc.theWorld.getWorldInfo()
                    .setWorldTime(t);
            }
        }

        // Weather
        Visuals.Weather weather = editor.visuals.weather;
        if (weather != Visuals.Weather.RECORDED) {
            float rain = weather == Visuals.Weather.CLEAR ? 0 : 1;
            float thunder = weather == Visuals.Weather.THUNDER ? 1 : 0;
            mc.theWorld.setRainStrength(rain);
            mc.theWorld.setThunderStrength(thunder);
            weatherOverridden = true;
        } else if (weatherOverridden) {
            weatherOverridden = false;
            mc.theWorld.setRainStrength(recordedRain);
            mc.theWorld.setThunderStrength(recordedThunder);
        }

        VisualsApplier.apply(editor.visuals, clockDriver != null);
        updateFrozenRendererState(camera);
    }

    /**
     * EntityRenderer.updateRenderer is the only thing that invalidates the lightmap and moves the fog brightness, and
     * it does not run on frozen ticks. Without this the world renders black until the replay first plays, and time of
     * day overrides have no effect while paused. The hand FOV modifier is the same: it starts at zero in a new client,
     * so a replay opened from the main menu would use a field of view of 0 until it first plays.
     */
    private void updateFrozenRendererState(CameraEntity camera) {
        AccessorEntityRenderer renderer = (AccessorEntityRenderer) mc.entityRenderer;
        renderer.flashbacknh$setLightmapUpdateNeeded(true);
        if (!paused || clockDriver != null) return;
        renderer.flashbacknh$setFovModifierHand(1.0F);
        renderer.flashbacknh$setFovModifierHandPrev(1.0F);
        Entity view = mc.renderViewEntity != null ? mc.renderViewEntity : camera;
        float light = mc.theWorld.getLightBrightness(
            MathHelper.floor_double(view.posX),
            MathHelper.floor_double(view.posY),
            MathHelper.floor_double(view.posZ));
        float distance = mc.gameSettings.renderDistanceChunks / 16.0F;
        float fog = light * (1.0F - distance) + distance;
        renderer.flashbacknh$setFogColor1(fog);
        renderer.flashbacknh$setFogColor2(fog);
    }

    /** True if the entity should not be rendered (render filter / hidden entities). */
    public boolean isHidden(Entity entity) {
        Visuals v = editor.visuals;
        if (entity instanceof CameraEntity) return true;
        if (entity instanceof EntityPlayer) {
            if (!v.renderPlayers) return true;
        } else if (!v.renderEntities) {
            return true;
        }
        if (!v.hiddenEntities.isEmpty()) {
            if (v.hiddenEntities.contains(entityKey(entity))) return true;
        }
        return false;
    }

    public static String entityKey(Entity entity) {
        if (entity instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) entity;
            if (p.getGameProfile() != null && p.getGameProfile()
                .getId() != null) {
                return p.getGameProfile()
                    .getId()
                    .toString();
            }
        }
        return "#" + entity.getEntityId();
    }

    public void toggleHidden(Entity entity) {
        String key = entityKey(entity);
        if (!editor.visuals.hiddenEntities.remove(key)) editor.visuals.hiddenEntities.add(key);
        markEditorDirty();
    }

    /** Called when the client world disappears without us closing it (e.g. a crash in a mod). */
    public static void onWorldGone() {
        ReplayHandler h = active;
        if (h != null && !h.seeking && h.mc.theWorld == null) {
            FlashbackNH.LOG.warn("Replay world unloaded unexpectedly, closing replay");
            h.close(true);
        }
    }

    public static GuiScreen returnScreen() {
        return new GuiReplaySelect(new GuiMainMenu());
    }
}
