package com.zoneraids.data;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin-defined raid location: a dimension plus spawn and extraction points.
 */
public final class RaidMap {
    public final String id;
    public String displayName;
    public ResourceKey<Level> dimension;
    public int durationSeconds = 25 * 60;
    public int minPlayers = 1;
    public int maxPlayers = 12;
    public int extractsPerPlayer = 2;
    public boolean enabled = true;
    public final List<SpawnPoint> spawns = new ArrayList<>();
    public final List<ExtractPoint> extracts = new ArrayList<>();
    /** Optional play area (inclusive block coordinates); players in a raid are kept inside it. */
    public boolean bounded;
    public int minX;
    public int minZ;
    public int maxX;
    public int maxZ;

    public RaidMap(String id, ResourceKey<Level> dimension) {
        this.id = id;
        this.displayName = id;
        this.dimension = dimension;
    }

    public boolean isPlayable() {
        return enabled && !spawns.isEmpty() && !extracts.isEmpty();
    }

    public void setBounds(int x1, int z1, int x2, int z2) {
        bounded = true;
        minX = Math.min(x1, x2);
        minZ = Math.min(z1, z2);
        maxX = Math.max(x1, x2);
        maxZ = Math.max(z1, z2);
    }

    public boolean inBounds(double x, double z) {
        return !bounded || (x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putString("Name", displayName);
        tag.putString("Dim", dimension.location().toString());
        tag.putInt("Duration", durationSeconds);
        tag.putInt("Min", minPlayers);
        tag.putInt("Max", maxPlayers);
        tag.putInt("ExtractsPerPlayer", extractsPerPlayer);
        tag.putBoolean("Enabled", enabled);
        ListTag spawnList = new ListTag();
        spawns.forEach(s -> spawnList.add(s.save()));
        tag.put("Spawns", spawnList);
        ListTag extractList = new ListTag();
        extracts.forEach(e -> extractList.add(e.save()));
        tag.put("Extracts", extractList);
        if (bounded) {
            tag.putIntArray("Bounds", new int[]{minX, minZ, maxX, maxZ});
        }
        return tag;
    }

    public static RaidMap load(CompoundTag tag) {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(tag.getString("Dim")));
        RaidMap map = new RaidMap(tag.getString("Id"), dim);
        map.displayName = tag.getString("Name");
        map.durationSeconds = tag.getInt("Duration");
        map.minPlayers = tag.getInt("Min");
        map.maxPlayers = tag.getInt("Max");
        map.extractsPerPlayer = tag.getInt("ExtractsPerPlayer");
        map.enabled = tag.getBoolean("Enabled");
        ListTag spawnList = tag.getList("Spawns", Tag.TAG_COMPOUND);
        for (int i = 0; i < spawnList.size(); i++) {
            map.spawns.add(SpawnPoint.load(spawnList.getCompound(i)));
        }
        ListTag extractList = tag.getList("Extracts", Tag.TAG_COMPOUND);
        for (int i = 0; i < extractList.size(); i++) {
            map.extracts.add(ExtractPoint.load(extractList.getCompound(i)));
        }
        int[] bounds = tag.getIntArray("Bounds");
        if (bounds.length == 4) {
            map.setBounds(bounds[0], bounds[1], bounds[2], bounds[3]);
        }
        return map;
    }
}
