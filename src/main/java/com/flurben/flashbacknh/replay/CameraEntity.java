package com.flurben.flashbacknh.replay;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.util.DamageSource;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import com.mojang.util.UUIDTypeAdapter;

/**
 * The replay viewer. It replaces the client player while a replay is open: it never ticks physics, never collides,
 * is never rendered and is moved per frame by {@link CameraController}.
 */
public class CameraEntity extends EntityClientPlayerMP {

    public static final int CAMERA_ENTITY_ID = ReplayConstants.CAMERA_ENTITY_ID;
    private static final UUID CAMERA_UUID = UUID.fromString("f1a5b8c0-0000-4000-8000-f1a5bac0ca3e");

    /** Last known camera transform, carried over when the client re-creates the player (respawn/seek). */
    static double lastX, lastY, lastZ;
    static float lastYaw, lastPitch;
    static boolean hasLast;

    private final PotionEffect nightVision = new PotionEffect(Potion.nightVision.id, 1000000, 0, true);

    public CameraEntity(Minecraft mc, World world, NetHandlerPlayClient handler, StatFileWriter stats) {
        super(
            mc,
            world,
            new net.minecraft.util.Session("ReplayCamera", UUIDTypeAdapter.fromUUID(CAMERA_UUID), "", "legacy"),
            handler,
            stats);
        this.noClip = true;
        this.capabilities.isFlying = true;
        this.capabilities.allowFlying = true;
        this.capabilities.disableDamage = true;
        if (hasLast) {
            restoreLast();
        }
    }

    public void restoreLast() {
        setCameraPosition(lastX, lastY, lastZ, lastYaw, lastPitch);
    }

    /** Moves the camera without any interpolation (eye position). */
    public void setCameraPosition(double x, double y, double z, float yaw, float pitch) {
        this.setPosition(x, y, z);
        this.prevPosX = this.lastTickPosX = this.posX;
        this.prevPosY = this.lastTickPosY = this.posY;
        this.prevPosZ = this.lastTickPosZ = this.posZ;
        this.rotationYaw = this.prevRotationYaw = yaw;
        this.rotationPitch = this.prevRotationPitch = pitch;
        this.rotationYawHead = this.prevRotationYawHead = yaw;
        this.renderYawOffset = this.prevRenderYawOffset = yaw;
        this.motionX = this.motionY = this.motionZ = 0;
        remember();
    }

    public void remember() {
        lastX = posX;
        lastY = posY;
        lastZ = posZ;
        lastYaw = rotationYaw;
        lastPitch = rotationPitch;
        hasLast = true;
    }

    public static void forgetLast() {
        hasLast = false;
    }

    /** Keeps render interpolation from smearing the per-frame camera movement. */
    public void snapInterpolation() {
        this.prevPosX = this.lastTickPosX = this.posX;
        this.prevPosY = this.lastTickPosY = this.posY;
        this.prevPosZ = this.lastTickPosZ = this.posZ;
        this.prevRotationYaw = this.rotationYaw;
        this.prevRotationPitch = this.rotationPitch;
        this.prevRotationYawHead = this.rotationYawHead = this.rotationYaw;
        this.prevRenderYawOffset = this.renderYawOffset = this.rotationYaw;
    }

    @Override
    public void onUpdate() {
        // no physics, no movement packets
        this.ticksExisted++;
        snapInterpolation();
        remember();
    }

    @Override
    public void onLivingUpdate() {}

    @Override
    public void moveEntity(double x, double y, double z) {
        setPosition(posX + x, posY + y, posZ + z);
    }

    @Override
    public void sendMotionUpdates() {}

    @Override
    public void preparePlayerToSpawn() {
        super.preparePlayerToSpawn();
        if (hasLast) restoreLast();
    }

    @Override
    protected boolean func_145771_j(double x, double y, double z) {
        return false; // never pushed out of blocks
    }

    @Override
    public boolean isEntityInsideOpaqueBlock() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    public void applyEntityCollision(Entity entity) {}

    @Override
    public boolean isInvisible() {
        return true;
    }

    @Override
    public boolean isInvisibleToPlayer(EntityPlayer player) {
        return true;
    }

    @Override
    public boolean isInRangeToRender3d(double x, double y, double z) {
        return false;
    }

    @Override
    public boolean isInRangeToRenderDist(double distance) {
        return false;
    }

    @Override
    public boolean shouldRenderInPass(int pass) {
        return false;
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        return false;
    }

    @Override
    public float getFOVMultiplier() {
        return 1.0F;
    }

    @Override
    public boolean isPotionActive(Potion potion) {
        if (potion == Potion.nightVision && ReplayHandler.visuals().nightVision) return true;
        return false;
    }

    @Override
    public boolean isPotionActive(int id) {
        if (id == Potion.nightVision.id && ReplayHandler.visuals().nightVision) return true;
        return false;
    }

    @Override
    public PotionEffect getActivePotionEffect(Potion potion) {
        if (potion == Potion.nightVision && ReplayHandler.visuals().nightVision) return nightVision;
        return null;
    }

    @Override
    public void addChatMessage(IChatComponent message) {
        Minecraft.getMinecraft().ingameGUI.getChatGUI()
            .printChatMessage(message);
    }

    @Override
    public void sendChatMessage(String message) {
        // nowhere to send it; client commands are handled before this is called
    }

    @Override
    public void playSound(String name, float volume, float pitch) {}

    @Override
    public boolean isSneaking() {
        return false;
    }

    @Override
    public boolean isPlayerSleeping() {
        return false;
    }
}
