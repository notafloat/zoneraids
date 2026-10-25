package com.zoneraids.survival;

import com.zoneraids.ZoneRaids;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What became of each place site (by region key): built, its crew out, cleared, or unusable terrain. */
public final class PlaceData extends SavedData {
    public enum State {
        SKIPPED, BUILT, ACTIVE, CLEARED
    }

    public static final class Entry {
        public State state;
        public final Places.Type type;
        public final BlockPos origin;
        public BlockPos crewPos;
        public BlockPos traderPos;
        public int wakeRadius;
        /** The crew that is out: enemies of a hostile place or ruin, guards of a friendly one. */
        public final List<UUID> crew = new ArrayList<>();
        @Nullable
        public UUID trader;

        Entry(State state, Places.Type type, BlockPos origin) {
            this.state = state;
            this.type = type;
            this.origin = origin;
            this.crewPos = origin;
            this.traderPos = origin;
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();

    public static PlaceData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PlaceData::load, PlaceData::new, ZoneRaids.MODID + "_places");
    }

    @Nullable
    public Entry get(String key) {
        return entries.get(key);
    }

    public Map<String, Entry> all() {
        return entries;
    }

    public Entry put(String key, State state, Places.Type type, BlockPos origin) {
        Entry entry = new Entry(state, type, origin);
        entries.put(key, entry);
        setDirty();
        return entry;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag list = new CompoundTag();
        entries.forEach((key, e) -> {
            CompoundTag t = new CompoundTag();
            t.putString("State", e.state.name());
            t.putString("Type", e.type.name());
            t.put("Origin", NbtUtils.writeBlockPos(e.origin));
            t.put("Crew", NbtUtils.writeBlockPos(e.crewPos));
            t.put("Trader", NbtUtils.writeBlockPos(e.traderPos));
            t.putInt("Wake", e.wakeRadius);
            ListTag ids = new ListTag();
            for (UUID id : e.crew) {
                ids.add(NbtUtils.createUUID(id));
            }
            t.put("CrewIds", ids);
            if (e.trader != null) {
                t.putUUID("TraderId", e.trader);
            }
            list.put(key, t);
        });
        tag.put("Places", list);
        return tag;
    }

    private static PlaceData load(CompoundTag tag) {
        PlaceData data = new PlaceData();
        CompoundTag list = tag.getCompound("Places");
        for (String key : list.getAllKeys()) {
            CompoundTag t = list.getCompound(key);
            try {
                Entry e = new Entry(State.valueOf(t.getString("State")), Places.Type.valueOf(t.getString("Type")),
                        NbtUtils.readBlockPos(t.getCompound("Origin")));
                e.crewPos = NbtUtils.readBlockPos(t.getCompound("Crew"));
                e.traderPos = NbtUtils.readBlockPos(t.getCompound("Trader"));
                e.wakeRadius = t.getInt("Wake");
                for (Tag id : t.getList("CrewIds", Tag.TAG_INT_ARRAY)) {
                    e.crew.add(NbtUtils.loadUUID(id));
                }
                e.trader = t.hasUUID("TraderId") ? t.getUUID("TraderId") : null;
                data.entries.put(key, e);
            } catch (IllegalArgumentException ignored) {
                // A place type that no longer exists.
            }
        }
        return data;
    }
}
