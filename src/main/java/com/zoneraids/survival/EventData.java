package com.zoneraids.survival;

import com.zoneraids.ZoneRaids;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/** When the next emission and supply drop are due (overworld game time), and the supply drops still smoking. */
public final class EventData extends SavedData {
    public static final class Drop {
        public final BlockPos pos;
        /** Game time after which the drop stops smoking and is no longer tracked (the crate stays). */
        public final long expires;
        public boolean guarded;

        Drop(BlockPos pos, long expires, boolean guarded) {
            this.pos = pos;
            this.expires = expires;
            this.guarded = guarded;
        }
    }

    /** 0 = not scheduled yet. */
    public long nextEmission;
    public long nextAirdrop;
    public final List<Drop> drops = new ArrayList<>();

    public static EventData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(EventData::load, EventData::new, ZoneRaids.MODID + "_events");
    }

    public Drop addDrop(BlockPos pos, long expires) {
        Drop drop = new Drop(pos, expires, false);
        drops.add(drop);
        setDirty();
        return drop;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("NextEmission", nextEmission);
        tag.putLong("NextAirdrop", nextAirdrop);
        ListTag list = new ListTag();
        for (Drop drop : drops) {
            CompoundTag t = new CompoundTag();
            t.put("Pos", NbtUtils.writeBlockPos(drop.pos));
            t.putLong("Expires", drop.expires);
            t.putBoolean("Guarded", drop.guarded);
            list.add(t);
        }
        tag.put("Drops", list);
        return tag;
    }

    private static EventData load(CompoundTag tag) {
        EventData data = new EventData();
        data.nextEmission = tag.getLong("NextEmission");
        data.nextAirdrop = tag.getLong("NextAirdrop");
        for (Tag t : tag.getList("Drops", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            data.drops.add(new Drop(NbtUtils.readBlockPos(c.getCompound("Pos")), c.getLong("Expires"), c.getBoolean("Guarded")));
        }
        return data;
    }
}
