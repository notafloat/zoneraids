package com.zoneraids.survival;

import com.zoneraids.ZoneRaids;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The temporary camps standing in the overworld: where, who sits there, what was placed and when they pack up. */
public final class CampData extends SavedData {
    public enum State {
        /** Sitting round the fire. */
        RESTING,
        /** Up and fighting (or defending themselves). */
        AWAKE,
        /** Everyone is dead; the camp stays until it is packed away. */
        CLEARED
    }

    /** A block the camp put down; it is only taken away again if it is still there. */
    public record Placed(BlockPos pos, Block block) {
    }

    public static final class Camp {
        public final int id;
        /** "stalker" for a friendly camp, otherwise an enemy kind. */
        public final String kind;
        public final BlockPos origin;
        public State state = State.RESTING;
        /** Game time when the camp packs up (once nobody is near). */
        public long expires;
        public final List<UUID> members = new ArrayList<>();
        /** The invisible armor stands the members sit on. */
        public final List<UUID> seats = new ArrayList<>();
        /** Blocks the camp put down (tent, bedrolls, campfire), removed when it packs up. */
        public final List<Placed> blocks = new ArrayList<>();

        Camp(int id, String kind, BlockPos origin) {
            this.id = id;
            this.kind = kind;
            this.origin = origin;
        }

        public boolean friendly() {
            return Camps.FRIENDLY.equals(kind);
        }
    }

    private final Map<Integer, Camp> camps = new HashMap<>();
    private int nextId = 1;

    public static CampData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(CampData::load, CampData::new, ZoneRaids.MODID + "_camps");
    }

    @Nullable
    public Camp get(int id) {
        return camps.get(id);
    }

    public Map<Integer, Camp> all() {
        return camps;
    }

    public Camp add(String kind, BlockPos origin) {
        Camp camp = new Camp(nextId++, kind, origin);
        camps.put(camp.id, camp);
        setDirty();
        return camp;
    }

    public void remove(int id) {
        if (camps.remove(id) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Camp c : camps.values()) {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", c.id);
            t.putString("Kind", c.kind);
            t.put("Origin", NbtUtils.writeBlockPos(c.origin));
            t.putString("State", c.state.name());
            t.putLong("Expires", c.expires);
            t.put("Members", uuids(c.members));
            t.put("Seats", uuids(c.seats));
            ListTag blocks = new ListTag();
            for (Placed placed : c.blocks) {
                CompoundTag b = NbtUtils.writeBlockPos(placed.pos());
                b.putString("Block", String.valueOf(ForgeRegistries.BLOCKS.getKey(placed.block())));
                blocks.add(b);
            }
            t.put("Blocks", blocks);
            list.add(t);
        }
        tag.put("Camps", list);
        tag.putInt("NextId", nextId);
        return tag;
    }

    private static ListTag uuids(List<UUID> ids) {
        ListTag list = new ListTag();
        for (UUID id : ids) {
            list.add(NbtUtils.createUUID(id));
        }
        return list;
    }

    private static CampData load(CompoundTag tag) {
        CampData data = new CampData();
        data.nextId = Math.max(1, tag.getInt("NextId"));
        for (Tag entry : tag.getList("Camps", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            Camp c = new Camp(t.getInt("Id"), t.getString("Kind"), NbtUtils.readBlockPos(t.getCompound("Origin")));
            try {
                c.state = State.valueOf(t.getString("State"));
            } catch (IllegalArgumentException e) {
                c.state = State.AWAKE;
            }
            c.expires = t.getLong("Expires");
            for (Tag id : t.getList("Members", Tag.TAG_INT_ARRAY)) {
                c.members.add(NbtUtils.loadUUID(id));
            }
            for (Tag id : t.getList("Seats", Tag.TAG_INT_ARRAY)) {
                c.seats.add(NbtUtils.loadUUID(id));
            }
            for (Tag b : t.getList("Blocks", Tag.TAG_COMPOUND)) {
                ResourceLocation id = ResourceLocation.tryParse(((CompoundTag) b).getString("Block"));
                Block block = id == null ? null : ForgeRegistries.BLOCKS.getValue(id);
                if (block != null && block != Blocks.AIR) {
                    c.blocks.add(new Placed(NbtUtils.readBlockPos((CompoundTag) b), block));
                }
            }
            data.camps.put(c.id, c);
            data.nextId = Math.max(data.nextId, c.id + 1);
        }
        return data;
    }
}
