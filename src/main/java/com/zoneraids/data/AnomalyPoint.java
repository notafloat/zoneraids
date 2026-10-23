package com.zoneraids.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/**
 * A raid anomaly: a spot that hurts players who step into it and may hold an artifact.
 * {@code pos} is the block the player stands in at its centre.
 */
public record AnomalyPoint(Type type, BlockPos pos, double radius) {
    public enum Type {
        /** Electric discharge. */
        ELECTRA,
        /** Heat: sets on fire. */
        BURNER,
        /** Gravity: pulls in from around it, throws whoever reaches the centre. */
        VORTEX,
        /** Acid jelly: poison. */
        ACID;

        public static Type byName(String name, Type fallback) {
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public double centerX() {
        return pos.getX() + 0.5;
    }

    public double centerZ() {
        return pos.getZ() + 0.5;
    }

    /** How far from the centre the anomaly acts on players. */
    public double reach() {
        return type == Type.VORTEX ? radius * 2.5 : radius;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", type.name());
        tag.putLong("Pos", pos.asLong());
        tag.putDouble("Radius", radius);
        return tag;
    }

    public static AnomalyPoint load(CompoundTag tag) {
        return new AnomalyPoint(Type.byName(tag.getString("Type"), Type.ELECTRA), BlockPos.of(tag.getLong("Pos")), tag.getDouble("Radius"));
    }
}
