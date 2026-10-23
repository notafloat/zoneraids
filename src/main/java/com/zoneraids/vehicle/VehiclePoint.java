package com.zoneraids.vehicle;

import net.minecraft.nbt.CompoundTag;

/** Where a raid vehicle may appear. {@code y == GROUND} means "on top of whatever is at (x, z)". */
public record VehiclePoint(int x, int y, int z, float yaw, double chance) {
    public static final int GROUND = Integer.MIN_VALUE;

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", x);
        tag.putInt("Y", y);
        tag.putInt("Z", z);
        tag.putFloat("Yaw", yaw);
        tag.putDouble("Chance", chance);
        return tag;
    }

    public static VehiclePoint load(CompoundTag tag) {
        return new VehiclePoint(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"), tag.getFloat("Yaw"), tag.getDouble("Chance"));
    }
}
