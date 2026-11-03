package com.flurben.flashbacknh.recording;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S04PacketEntityEquipment;
import net.minecraft.network.play.server.S0APacketUseBed;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S1BPacketEntityAttach;
import net.minecraft.network.play.server.S25PacketBlockBreakAnim;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.format.ReplayFormat;
import com.flurben.flashbacknh.mixins.early.AccessorPlayerControllerMP;
import com.flurben.flashbacknh.net.PacketBuilder;
import com.flurben.flashbacknh.net.PacketCodec;
import com.mojang.authlib.GameProfile;

/**
 * The server never sends the client its own movement, animations or equipment, so these are synthesized every tick
 * from the local player and written into the recording. During playback the recording player is then a normal remote
 * player entity with its original entity id, so every packet the server sent about it applies as usual.
 */
public final class LocalPlayerRecorder {

    private final Recorder recorder;
    private volatile GameProfile profile;

    private EntityClientPlayerMP lastPlayer;
    private boolean forceSpawn = true;
    private final ItemStack[] equipment = new ItemStack[5];
    private long watchedHash;
    private int lastRiding = -2;
    private boolean lastSleeping;
    private int lastHurtTime;
    private boolean lastDead;
    private int lastBreakX, lastBreakY, lastBreakZ, lastBreakStage = -1;
    private double lx, ly, lz;
    private float lyaw, lpitch, lhead;
    private int ticksSinceMove;
    private int lastHotbarHash;
    private int lastDimension = Integer.MIN_VALUE;

    public LocalPlayerRecorder(Recorder recorder, GameProfile profile) {
        this.recorder = recorder;
        this.profile = profile;
    }

    public void setProfile(GameProfile profile) {
        this.profile = profile;
    }

    public void forceRespawn() {
        forceSpawn = true;
    }

    public int getLastDimension() {
        return lastDimension;
    }

    public void tick(Minecraft mc) throws IOException {
        EntityClientPlayerMP p = mc.thePlayer;
        if (p == null || mc.theWorld == null) return;

        if (p != lastPlayer || forceSpawn) {
            if (lastPlayer != null && p.dimension != lastDimension && lastDimension != Integer.MIN_VALUE) {
                recorder.onDimensionChanged(p.dimension);
            }
            lastPlayer = p;
            forceSpawn = false;
            spawn(p);
        }
        lastDimension = p.dimension;
        int id = p.getEntityId();

        // Movement (every tick when moving, at least once per second otherwise)
        ticksSinceMove++;
        if (p.posX != lx || p.posY != ly
            || p.posZ != lz
            || p.rotationYaw != lyaw
            || p.rotationPitch != lpitch
            || p.rotationYawHead != lhead
            || ticksSinceMove >= 20) {
            writeMove(p);
        }

        // Metadata (flags, air, arrows, absorption, ...)
        int flags = p.getDataWatcher()
            .getWatchableObjectByte(0) & 0xFF;
        if (p.isUsingItem()) flags |= 1 << 4;
        else flags &= ~(1 << 4);
        List<DataWatcher.WatchableObject> watched = PacketBuilder.copyWatched(p.getDataWatcher(), flags);
        long hash = 17;
        for (DataWatcher.WatchableObject obj : watched) {
            Object v = obj.getObject();
            int h = v instanceof ItemStack ? stackHash((ItemStack) v) : (v == null ? 0 : v.hashCode());
            hash = hash * 31 + obj.getDataValueId() * 7919L + h;
        }
        if (hash != watchedHash) {
            watchedHash = hash;
            recorder.writeRaw(ReplayFormat.REC_PACKET, PacketBuilder.entityMetadata(id, watched));
        }

        // Equipment
        for (int slot = 0; slot < 5; slot++) {
            ItemStack cur = p.getEquipmentInSlot(slot);
            if (!ItemStack.areItemStacksEqual(cur, equipment[slot])) {
                equipment[slot] = cur == null ? null : cur.copy();
                packet(new S04PacketEntityEquipment(id, slot, cur == null ? null : cur.copy()));
            }
        }

        // Arm swing
        if (p.isSwingInProgress && p.swingProgressInt == 0) {
            packet(new S0BPacketAnimation(p, 0));
        }

        // Riding
        int riding = p.ridingEntity == null ? -1 : p.ridingEntity.getEntityId();
        if (riding != lastRiding) {
            lastRiding = riding;
            packet(new S1BPacketEntityAttach(0, p, p.ridingEntity));
        }

        // Sleeping
        boolean sleeping = p.isPlayerSleeping();
        if (sleeping != lastSleeping) {
            lastSleeping = sleeping;
            if (sleeping) {
                ChunkCoordinates bed = p.playerLocation;
                if (bed != null) packet(new S0APacketUseBed(p, bed.posX, bed.posY, bed.posZ));
            } else {
                packet(new S0BPacketAnimation(p, 2));
            }
        }

        // Hurt / death animations
        if (p.hurtTime > lastHurtTime && p.hurtTime == p.maxHurtTime && p.maxHurtTime > 0) {
            packet(new S19PacketEntityStatus(p, (byte) 2));
        }
        lastHurtTime = p.hurtTime;
        boolean dead = p.getHealth() <= 0.0F;
        if (dead && !lastDead) packet(new S19PacketEntityStatus(p, (byte) 3));
        lastDead = dead;

        // Block breaking progress (the server does not echo our own breaking animation)
        if (mc.playerController != null) {
            AccessorPlayerControllerMP pc = (AccessorPlayerControllerMP) mc.playerController;
            int stage = pc.flashbacknh$isHittingBlock() ? (int) (pc.flashbacknh$getCurBlockDamage() * 10.0F) - 1 : -1;
            int bx = pc.flashbacknh$getCurrentBlockX(), by = pc.flashbacknh$getCurrentBlockY(),
                bz = pc.flashbacknh$getCurrentBlockZ();
            if (stage != lastBreakStage || bx != lastBreakX || by != lastBreakY || bz != lastBreakZ) {
                if (lastBreakStage >= 0 && (bx != lastBreakX || by != lastBreakY || bz != lastBreakZ)) {
                    packet(new S25PacketBlockBreakAnim(id, lastBreakX, lastBreakY, lastBreakZ, -1));
                }
                if (stage >= 0 || lastBreakStage >= 0) {
                    packet(new S25PacketBlockBreakAnim(id, bx, by, bz, stage));
                }
                lastBreakStage = stage;
                lastBreakX = bx;
                lastBreakY = by;
                lastBreakZ = bz;
            }
        }

        if (com.flurben.flashbacknh.Config.recordHotbar) {
            byte[] hotbar = HotbarState.capture(p);
            int h = java.util.Arrays.hashCode(hotbar);
            if (h != lastHotbarHash) {
                lastHotbarHash = h;
                recorder.writeRaw(ReplayFormat.REC_HOTBAR, hotbar);
            }
        }
    }

