package com.flurben.flashbacknh.format;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.flurben.flashbacknh.FlashbackNH;

import cpw.mods.fml.common.registry.GameData;

/**
 * The FML block/item id mapping. Block and item ids in 1.7.10 are assigned per world, and every recorded packet that
 * contains a block or item refers to them numerically, so the mapping active while recording has to be restored during
 * playback.
 */
public final class RegistrySnapshot {

    public final Map<String, Integer> ids;
    public final Set<String> blockSubstitutions;
    public final Set<String> itemSubstitutions;

    public RegistrySnapshot(Map<String, Integer> ids, Set<String> blockSubstitutions, Set<String> itemSubstitutions) {
        this.ids = ids;
        this.blockSubstitutions = blockSubstitutions;
        this.itemSubstitutions = itemSubstitutions;
    }

    public static RegistrySnapshot capture() {
        GameData.GameDataSnapshot snap = GameData.buildItemDataList();
        return new RegistrySnapshot(
            new HashMap<>(snap.idMap),
            new HashSet<>(snap.blockSubstitutions),
            new HashSet<>(snap.itemSubstitutions));
    }

    /** Applies this mapping to the running game. Returns the list of names that could not be mapped. */
    public List<String> inject() {
        return GameData.injectWorldIDMap(ids, blockSubstitutions, itemSubstitutions, false, false);
    }

    public static void revert() {
        try {
            GameData.revertToFrozen();
        } catch (Throwable t) {
            FlashbackNH.LOG.error("Failed to revert id map", t);
        }
    }

    /** Number of entries whose id differs from (or is missing in) the current game. */
    public int countDifferences() {
        Map<String, Integer> current = GameData.buildItemDataList().idMap;
        int diff = 0;
        for (Map.Entry<String, Integer> e : ids.entrySet()) {
            Integer cur = current.get(e.getKey());
            if (cur == null) diff++;
        }
        return diff;
    }

    public byte[] toBytes() throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound map = new NBTTagCompound();
        for (Map.Entry<String, Integer> e : ids.entrySet()) {
            map.setInteger(e.getKey(), e.getValue());
        }
        root.setTag("ids", map);
        root.setTag("blockSubstitutions", toList(blockSubstitutions));
        root.setTag("itemSubstitutions", toList(itemSubstitutions));
        return CompressedStreamTools.compress(root);
    }

    public static RegistrySnapshot fromBytes(byte[] bytes) throws IOException {
        NBTTagCompound root = CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes));
        NBTTagCompound map = root.getCompoundTag("ids");
        Map<String, Integer> ids = new HashMap<>();
        for (Object key : map.func_150296_c()) {
            String k = (String) key;
            ids.put(k, map.getInteger(k));
        }
        return new RegistrySnapshot(
            ids,
            fromList(root.getTagList("blockSubstitutions", 8)),
            fromList(root.getTagList("itemSubstitutions", 8)));
    }

    private static NBTTagList toList(Set<String> set) {
        NBTTagList list = new NBTTagList();
        for (String s : set) list.appendTag(new NBTTagString(s));
        return list;
    }

    private static Set<String> fromList(NBTTagList list) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < list.tagCount(); i++) set.add(list.getStringTagAt(i));
        return set;
    }
}
