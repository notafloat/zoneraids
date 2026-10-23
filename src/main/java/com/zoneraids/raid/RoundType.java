package com.zoneraids.raid;

import org.jetbrains.annotations.Nullable;

/** What a round of a session is about. */
public enum RoundType {
    /** Loot and reach your extraction point. */
    EXTRACTION(true, false, false),
    /** Hold the drop zone with no rivals inside, take the loot and extract. */
    SUPPLY_DROP(true, false, false),
    /** Get inside the shrinking safe zone and outlast the emission. No extraction. */
    EMISSION(false, true, true),
    /** One player carries documents and everyone sees them; carry them out through an extract. */
    DOCUMENTS(true, false, false),
    /** Everyone against everyone in a shrinking zone; last one standing wins. */
    DEATHMATCH(false, true, true);

    public final String id = name().toLowerCase();
    public final boolean extracts;
    public final boolean shrinkingZone;
    /** Players still alive when the timer runs out survive with their gear instead of going missing. */
    public final boolean survivorsKeepGear;

    RoundType(boolean extracts, boolean shrinkingZone, boolean survivorsKeepGear) {
        this.extracts = extracts;
        this.shrinkingZone = shrinkingZone;
        this.survivorsKeepGear = survivorsKeepGear;
    }

    @Nullable
    public static RoundType byId(String id) {
        for (RoundType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }
}
