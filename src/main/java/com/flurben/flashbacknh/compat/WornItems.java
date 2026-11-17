package com.flurben.flashbacknh.compat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import com.flurben.flashbacknh.FlashbackNH;

/**
 * What players wear or hold through other mods, which those mods draw on the player model: baubles (Botania's cosmetic
 * items among them), the offhand item, Galacticraft's oxygen gear, worn backpacks and angel ring wings.
 *
 * <p>
 * A server sends this when a player comes into view and when it changes, but not always to the player it belongs to,
 * and a recording that starts mid-session missed the first part. So recordings store it themselves: for every player in
 * a world snapshot, and for the recording player whenever it changes. Each mod is read and written through its own
 * client-side state. All optional: a mod that is not installed is skipped.
 */
public final class WornItems {

    /** One mod's worn items. */
    private abstract static class Source {

        final String id;
        private boolean initialised, available;

        Source(String id) {
            this.id = id;
        }

        final boolean available() {
            if (!initialised) {
                initialised = true;
                try {
                    init();
                    available = true;
                } catch (ClassNotFoundException e) {
                    available = false;
                } catch (Throwable t) {
                    FlashbackNH.LOG.warn("Worn items of {} cannot be recorded", id, t);
                    available = false;
                }
            }
            return available;
        }

        abstract void init() throws Exception;

        /** The player's state, an empty tag for nothing worn. */
        abstract NBTTagCompound save(EntityPlayer p) throws Exception;

        abstract void load(EntityPlayer p, NBTTagCompound tag) throws Exception;

        /** Forgets what is kept by player name, which outlives a world. */
        void clear() throws Exception {}
    }

    private static final List<Source> SOURCES = Arrays.asList(
        new Baubles(),
        new Backhand(),
        new AdventureBackpack(),
        new Galacticraft(),
        new AngelRing(),
        new Backpack());

    private static int failures;

    private WornItems() {}

