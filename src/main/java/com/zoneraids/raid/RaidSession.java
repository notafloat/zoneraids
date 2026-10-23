package com.zoneraids.raid;

import com.zoneraids.data.EnemyPoint;
import com.zoneraids.data.ExtractPoint;
import com.zoneraids.data.RaidMap;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** Enemy points as they were when the raid started; a set bit means that point already had its chance to spawn. */
    final List<EnemyPoint> enemyPoints;
    final BitSet enemyPointsDone = new BitSet();
    /** Live enemies of this raid (UUIDs only), and the subset that are armed NPCs. */
    final Set<UUID> enemies = new HashSet<>();
    final Set<UUID> npcs = new HashSet<>();
    /** Per anomaly of the map: the artifact still waiting to appear in it, or null. */
    final Item[] pendingArtifacts;
    /** Per anomaly: the artifact item entity once it has appeared. */
    final UUID[] artifactEntities;

    RaidSession(RaidMap map) {
        this.map = map;
        this.enemyPoints = List.copyOf(map.enemies);
        this.totalTicks = map.durationSeconds * 20;
        this.ticksLeft = totalTicks;
        this.pendingArtifacts = new Item[map.anomalies.size()];
        this.artifactEntities = new UUID[map.anomalies.size()];
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

    public int enemyCount() {
        return enemies.size();
    }
}
