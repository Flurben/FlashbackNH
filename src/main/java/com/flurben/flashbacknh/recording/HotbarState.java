package com.flurben.flashbacknh.recording;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** First-person HUD information of the recording player (hotbar, health, food, experience). */
public final class HotbarState {

    public int selectedSlot;
    public float health;
    public float maxHealth;
    public int food;
    public int armor;
    public int air;
    public int xpLevel;
    public float xpProgress;
    public final ItemStack[] hotbar = new ItemStack[9];

    public static byte[] capture(EntityPlayer p) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(p.inventory.currentItem);
            out.writeFloat(p.getHealth());
            out.writeFloat(p.getMaxHealth());
            out.writeInt(
                p.getFoodStats()
                    .getFoodLevel());
            out.writeInt(p.getTotalArmorValue());
            out.writeInt(p.getAir());
            out.writeInt(p.experienceLevel);
            out.writeFloat(p.experience);
            NBTTagCompound root = new NBTTagCompound();
            NBTTagList list = new NBTTagList();
            for (int i = 0; i < 9; i++) {
                ItemStack s = p.inventory.mainInventory[i];
                NBTTagCompound tag = new NBTTagCompound();
                tag.setByte("Slot", (byte) i);
                if (s != null) s.writeToNBT(tag);
                list.appendTag(tag);
            }
            root.setTag("items", list);
            CompressedStreamTools.write(root, out);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static HotbarState read(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            HotbarState s = new HotbarState();
            s.selectedSlot = in.readByte();
            s.health = in.readFloat();
            s.maxHealth = in.readFloat();
            s.food = in.readInt();
            s.armor = in.readInt();
            s.air = in.readInt();
            s.xpLevel = in.readInt();
            s.xpProgress = in.readFloat();
            NBTTagCompound root = CompressedStreamTools.read(in);
            NBTTagList list = root.getTagList("items", 10);
            for (int i = 0; i < list.tagCount() && i < 9; i++) {
                NBTTagCompound tag = list.getCompoundTagAt(i);
                int slot = tag.getByte("Slot");
                if (slot >= 0 && slot < 9 && tag.hasKey("id")) {
                    s.hotbar[slot] = ItemStack.loadItemStackFromNBT(tag);
                }
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }
}