    /**
     * A record of what this player wears: int entity id, NBT with a tag per mod. Null when no such mod is installed.
     */
    public static byte[] record(EntityPlayer p) {
        NBTTagCompound all = save(p);
        if (all == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(p.getEntityId());
            CompressedStreamTools.write(all, out);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** What the player wears, to compare with an earlier state. Null when no such mod is installed. */
    public static NBTTagCompound save(EntityPlayer p) {
        NBTTagCompound all = null;
        for (Source s : SOURCES) {
            if (!s.available()) continue;
            try {
                NBTTagCompound tag = s.save(p);
                if (all == null) all = new NBTTagCompound();
                all.setTag(s.id, tag == null ? new NBTTagCompound() : tag);
            } catch (Throwable t) {
                warn("read", s, t);
            }
        }
        return all;
    }

    /** Applies a record made by {@link #record} to the player in the replay world. Client thread. */
    public static void apply(World world, byte[] data) {
        if (world == null) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            Entity e = world.getEntityByID(in.readInt());
            if (!(e instanceof EntityPlayer)) return;
            NBTTagCompound all = CompressedStreamTools.read(in);
            for (Source s : SOURCES) {
                if (!all.hasKey(s.id) || !s.available()) continue;
                try {
                    s.load((EntityPlayer) e, all.getCompoundTag(s.id));
                } catch (Throwable t) {
                    warn("restore", s, t);
                }
            }
        } catch (IOException e) {
            FlashbackNH.LOG.debug("Unable to read worn items", e);
        }
    }

    /**
     * Forgets the worn items mods keep by player name, when a replay starts and ends, so that neither the world before
     * nor the replay leaves its state on players of the same name.
     */
    public static void clear() {
        for (Source s : SOURCES) {
            if (!s.available()) continue;
            try {
                s.clear();
            } catch (Throwable t) {
                warn("clear", s, t);
            }
        }
    }

    private static void warn(String what, Source s, Throwable t) {
        if (failures++ < 10) FlashbackNH.LOG.warn("Unable to {} worn items of {}", what, s.id, t);
    }

    private static NBTTagCompound stack(ItemStack stack) {
        NBTTagCompound tag = new NBTTagCompound();
        if (stack != null) tag.setTag("Item", stack.writeToNBT(new NBTTagCompound()));
        return tag;
    }

    private static ItemStack stack(NBTTagCompound tag) {
        return tag.hasKey("Item") ? ItemStack.loadItemStackFromNBT(tag.getCompoundTag("Item")) : null;
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> staticMap(Class<?> owner, String field) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return (Map<String, T>) f.get(null);
    }

    /** Baubles (Baubles Expanded): every slot, kept on the client by player name. */
    private static final class Baubles extends Source {

        private Method get, saveNbt, readNbt, clearAll;
        private Field stacks;

        Baubles() {
            super("baubles");
        }

        @Override
        void init() throws Exception {
            Class<?> handler = Class.forName("baubles.common.lib.PlayerHandler");
            Class<?> inv = Class.forName("baubles.common.container.InventoryBaubles");
            get = handler.getMethod("getPlayerBaubles", EntityPlayer.class);
            clearAll = handler.getMethod("clearClientPlayerBaubles");
            saveNbt = inv.getMethod("saveNBT", NBTTagCompound.class);
            readNbt = inv.getMethod("readNBT", NBTTagCompound.class);
            stacks = inv.getField("stackList");
        }

        @Override
        NBTTagCompound save(EntityPlayer p) throws Exception {
            NBTTagCompound tag = new NBTTagCompound();
            saveNbt.invoke(get.invoke(null, p), tag);
            return tag;
        }

        @Override
        void load(EntityPlayer p, NBTTagCompound tag) throws Exception {
            Object inv = get.invoke(null, p);
            Arrays.fill((ItemStack[]) stacks.get(inv), null);
            readNbt.invoke(inv, tag);
        }

        @Override
        void clear() throws Exception {
            clearAll.invoke(null);
        }
    }

    /** Backhand: the offhand item, a slot of the player's inventory. */
    private static final class Backhand extends Source {

        private Method get, set;

        Backhand() {
            super("backhand");
        }

        @Override
        void init() throws Exception {
            Class<?> utils = Class.forName("xonin.backhand.api.core.BackhandUtils");
            get = utils.getMethod("getOffhandItem", EntityPlayer.class);
            set = utils.getMethod("setPlayerOffhandItem", EntityPlayer.class, ItemStack.class);
        }

        @Override
        NBTTagCompound save(EntityPlayer p) throws Exception {
            return stack((ItemStack) get.invoke(null, p));
        }

        @Override
        void load(EntityPlayer p, NBTTagCompound tag) throws Exception {
            set.invoke(null, p, stack(tag));
        }
    }

    /** Adventure Backpack: the worn backpack or copter/jetpack, kept in the player's entity properties. */
    private static final class AdventureBackpack extends Source {

        private Method get, getWearable, setWearable;

        AdventureBackpack() {
            super("adventurebackpack");
        }

        @Override
        void init() throws Exception {
            Class<?> prop = Class.forName("com.darkona.adventurebackpack.playerProperties.BackpackProperty");
            get = prop.getMethod("get", EntityPlayer.class);
            getWearable = prop.getMethod("getWearable");
            setWearable = prop.getMethod("setWearable", ItemStack.class);
        }

        @Override
        NBTTagCompound save(EntityPlayer p) throws Exception {
            Object prop = get.invoke(null, p);
            return stack(prop == null ? null : (ItemStack) getWearable.invoke(prop));
        }

        @Override
        void load(EntityPlayer p, NBTTagCompound tag) throws Exception {
            Object prop = get.invoke(null, p);
            if (prop != null) setWearable.invoke(prop, stack(tag));
        }
    }

    /** Galacticraft: oxygen mask, gear, tanks, parachute, frequency module and thermal padding, kept by player name. */
    private static final class Galacticraft extends Source {

        private Map<String, Object> data;
        private java.lang.reflect.Constructor<?> create;
        private final List<Field> fields = new ArrayList<>();

        Galacticraft() {
            super("galacticraft");
        }

        @Override
        void init() throws Exception {
            Class<?> gear = Class.forName("micdoodle8.mods.galacticraft.core.wrappers.PlayerGearData");
            data = staticMap(
                Class.forName("micdoodle8.mods.galacticraft.core.proxy.ClientProxyCore"),
                "playerItemData");
            create = gear.getConstructor(EntityPlayer.class);
            for (Field f : gear.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() == EntityPlayer.class) continue;
                f.setAccessible(true);
                fields.add(f);
            }
        }

        @Override
        NBTTagCompound save(EntityPlayer p) throws Exception {
            NBTTagCompound tag = new NBTTagCompound();
            Object gear = data.get(p.getCommandSenderName());
            if (gear == null) return tag;
            for (Field f : fields) {
                Object v = f.get(gear);
                if (v instanceof Integer) tag.setInteger(f.getName(), (Integer) v);
                else if (v instanceof Boolean) tag.setBoolean(f.getName(), (Boolean) v);
                else if (v instanceof int[]) tag.setIntArray(f.getName(), (int[]) v);
                else if (v instanceof boolean[]) {
                    boolean[] b = (boolean[]) v;
                    byte[] bytes = new byte[b.length];
                    for (int i = 0; i < b.length; i++) bytes[i] = (byte) (b[i] ? 1 : 0);
                    tag.setByteArray(f.getName(), bytes);
                } else if (v instanceof ResourceLocation) tag.setString(f.getName(), v.toString());
            }
            return tag;
        }

        @Override
        void load(EntityPlayer p, NBTTagCompound tag) throws Exception {
            if (tag.hasNoTags()) {
                data.remove(p.getCommandSenderName());
                return;
            }
            Object gear = create.newInstance(p);
            for (Field f : fields) {
                if (!tag.hasKey(f.getName())) continue;
                Class<?> t = f.getType();
                if (t == int.class) f.setInt(gear, tag.getInteger(f.getName()));
                else if (t == boolean.class) f.setBoolean(gear, tag.getBoolean(f.getName()));
                else if (t == int[].class) {
                    int[] to = (int[]) f.get(gear), from = tag.getIntArray(f.getName());
                    System.arraycopy(from, 0, to, 0, Math.min(from.length, to.length));
                } else if (t == boolean[].class) {
                    boolean[] to = (boolean[]) f.get(gear);
                    byte[] from = tag.getByteArray(f.getName());
                    for (int i = 0; i < Math.min(from.length, to.length); i++) to[i] = from[i] != 0;
                } else if (t == ResourceLocation.class) {
                    f.set(gear, new ResourceLocation(tag.getString(f.getName())));
                }
            }
            data.put(p.getCommandSenderName(), gear);
        }

        @Override
        void clear() {
            data.clear();
        }
    }

