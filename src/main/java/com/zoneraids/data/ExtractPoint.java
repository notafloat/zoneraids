package com.zoneraids.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * Extraction zone. {@code chance} is the probability that the exit is open in a given raid.
 */
public record ExtractPoint(String name, BlockPos pos, double radius, double chance) {
    public boolean contains(Vec3 position) {
        double dx = position.x - (pos.getX() + 0.5);
        double dy = position.y - (pos.getY() + 0.5);
        double dz = position.z - (pos.getZ() + 0.5);
        // Generous vertical tolerance so zones work on stairs and uneven ground.
        return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= Math.max(3.0, radius);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        tag.putLong("Pos", pos.asLong());
        tag.putDouble("Radius", radius);
        tag.putDouble("Chance", chance);
        return tag;
    }

    public static ExtractPoint load(CompoundTag tag) {
        return new ExtractPoint(tag.getString("Name"), BlockPos.of(tag.getLong("Pos")), tag.getDouble("Radius"), tag.getDouble("Chance"));
    }
}
