package com.flurben.flashbacknh.recording;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiPlayerInfo;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S04PacketEntityEquipment;
import net.minecraft.network.play.server.S05PacketSpawnPosition;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S0APacketUseBed;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.network.play.server.S1BPacketEntityAttach;
import net.minecraft.network.play.server.S1DPacketEntityEffect;
import net.minecraft.network.play.server.S20PacketEntityProperties;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraft.network.play.server.S2BPacketChangeGameState;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3BPacketScoreboardObjective;
import net.minecraft.network.play.server.S3CPacketUpdateScore;
import net.minecraft.network.play.server.S3DPacketDisplayScoreboard;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.potion.PotionEffect;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.IChunkProvider;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.format.ReplayFormat;
import com.flurben.flashbacknh.format.TileBatch;
import com.flurben.flashbacknh.mixins.early.InvokerEntityTrackerEntry;
import com.flurben.flashbacknh.net.PacketBuilder;
import com.flurben.flashbacknh.net.PacketCodec;

/**
 * Re-creates the current client-side world state as a sequence of packets. Used when a recording starts while already
 * in a world, and when a paused recording is resumed.
 */
public final class WorldSnapshot {

    private static final int CHUNK_SEARCH_RADIUS = 40;

    /** A class with this many tile entities is what makes reading them slow, see {@link #readTiles}. */
    private static final int BULK_TILE_COUNT = 2048;

    private static final ExecutorService WORKERS = createWorkers();

    /** A record, or records that are still being made on a background thread. */
    public static final class Rec {

        public final byte type;
        public final byte[] data;
        public final Future<List<Rec>> later;

        Rec(byte type, byte[] data) {
            this.type = type;
            this.data = data;
            this.later = null;
        }

        Rec(Future<List<Rec>> later) {
            this.type = 0;
            this.data = null;
            this.later = later;
        }
    }

    public final List<Rec> records = new ArrayList<>();
    private final Minecraft mc;
    private final Consumer<List<int[]>> onTilesRead;
    private final AtomicInteger failures = new AtomicInteger();

    private WorldSnapshot(Minecraft mc, Consumer<List<int[]>> onTilesRead) {
        this.mc = mc;
        this.onTilesRead = onTilesRead;
    }

    /**
     * Must run on the client thread, which it holds up only for what has to be read at one moment in time. What takes
     * long is left to background threads, so some {@link #records} are still in the making when this returns.
     *
     * @param initial     true for the first snapshot of a recording (starts with a join game packet)
     * @param lastDim     dimension of the previous snapshot/recorded state; a respawn packet is added if it changed
     * @param onTilesRead receives the positions of tile entities that had no description packet (candidates for a
     *                    server-side refresh), on a background thread
     */
    public static WorldSnapshot create(Minecraft mc, boolean initial, Integer lastDim,
        Consumer<List<int[]>> onTilesRead) {
        WorldSnapshot s = new WorldSnapshot(mc, onTilesRead);
        s.build(initial, lastDim);
        return s;
    }

