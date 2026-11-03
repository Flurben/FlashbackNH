package com.flurben.flashbacknh.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.MathHelper;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Builds wire bytes for packets whose vanilla constructors cannot be used safely on the client. */
public final class PacketBuilder {

    public static final int ID_SPAWN_PLAYER = 0x0C;
    public static final int ID_ENTITY_METADATA = 0x1C;

    private PacketBuilder() {}

    /** S0CPacketSpawnPlayer with an explicit profile (so skins work for anybody viewing the replay). */
    public static byte[] spawnPlayer(EntityPlayer player, GameProfile profile, List<DataWatcher.WatchableObject> watched)
        throws IOException {
        ByteBuf raw = Unpooled.buffer(256);
        try {
            PacketBuffer buf = new PacketBuffer(raw);
            buf.writeVarIntToBuffer(ID_SPAWN_PLAYER);
            buf.writeVarIntToBuffer(player.getEntityId());
            UUID uuid = profile.getId();
            buf.writeStringToBuffer(uuid == null ? "" : uuid.toString());
            buf.writeStringToBuffer(profile.getName() == null ? "" : profile.getName());
            buf.writeVarIntToBuffer(
                profile.getProperties()
                    .size());
            for (Property property : profile.getProperties()
                .values()) {
                buf.writeStringToBuffer(property.getName());
                buf.writeStringToBuffer(property.getValue());
                buf.writeStringToBuffer(property.getSignature() == null ? "" : property.getSignature());
            }
            buf.writeInt(MathHelper.floor_double(player.posX * 32.0D));
            // feet position: the local player's posY is at eye height in 1.7.10
            buf.writeInt(MathHelper.floor_double(player.boundingBox.minY * 32.0D));
            buf.writeInt(MathHelper.floor_double(player.posZ * 32.0D));
            buf.writeByte((byte) (int) (player.rotationYaw * 256.0F / 360.0F));
            buf.writeByte((byte) (int) (player.rotationPitch * 256.0F / 360.0F));
            ItemStack held = player.inventory.getCurrentItem();
            buf.writeShort(held == null ? 0 : Item.getIdFromItem(held.getItem()));
            DataWatcher.writeWatchedListToPacketBuffer(watched == null ? new ArrayList<>() : watched, buf);
            return toArray(raw);
        } finally {
            raw.release();
        }
    }

    /** S1CPacketEntityMetadata from an explicit list of watched objects. */
    public static byte[] entityMetadata(int entityId, List<DataWatcher.WatchableObject> watched) throws IOException {
        ByteBuf raw = Unpooled.buffer(128);
        try {
            PacketBuffer buf = new PacketBuffer(raw);
            buf.writeVarIntToBuffer(ID_ENTITY_METADATA);
            buf.writeInt(entityId);
            DataWatcher.writeWatchedListToPacketBuffer(watched == null ? new ArrayList<>() : watched, buf);
            return toArray(raw);
        } finally {
            raw.release();
        }
    }

    /**
     * Copies all watched objects of the entity, optionally replacing the entity flag byte (index 0). The copies are
     * detached from the entity's data watcher so they can be modified and serialized from any thread.
     */
    @SuppressWarnings("unchecked")
    public static List<DataWatcher.WatchableObject> copyWatched(DataWatcher watcher, int flagsOverride) {
        List<DataWatcher.WatchableObject> list = watcher.getAllWatched();
        List<DataWatcher.WatchableObject> copy = new ArrayList<>();
        if (list == null) return copy;
        for (DataWatcher.WatchableObject obj : list) {
            Object value = obj.getObject();
            if (obj.getDataValueId() == 0 && flagsOverride >= 0 && obj.getObjectType() == 0) {
                value = (byte) flagsOverride;
            } else if (value instanceof ItemStack) {
                value = ((ItemStack) value).copy();
            }
            copy.add(new DataWatcher.WatchableObject(obj.getObjectType(), obj.getDataValueId(), value));
        }
        return copy;
    }

    private static byte[] toArray(ByteBuf raw) {
        byte[] out = new byte[raw.readableBytes()];
        raw.getBytes(raw.readerIndex(), out);
        return out;
    }
}