    /** Extra Utilities: the angel ring's wings, kept by player name. */
    private static final class AngelRing extends Source {

        private Map<String, Integer> flying;

        AngelRing() {
            super("angelring");
        }

        @Override
        void init() throws Exception {
            flying = staticMap(Class.forName("com.rwtema.extrautils.EventHandlerClient"), "flyingPlayers");
        }

        @Override
        NBTTagCompound save(EntityPlayer p) {
            NBTTagCompound tag = new NBTTagCompound();
            Integer wings = flying.get(p.getCommandSenderName());
            if (wings != null) tag.setInteger("wings", wings);
            return tag;
        }

        @Override
        void load(EntityPlayer p, NBTTagCompound tag) {
            if (tag.hasKey("wings")) flying.put(p.getCommandSenderName(), tag.getInteger("wings"));
            else flying.remove(p.getCommandSenderName());
        }

        @Override
        void clear() {
            flying.clear();
        }
    }

    /** Backpacks (Eydamos): the worn personal backpack, kept in the player's entity data. */
    private static final class Backpack extends Source {

        private static final String KEY = "pBackpackMeta";

        Backpack() {
            super("backpack");
        }

        @Override
        void init() throws Exception {
            Class.forName("de.eydamos.backpack.handler.EventHandlerClientOnly");
        }

        @Override
        NBTTagCompound save(EntityPlayer p) {
            NBTTagCompound tag = new NBTTagCompound();
            NBTTagCompound data = p.getEntityData();
            if (data.hasKey(KEY)) tag.setInteger("meta", data.getInteger(KEY));
            return tag;
        }

        @Override
        void load(EntityPlayer p, NBTTagCompound tag) {
            if (tag.hasKey("meta")) p.getEntityData()
                .setInteger(KEY, tag.getInteger("meta"));
            else p.getEntityData()
                .removeTag(KEY);
        }
    }
}