    private void spawn(EntityClientPlayerMP p) throws IOException {
        int id = p.getEntityId();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(id);
        recorder.writeRaw(ReplayFormat.REC_LOCAL_PLAYER, bos.toByteArray());

        GameProfile prof = profile != null ? profile : p.getGameProfile();
        int flags = p.getDataWatcher()
            .getWatchableObjectByte(0) & 0xFF;
        List<DataWatcher.WatchableObject> watched = PacketBuilder.copyWatched(p.getDataWatcher(), flags);
        recorder.writeRaw(ReplayFormat.REC_PACKET, PacketBuilder.spawnPlayer(p, prof, watched));
        packet(new S19PacketEntityHeadLook(p, (byte) MathHelper.floor_float(p.rotationYawHead * 256.0F / 360.0F)));
        for (int slot = 0; slot < 5; slot++) {
            ItemStack cur = p.getEquipmentInSlot(slot);
            equipment[slot] = cur == null ? null : cur.copy();
            if (cur != null) packet(new S04PacketEntityEquipment(id, slot, cur.copy()));
        }
        lastRiding = -2;
        lastSleeping = false;
        lastHurtTime = p.hurtTime;
        lastDead = false;
        lastBreakStage = -1;
        watchedHash = 0;
        lastHotbarHash = 0;
        writeMove(p);
    }

    private void writeMove(EntityClientPlayerMP p) throws IOException {
        ticksSinceMove = 0;
        lx = p.posX;
        ly = p.posY;
        lz = p.posZ;
        lyaw = p.rotationYaw;
        lpitch = p.rotationPitch;
        lhead = p.rotationYawHead;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(48);
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(p.getEntityId());
        // posY of the local player is eye-relative in 1.7.10; store the feet position like remote players use
        dos.writeDouble(p.posX);
        dos.writeDouble(p.boundingBox.minY);
        dos.writeDouble(p.posZ);
        dos.writeFloat(p.rotationYaw);
        dos.writeFloat(p.rotationPitch);
        dos.writeFloat(p.rotationYawHead);
        dos.writeBoolean(p.onGround);
        recorder.writeRaw(ReplayFormat.REC_LOCAL_MOVE, bos.toByteArray());
    }

    private void packet(Packet packet) {
        try {
            byte[] data = PacketCodec.encode(packet);
            if (data != null) recorder.writeRaw(ReplayFormat.REC_PACKET, data);
        } catch (Throwable t) {
            FlashbackNH.LOG.debug(
                "Failed to write local player packet {}",
                packet.getClass()
                    .getSimpleName(),
                t);
        }
    }

    private static int stackHash(ItemStack s) {
        int h = s.getItem() == null ? 0
            : s.getItem()
                .hashCode();
        h = h * 31 + s.stackSize;
        h = h * 31 + s.getItemDamage();
        h = h * 31 + (s.getTagCompound() == null ? 0
            : s.getTagCompound()
                .hashCode());
        return h;
    }

    @SuppressWarnings("unused")
    private static boolean isRemote(Entity e) {
        return e.worldObj != null && e.worldObj.isRemote;
    }
}
