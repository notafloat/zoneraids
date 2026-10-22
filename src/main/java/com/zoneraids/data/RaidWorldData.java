package com.zoneraids.data;

import com.zoneraids.ZoneRaids;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * World-level persistent state, stored in the overworld's data folder as {@code zoneraids.dat}.
 */
public final class RaidWorldData extends SavedData {
    private final Map<String, RaidMap> maps = new LinkedHashMap<>();
    private final Map<String, Long> generations = new HashMap<>();
    /** Players whose raid ended while they were offline: they lose their gear on next login. */
    private final Set<UUID> pendingMia = new HashSet<>();
    @Nullable
    private HubPoint hub;

    public static RaidWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(RaidWorldData::load, RaidWorldData::new, ZoneRaids.MODID);
    }

    public Collection<RaidMap> maps() {
        return Collections.unmodifiableCollection(maps.values());
    }

    @Nullable
    public RaidMap getMap(String id) {
        return maps.get(id);
    }

    public void putMap(RaidMap map) {
        maps.put(map.id, map);
        setDirty();
    }

    public boolean removeMap(String id) {
        boolean removed = maps.remove(id) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    @Nullable
    public HubPoint getHub() {
        return hub;
    }

    public void setHub(HubPoint hub) {
        this.hub = hub;
        setDirty();
    }

    public long getGeneration(ResourceKey<Level> dimension) {
        return generations.getOrDefault(dimension.location().toString(), 0L);
    }

    public long nextGeneration(ResourceKey<Level> dimension) {
        long next = getGeneration(dimension) + 1;
        generations.put(dimension.location().toString(), next);
        setDirty();
        return next;
    }

    public void addPendingMia(UUID player) {
        if (pendingMia.add(player)) {
            setDirty();
        }
    }

    public boolean consumePendingMia(UUID player) {
        boolean had = pendingMia.remove(player);
        if (had) {
            setDirty();
        }
        return had;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag mapList = new ListTag();
        maps.values().forEach(m -> mapList.add(m.save()));
        tag.put("Maps", mapList);
        CompoundTag gens = new CompoundTag();
        generations.forEach(gens::putLong);
        tag.put("Generations", gens);
        ListTag mia = new ListTag();
        pendingMia.forEach(id -> mia.add(NbtUtils.createUUID(id)));
        tag.put("PendingMia", mia);
        if (hub != null) {
            tag.put("Hub", hub.save());
        }
        return tag;
    }

    private static RaidWorldData load(CompoundTag tag) {
        RaidWorldData data = new RaidWorldData();
        ListTag mapList = tag.getList("Maps", Tag.TAG_COMPOUND);
        for (int i = 0; i < mapList.size(); i++) {
            RaidMap map = RaidMap.load(mapList.getCompound(i));
            data.maps.put(map.id, map);
        }
        CompoundTag gens = tag.getCompound("Generations");
        for (String key : gens.getAllKeys()) {
            data.generations.put(key, gens.getLong(key));
        }
        ListTag mia = tag.getList("PendingMia", Tag.TAG_INT_ARRAY);
        for (Tag t : mia) {
            data.pendingMia.add(NbtUtils.loadUUID(t));
        }
        if (tag.contains("Hub", Tag.TAG_COMPOUND)) {
            data.hub = HubPoint.load(tag.getCompound("Hub"));
        }
        return data;
    }
}
