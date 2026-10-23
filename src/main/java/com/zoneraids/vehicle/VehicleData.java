package com.zoneraids.vehicle;

import com.zoneraids.ZoneRaids;
import com.zoneraids.world.CordonVehicles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vehicle points per raid map, stored in the overworld's data folder as {@code zoneraids_vehicles.dat}.
 * The built-in Cordon gets its default points the first time this is loaded.
 */
public final class VehicleData extends SavedData {
    /** Bump to give existing worlds the current default Cordon points again. */
    private static final int CORDON_DEFAULTS = 1;

    private final Map<String, List<VehiclePoint>> points = new LinkedHashMap<>();
    private int cordonDefaults;

    public static VehicleData get(MinecraftServer server) {
        VehicleData data = server.overworld().getDataStorage().computeIfAbsent(VehicleData::load, VehicleData::new, ZoneRaids.MODID + "_vehicles");
        if (data.cordonDefaults < CORDON_DEFAULTS) {
            data.resetCordon();
        }
        return data;
    }

    public Set<String> mapIds() {
        return Collections.unmodifiableSet(points.keySet());
    }

    public List<VehiclePoint> points(String mapId) {
        return Collections.unmodifiableList(points.getOrDefault(mapId, List.of()));
    }

    public void add(String mapId, VehiclePoint point) {
        points.computeIfAbsent(mapId, k -> new ArrayList<>()).add(point);
        setDirty();
    }

    public int clear(String mapId) {
        List<VehiclePoint> removed = points.remove(mapId);
        setDirty();
        return removed == null ? 0 : removed.size();
    }

    public void resetCordon() {
        points.put(CordonVehicles.MAP_ID, new ArrayList<>(CordonVehicles.defaults()));
        cordonDefaults = CORDON_DEFAULTS;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag maps = new CompoundTag();
        points.forEach((id, list) -> {
            ListTag entries = new ListTag();
            list.forEach(p -> entries.add(p.save()));
            maps.put(id, entries);
        });
        tag.put("Maps", maps);
        tag.putInt("CordonDefaults", cordonDefaults);
        return tag;
    }

    private static VehicleData load(CompoundTag tag) {
        VehicleData data = new VehicleData();
        CompoundTag maps = tag.getCompound("Maps");
        for (String id : maps.getAllKeys()) {
            ListTag entries = maps.getList(id, Tag.TAG_COMPOUND);
            List<VehiclePoint> list = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                list.add(VehiclePoint.load(entries.getCompound(i)));
            }
            data.points.put(id, list);
        }
        data.cordonDefaults = tag.getInt("CordonDefaults");
        return data;
    }
}
