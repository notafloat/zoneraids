package com.zoneraids.raid;

import com.zoneraids.data.ExtractPoint;
import com.zoneraids.data.RaidMap;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One running raid on one map. Holds player UUIDs only, never entity references, so a session
 * cannot keep unloaded players or levels alive.
 */
public final class RaidSession {
    public static final class Participant {
        final UUID id;
        final String name;
        final List<ExtractPoint> extracts;
        @Nullable
        ExtractPoint extracting;
        int extractTicks;
        int hudTicks;

        Participant(UUID id, String name, List<ExtractPoint> extracts) {
            this.id = id;
            this.name = name;
            this.extracts = extracts;
        }
    }

    final UUID id = UUID.randomUUID();
    final RaidMap map;
    final int totalTicks;
    int ticksLeft;
    final Map<UUID, Participant> participants = new LinkedHashMap<>();

    RaidSession(RaidMap map) {
        this.map = map;
        this.totalTicks = map.durationSeconds * 20;
        this.ticksLeft = totalTicks;
    }

    public RaidMap map() {
        return map;
    }

    public int secondsLeft() {
        return Math.max(0, ticksLeft / 20);
    }

    public int size() {
        return participants.size();
    }
}