    private static ExecutorService createWorkers() {
        int threads = Math.max(
            1,
            Math.min(
                4,
                Runtime.getRuntime()
                    .availableProcessors() / 2));
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            threads,
            threads,
            30,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            task -> {
                Thread t = new Thread(task, "FlashbackNH snapshot worker");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private void warn(Packet packet, Throwable t) {
        if (failures.getAndIncrement() < 20) {
            FlashbackNH.LOG.warn(
                "Snapshot: failed to serialize {}",
                packet.getClass()
                    .getName(),
                t);
        }
    }

    private void packet(Packet packet) {
        if (packet == null) return;
        try {
            byte[] data = PacketCodec.encode(packet);
            if (data != null) records.add(new Rec(ReplayFormat.REC_PACKET, data));
        } catch (Throwable t) {
            warn(packet, t);
        }
    }

    /** Serializes a packet in the background, for packets that only compress their contents at that point. */
    private void packetLater(final Packet packet) {
        records.add(new Rec(WORKERS.submit(() -> {
            try {
                byte[] data = PacketCodec.encode(packet);
                if (data != null) return Collections.singletonList(new Rec(ReplayFormat.REC_PACKET, data));
            } catch (Throwable t) {
                warn(packet, t);
            }
            return Collections.<Rec>emptyList();
        })));
    }

    private void raw(byte type, byte[] data) {
        if (data != null) records.add(new Rec(type, data));
    }

    /**
     * Reads tile entities into records. A world can hold millions of tile entities of the same kind, GregTech ores in
     * particular, and those are read in the background. That is only for classes that do not tick and exist in great
     * numbers, as those are simple. A value that changes meanwhile does no harm: the change is also in the recorded
     * packets that follow the snapshot.
     */
    private void readTiles(List<Chunk> chunks) {
        List<TileEntity> tiles = new ArrayList<>();
        Map<Class<?>, int[]> counts = new HashMap<>();
        for (Chunk chunk : chunks) {
            for (Object o : chunk.chunkTileEntityMap.values()) {
                TileEntity te = (TileEntity) o;
                if (te == null || te.isInvalid()) continue;
                tiles.add(te);
                counts.computeIfAbsent(te.getClass(), c -> new int[1])[0]++;
            }
        }
        final TileReader now = new TileReader();
        final List<TileEntity> bulk = new ArrayList<>();
        for (TileEntity te : tiles) {
            if (!te.canUpdate() && counts.get(te.getClass())[0] >= BULK_TILE_COUNT) bulk.add(te);
            else now.read(te);
        }
        records.addAll(now.finish());
        records.add(new Rec(WORKERS.submit(() -> {
            TileReader later = new TileReader();
            for (TileEntity te : bulk) later.read(te);
            List<Rec> result = later.finish();
            later.missing.addAll(now.missing);
            onTilesRead.accept(later.missing);
            return result;
        })));
    }

    private final class TileReader {

        final List<int[]> missing = new ArrayList<>();
        private final List<Rec> out = new ArrayList<>();
        private final TileBatch batch = new TileBatch();

        void read(TileEntity te) {
            Packet desc = null;
            try {
                desc = te.getDescriptionPacket();
            } catch (Throwable t) {
                if (failures.getAndIncrement() < 20) {
                    FlashbackNH.LOG.debug("Snapshot: description packet failed for {}", te, t);
                }
            }
            if (desc != null) {
                try {
                    byte[] data = PacketCodec.encode(desc);
                    if (data != null) out.add(new Rec(ReplayFormat.REC_PACKET, data));
                } catch (Throwable t) {
                    warn(desc, t);
                }
                return;
            }
            missing.add(new int[] { te.xCoord, te.yCoord, te.zCoord });
            try {
                NBTTagCompound tag = new NBTTagCompound();
                te.writeToNBT(tag);
                batch.add(te.xCoord, te.yCoord, te.zCoord, tag);
            } catch (Throwable t) {
                if (failures.getAndIncrement() < 20) {
                    FlashbackNH.LOG.debug("Snapshot: writeToNBT failed for {}", te, t);
                }
            }
        }

        List<Rec> finish() {
            if (batch.size() > 0) {
                try {
                    out.add(new Rec(ReplayFormat.REC_TILE_BATCH, batch.toBytes()));
                } catch (IOException e) {
                    FlashbackNH.LOG.warn("Snapshot: unable to store tile entities", e);
                }
            }
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    private void build(boolean initial, Integer lastDim) {
        WorldClient world = mc.theWorld;
        EntityPlayer self = mc.thePlayer;
        int dim = world.provider.dimensionId;
        WorldSettings.GameType gameType = world.getWorldInfo()
            .getGameType();
        if (gameType == null || gameType == WorldSettings.GameType.NOT_SET) gameType = WorldSettings.GameType.SURVIVAL;

        if (initial) {
            int maxPlayers = mc.getNetHandler() != null ? mc.getNetHandler().currentServerMaxPlayers : 20;
            packet(
                new S01PacketJoinGame(
                    self.getEntityId(),
                    gameType,
                    world.getWorldInfo()
                        .isHardcoreModeEnabled(),
                    dim,
                    world.difficultySetting,
                    Math.min(255, maxPlayers),
                    world.getWorldInfo()
                        .getTerrainType()));
        } else if (lastDim == null || lastDim != dim) {
            packet(
                new S07PacketRespawn(
                    dim,
                    world.difficultySetting,
                    world.getWorldInfo()
                        .getTerrainType(),
                    gameType));
        }

        boolean daylight = world.getGameRules()
            .getGameRuleBooleanValue("doDaylightCycle");
        packet(new S03PacketTimeUpdate(world.getTotalWorldTime(), world.getWorldTime(), daylight));
        ChunkCoordinates spawn = world.getSpawnPoint();
        if (spawn != null) packet(new S05PacketSpawnPosition(spawn.posX, spawn.posY, spawn.posZ));
        if (world.isRaining()) {
            packet(new S2BPacketChangeGameState(1, 0));
        }
        packet(new S2BPacketChangeGameState(7, world.getRainStrength(1.0F)));
        packet(new S2BPacketChangeGameState(8, world.getWeightedThunderStrength(1.0F)));

        // Chunks. One per packet: a chunk that comes back unchanged in a later snapshot is then an identical packet,
        // which the writer stores once. Sharing a packet would save almost nothing, as a chunk is larger than deflate's
        // window. Creating the packet copies the chunk, compressing that happens when the packet is serialized.
        List<Chunk> chunks = loadedChunks(world, self);
        for (Chunk chunk : chunks) {
            packetLater(new S26PacketMapChunkBulk(Collections.singletonList(chunk)));
        }

        readTiles(chunks);

        // Entities
        List<Entity> entities = new ArrayList<>(world.loadedEntityList);
        for (Entity e : entities) {
            if (e == self || e.isDead) continue;
            try {
                spawnEntity(e);
            } catch (Throwable t) {
                if (failures.getAndIncrement() < 20) FlashbackNH.LOG.debug("Snapshot: unable to spawn {}", e, t);
            }
        }
        // attachments after every entity exists
        for (Entity e : entities) {
            if (e == self || e.isDead) continue;
            if (e.ridingEntity != null) packet(new S1BPacketEntityAttach(0, e, e.ridingEntity));
            if (e instanceof EntityLiving && ((EntityLiving) e).getLeashedToEntity() != null) {
                packet(new S1BPacketEntityAttach(1, e, ((EntityLiving) e).getLeashedToEntity()));
            }
        }

        // Player list
        if (mc.getNetHandler() != null) {
            for (Object o : new ArrayList<>(mc.getNetHandler().playerInfoList)) {
                GuiPlayerInfo info = (GuiPlayerInfo) o;
                packet(new S38PacketPlayerListItem(info.name, true, info.responseTime));
            }
        }

        // Scoreboard
        try {
            Scoreboard sb = world.getScoreboard();
            for (Object o : sb.getScoreObjectives()) {
                packet(new S3BPacketScoreboardObjective((ScoreObjective) o, 0));
            }
            for (int slot = 0; slot < 3; slot++) {
                ScoreObjective obj = sb.func_96539_a(slot);
                if (obj != null) packet(new S3DPacketDisplayScoreboard(slot, obj));
            }
            for (Object o : sb.func_96528_e()) {
                packet(new S3CPacketUpdateScore((Score) o, 0));
            }
            for (Object o : sb.getTeams()) {
                packet(new S3EPacketTeams((ScorePlayerTeam) o, 0));
            }
        } catch (Throwable t) {
            FlashbackNH.LOG.debug("Snapshot: scoreboard failed", t);
        }
    }

    /**
     * The chunks the client holds. ChunkProviderClient's chunk list cannot be used: Hodgepodge removes it. A server
     * never
     * sends chunks further from the player than its view distance, which is at most 32.
     */
    private static List<Chunk> loadedChunks(WorldClient world, Entity center) {
        List<Chunk> chunks = new ArrayList<>();
        int cx = MathHelper.floor_double(center.posX) >> 4;
        int cz = MathHelper.floor_double(center.posZ) >> 4;
        IChunkProvider provider = world.getChunkProvider();
        for (int x = cx - CHUNK_SEARCH_RADIUS; x <= cx + CHUNK_SEARCH_RADIUS; x++) {
            for (int z = cz - CHUNK_SEARCH_RADIUS; z <= cz + CHUNK_SEARCH_RADIUS; z++) {
                Chunk chunk = provider.provideChunk(x, z);
                if (chunk != null && !(chunk instanceof EmptyChunk)) chunks.add(chunk);
            }
        }
        return chunks;
    }

    @SuppressWarnings("unchecked")
    private void spawnEntity(Entity e) throws IOException {
        int id = e.getEntityId();
        if (e instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) e;
            raw(
                ReplayFormat.REC_PACKET,
                PacketBuilder.spawnPlayer(p, p.getGameProfile(), PacketBuilder.copyWatched(p.getDataWatcher(), -1)));
        } else {
            EntityTrackerEntry entry = new EntityTrackerEntry(e, 64, 20, true);
            Packet spawn;
            try {
                spawn = ((InvokerEntityTrackerEntry) entry).flashbacknh$createSpawnPacket();
            } catch (IllegalArgumentException unknown) {
                return;
            }
            packet(spawn);
            if (!e.getDataWatcher()
                .getIsBlank()) {
                raw(
                    ReplayFormat.REC_PACKET,
                    PacketBuilder.entityMetadata(id, PacketBuilder.copyWatched(e.getDataWatcher(), -1)));
            }
            if (!(e instanceof EntityLivingBase)) {
                packet(new S12PacketEntityVelocity(id, e.motionX, e.motionY, e.motionZ));
            }
        }
        if (e instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) e;
            packet(
                new S19PacketEntityHeadLook(
                    e,
                    (byte) MathHelper.floor_float(living.getRotationYawHead() * 256.0F / 360.0F)));
            try {
                Collection<net.minecraft.entity.ai.attributes.IAttributeInstance> attributes = living.getAttributeMap()
                    .getAllAttributes();
                if (!attributes.isEmpty()) packet(new S20PacketEntityProperties(id, attributes));
            } catch (Throwable ignored) {}
            for (int slot = 0; slot < 5; slot++) {
                ItemStack stack = living.getEquipmentInSlot(slot);
                if (stack != null) packet(new S04PacketEntityEquipment(id, slot, stack.copy()));
            }
            for (Object o : new ArrayList<>(living.getActivePotionEffects())) {
                packet(new S1DPacketEntityEffect(id, (PotionEffect) o));
            }
        }
        if (e instanceof EntityPlayer && ((EntityPlayer) e).isPlayerSleeping()) {
            packet(
                new S0APacketUseBed(
                    (EntityPlayer) e,
                    MathHelper.floor_double(e.posX),
                    MathHelper.floor_double(e.posY),
                    MathHelper.floor_double(e.posZ)));
        }
    }
}
