package com.zoneraids.raid;

import com.zoneraids.data.EnemyPoint;
import com.zoneraids.data.ExtractPoint;
import com.zoneraids.data.RaidMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One running session on one map: up to three rounds with intermissions in the hub between them.
 * Holds player UUIDs only, never entity references, so a session cannot keep unloaded players or
 * levels alive.
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
        int outsideTicks;
        /** How long the player has been offline in this round; see {@code offlineMiaSeconds}. */
        int offlineTicks;

        Participant(UUID id, String name, List<ExtractPoint> extracts) {
            this.id = id;
            this.name = name;
            this.extracts = extracts;
        }
    }

    final UUID id = UUID.randomUUID();
    final RaidMap map;
    int totalTicks;
    int ticksLeft;
    /** Players on the map in the current round. */
    final Map<UUID, Participant> participants = new LinkedHashMap<>();
    /** Enemy points as they were when the raid started; a set bit means that point already had its chance to spawn. */
    final List<EnemyPoint> enemyPoints;
    final BitSet enemyPointsDone = new BitSet();
    /** Points whose chance roll already happened (a passed roll may still be waiting for room to spawn). */
    final BitSet enemyPointsRolled = new BitSet();
    /** Live enemies of this raid (UUIDs only), and the subset that are armed NPCs. */
    final Set<UUID> enemies = new HashSet<>();
    final Set<UUID> npcs = new HashSet<>();
    /** Per anomaly of the map: the artifact still waiting to appear in it, or null. */
    final Item[] pendingArtifacts;
    /** Per anomaly: the artifact item entity once it has appeared. */
    final UUID[] artifactEntities;

    final int rounds;
    int round;
    RoundType type = RoundType.EXTRACTION;
    boolean intermission;
    /** Session members who pressed "ready" during the current break. */
    final Set<UUID> ready = new HashSet<>();
    /** The round that starts after the current break, picked when the break begins so players know what is coming. */
    RoundType nextType = RoundType.EXTRACTION;
    /** Everyone who started the session, with the group they queued in; kept until the session ends. */
    final Map<UUID, String> roster = new LinkedHashMap<>();
    final List<List<UUID>> groups = new ArrayList<>();
    final Map<UUID, Integer> groupOf = new HashMap<>();
    int roundStartSize;
    /** Extracts open in the current round, for players who join it late. */
    List<ExtractPoint> openExtracts = List.of();
    /** Players who were on the map in the current round; nobody joins the same round twice. */
    final Set<UUID> playedThisRound = new HashSet<>();

    // Shrinking zone (emission, deathmatch): a circle around (zoneX, zoneZ).
    double zoneX;
    double zoneZ;
    double zoneStartRadius;
    double zoneEndRadius;

    // Supply drop.
    @Nullable
    BlockPos drop;
    int dropGroup = -1;
    int dropTicks;
    boolean dropTaken;

    // Documents.
    @Nullable
    UUID docsHolder;
    double docsX;
    double docsZ;
    boolean docsKnown;
    int docsLostTicks;

    RaidSession(RaidMap map, int rounds) {
        this.map = map;
        this.rounds = rounds;
        this.enemyPoints = List.copyOf(map.enemies);
        this.totalTicks = map.durationSeconds * 20;
        this.ticksLeft = totalTicks;
        this.pendingArtifacts = new Item[map.anomalies.size()];
        this.artifactEntities = new UUID[map.anomalies.size()];
    }

    public int round() {
        return round;
    }

    public int rounds() {
        return rounds;
    }

    public RoundType type() {
        return type;
    }

    public boolean inIntermission() {
        return intermission;
    }

    /** Current safe zone radius: full size for the first fifth of the round, shrinking until four fifths. */
    double zoneRadius() {
        double elapsed = 1.0 - ticksLeft / (double) Math.max(1, totalTicks);
        double t = Math.max(0.0, Math.min(1.0, (elapsed - 0.2) / 0.6));
        return zoneStartRadius + (zoneEndRadius - zoneStartRadius) * t;
    }

    public UUID id() {
        return id;
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
