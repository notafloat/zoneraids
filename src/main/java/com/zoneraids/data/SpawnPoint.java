package com.zoneraids.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

public record SpawnPoint(BlockPos pos, float yaw) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        tag.putFloat("Yaw", yaw);
        return tag;
    }

    public static SpawnPoint load(CompoundTag tag) {
        return new SpawnPoint(BlockPos.of(tag.getLong("Pos")), tag.getFloat("Yaw"));
    }
}
