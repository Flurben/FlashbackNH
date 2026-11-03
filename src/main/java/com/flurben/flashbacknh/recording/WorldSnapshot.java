package com.flurben.flashbacknh.recording;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiPlayerInfo;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
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

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.format.ReplayFormat;
import com.flurben.flashbacknh.mixins.early.AccessorChunkProviderClient;
import com.flurben.flashbacknh.mixins.early.InvokerEntityTrackerEntry;
import com.flurben.flashbacknh.net.PacketBuilder;
import com.flurben.flashbacknh.net.PacketCodec;

/**
 * Re-creates the current client-side world state as a sequence of packets. Used when a recording starts while already
 * in a world, and when a paused recording is resumed.
 */
public final class WorldSnapshot {

    public static final class Rec {

        public final byte type;
        public final byte[] data;

        Rec(byte type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    /** Positions of tile entities that had no description packet (candidates for a server-side refresh). */
    public final List<int[]> tilesWithoutDescription = new ArrayList<>();
    public final List<Rec> records = new ArrayList<>();
    private final Minecraft mc;
    private int failures;

    private WorldSnapshot(Minecraft mc) {
        this.mc = mc;
    }

    /**
     * @param initial  true for the first snapshot of a recording (starts with a join game packet)
     * @param lastDim  dimension of the previous snapshot/recorded state; a respawn packet is added if it changed
     */
    public static WorldSnapshot create(Minecraft mc, boolean initial, Integer lastDim) {
        WorldSnapshot s = new WorldSnapshot(mc);
        s.build(initial, lastDim);
        return s;
    }

    private void packet(Packet packet) {
        if (packet == null) return;
        try {
            byte[] data = PacketCodec.encode(packet);
            if (data != null) records.add(new Rec(ReplayFormat.REC_PACKET, data));
        } catch (Throwable t) {
            if (failures++ < 20) {
                FlashbackNH.LOG.warn(
                    "Snapshot: failed to serialize {}",
                    packet.getClass()
                        .getName(),
                    t);
            }
        }
    }

    private void raw(byte type, byte[] data) {
        if (data != null) records.add(new Rec(type, data));
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

        // Chunks
        ChunkProviderClient provider = (ChunkProviderClient) world.getChunkProvider();
        List<Chunk> chunks = new ArrayList<>(((AccessorChunkProviderClient) provider).flashbacknh$getChunkListing());
        List<Chunk> batch = new ArrayList<>();
        for (Chunk chunk : chunks) {
            batch.add(chunk);
            if (batch.size() >= 5) {
                packet(new S26PacketMapChunkBulk(new ArrayList<>(batch)));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) packet(new S26PacketMapChunkBulk(batch));

        // Tile entities
        for (Chunk chunk : chunks) {
            for (Object o : new ArrayList<>(chunk.chunkTileEntityMap.values())) {
                TileEntity te = (TileEntity) o;
                if (te == null || te.isInvalid()) continue;
                Packet desc = null;
                try {
                    desc = te.getDescriptionPacket();
                } catch (Throwable t) {
                    if (failures++ < 20) FlashbackNH.LOG.debug("Snapshot: description packet failed for {}", te, t);
                }
                if (desc != null) {
                    packet(desc);
                } else {
                    tilesWithoutDescription.add(new int[] { te.xCoord, te.yCoord, te.zCoord });
                    try {
                        NBTTagCompound tag = new NBTTagCompound();
                        te.writeToNBT(tag);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        DataOutputStream dos = new DataOutputStream(bos);
                        dos.writeInt(te.xCoord);
                        dos.writeInt(te.yCoord);
                        dos.writeInt(te.zCoord);
                        CompressedStreamTools.write(tag, dos);
                        dos.flush();
                        raw(ReplayFormat.REC_TILE_NBT, bos.toByteArray());
                    } catch (Throwable t) {
                        if (failures++ < 20) FlashbackNH.LOG.debug("Snapshot: writeToNBT failed for {}", te, t);
                    }
                }
            }
        }

        // Entities
        List<Entity> entities = new ArrayList<>(world.loadedEntityList);
        for (Entity e : entities) {
            if (e == self || e.isDead) continue;
            try {
                spawnEntity(e);
            } catch (Throwable t) {
                if (failures++ < 20) FlashbackNH.LOG.debug("Snapshot: unable to spawn {}", e, t);
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
                raw(ReplayFormat.REC_PACKET, PacketBuilder.entityMetadata(id, PacketBuilder.copyWatched(e.getDataWatcher(), -1)));
            }
            if (!(e instanceof EntityLivingBase)) {
                packet(new S12PacketEntityVelocity(id, e.motionX, e.motionY, e.motionZ));
            }
        }
        if (e instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) e;
            packet(new S19PacketEntityHeadLook(e, (byte) MathHelper.floor_float(living.getRotationYawHead() * 256.0F / 360.0F)));
            try {
                Collection<?> attributes = living.getAttributeMap()
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
            packet(new S0APacketUseBed((EntityPlayer) e, MathHelper.floor_double(e.posX), MathHelper.floor_double(e.posY), MathHelper.floor_double(e.posZ)));
        }
    }
}
