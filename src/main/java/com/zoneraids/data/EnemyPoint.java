package com.zoneraids.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * Where a group of enemies appears in a raid. The group spawns once per raid, the first time a
 * raider comes within {@code trigger} blocks, with {@code chance} of being there at all.
 *
 * @param kind enemy kind from the {@code enemyTypes} config (bandit, military, blind_dog, ...)
 */
public record EnemyPoint(String kind, BlockPos pos, int min, int max, double chance, int trigger) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Kind", kind);
        tag.putLong("Pos", pos.asLong());
        tag.putInt("Min", min);
        tag.putInt("Max", max);
        tag.putDouble("Chance", chance);
        tag.putInt("Trigger", trigger);
        return tag;
    }

    public static EnemyPoint load(CompoundTag tag) {
        return new EnemyPoint(tag.getString("Kind"), BlockPos.of(tag.getLong("Pos")), tag.getInt("Min"), tag.getInt("Max"),
                tag.getDouble("Chance"), tag.getInt("Trigger"));
    }
}
