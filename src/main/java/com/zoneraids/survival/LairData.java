package com.zoneraids.survival;

import com.zoneraids.ZoneRaids;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** What became of each lair site (by region key): built, its boss awake, beaten, or unusable terrain. */
public final class LairData extends SavedData {
    public enum State {
        SKIPPED, BUILT, ACTIVE, DEFEATED
    }

    public static final class Entry {
        public State state;
        public final Lairs.Type type;
        public final BlockPos origin;
        public BlockPos boss;
        public BlockPos guards;
        public int wakeRadius;
        @Nullable
        public UUID bossId;

        Entry(State state, Lairs.Type type, BlockPos origin) {
            this.state = state;
            this.type = type;
            this.origin = origin;
            this.boss = origin;
            this.guards = origin;
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();

    public static LairData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(LairData::load, LairData::new, ZoneRaids.MODID + "_lairs");
    }

    @Nullable
    public Entry get(String key) {
        return entries.get(key);
    }

    public Map<String, Entry> all() {
        return entries;
    }

    public Entry put(String key, State state, Lairs.Type type, BlockPos origin) {
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
            t.put("Boss", NbtUtils.writeBlockPos(e.boss));
            t.put("Guards", NbtUtils.writeBlockPos(e.guards));
            t.putInt("Wake", e.wakeRadius);
            if (e.bossId != null) {
                t.putUUID("BossId", e.bossId);
            }
            list.put(key, t);
        });
        tag.put("Lairs", list);
        return tag;
    }

    private static LairData load(CompoundTag tag) {
        LairData data = new LairData();
        CompoundTag list = tag.getCompound("Lairs");
        for (String key : list.getAllKeys()) {
            CompoundTag t = list.getCompound(key);
            try {
                Entry e = new Entry(State.valueOf(t.getString("State")), Lairs.Type.valueOf(t.getString("Type")),
                        NbtUtils.readBlockPos(t.getCompound("Origin")));
                e.boss = NbtUtils.readBlockPos(t.getCompound("Boss"));
                e.guards = NbtUtils.readBlockPos(t.getCompound("Guards"));
                e.wakeRadius = t.getInt("Wake");
                e.bossId = t.hasUUID("BossId") ? t.getUUID("BossId") : null;
                data.entries.put(key, e);
            } catch (IllegalArgumentException ignored) {
                // A lair type that no longer exists.
            }
        }
        return data;
    }
}
