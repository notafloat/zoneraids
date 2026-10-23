package com.zoneraids.perk;

import org.jetbrains.annotations.Nullable;

/**
 * Persistent perks: the only progress that survives the end of a session. Every bonus is a small,
 * down-to-earth skill (no magic): a few percent per level.
 */
public enum Perk {
    /** Healing +8% per level, eating and drinking 10% faster per level. */
    FIELD_MEDIC,
    /** Movement speed +2% per level. */
    ATHLETE,
    /** Footsteps 15% quieter per level, mobs and NPCs notice the player 8% closer per level. */
    QUIET_STEP,
    /** Fall damage -12% per level. */
    FALL_TRAINING,
    /** Extraction 10% faster per level. */
    ORIENTATION;

    public static final int MAX_LEVEL = 3;

    public final String id = name().toLowerCase();

    /** Points needed to go from {@code level - 1} to {@code level}. */
    public static int cost(int level) {
        return level;
    }

    @Nullable
    public static Perk byId(String id) {
        for (Perk perk : values()) {
            if (perk.id.equals(id)) {
                return perk;
            }
        }
        return null;
    }
}
