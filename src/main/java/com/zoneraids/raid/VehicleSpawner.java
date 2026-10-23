package com.zoneraids.raid;

import com.zoneraids.vehicle.PitBike;
import com.zoneraids.vehicle.VehicleData;
import com.zoneraids.vehicle.VehiclePoint;
import com.zoneraids.vehicle.Vehicles;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Puts pit bikes on a map while a raid runs there and takes them away afterwards. It only watches
 * {@link RaidManager} (once a second) and never changes it, so raid code needs no hooks for vehicles.
 *
 * <ul>
 *     <li>Raid start: each vehicle point rolls its chance once. A bike appears when its chunk is
 *     loaded by a nearby player, so nothing is forced to load and unvisited bikes cost nothing.</li>
 *     <li>Raid end: loaded bikes of that raid are removed. Bikes in unloaded chunks (or taken offline
 *     by a rider) are dropped when they would load again, because their raid id is no longer live.</li>
 * </ul>
 */
public final class VehicleSpawner {
    private static final class State {
        final UUID session;
        final ResourceKey<Level> dimension;
        final List<VehiclePoint> pending;

        State(UUID session, ResourceKey<Level> dimension, List<VehiclePoint> pending) {
            this.session = session;
            this.dimension = dimension;
            this.pending = pending;
        }
    }

    /** Map id to the raid currently served on it. */
    private static final Map<String, State> states = new HashMap<>();
    private static final Set<UUID> liveSessions = new HashSet<>();
    private static final Random random = new Random();
    private static int ticks;

    private VehicleSpawner() {
    }

    public static void tick(MinecraftServer server) {
        if (++ticks % 20 != 0) {
            return;
        }
        RaidManager manager = RaidManager.get();
        if (manager == null) {
            return;
        }
        VehicleData data = VehicleData.get(server);
        Set<String> mapIds = new HashSet<>(data.mapIds());
        mapIds.addAll(states.keySet());
        for (String mapId : mapIds) {
            RaidSession session = manager.sessionOnMap(mapId);
            State state = states.get(mapId);
            if (state != null && (session == null || !state.session.equals(session.id))) {
                end(server, state);
                states.remove(mapId);
                state = null;
            }
            if (session != null && state == null) {
                state = begin(session, data.points(mapId));
                states.put(mapId, state);
            }
            if (state != null && !state.pending.isEmpty()) {
                ServerLevel level = server.getLevel(state.dimension);
                if (level != null) {
                    spawnLoaded(level, state);
                }
            }
        }
    }

    /** True if the bike belongs to a raid that is over (or to one from before a restart). */
    public static boolean isStale(Entity entity) {
        return entity instanceof PitBike bike && bike.raidSession() != null && !liveSessions.contains(bike.raidSession());
    }

    public static void clear() {
        states.clear();
        liveSessions.clear();
        ticks = 0;
    }

    private static State begin(RaidSession session, List<VehiclePoint> points) {
        List<VehiclePoint> chosen = new ArrayList<>();
        if (Vehicles.SPAWN_IN_RAIDS.get()) {
            for (VehiclePoint point : points) {
                if (random.nextDouble() < point.chance()) {
                    chosen.add(point);
                }
            }
        }
        liveSessions.add(session.id);
        return new State(session.id, session.map.dimension, chosen);
    }

    private static void spawnLoaded(ServerLevel level, State state) {
        for (Iterator<VehiclePoint> it = state.pending.iterator(); it.hasNext(); ) {
            VehiclePoint point = it.next();
            if (!level.isPositionEntityTicking(new BlockPos(point.x(), level.getSeaLevel(), point.z()))) {
                continue;
            }
            it.remove();
            int y = point.y() == VehiclePoint.GROUND
                    ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, point.x(), point.z())
                    : point.y();
            spawn(level, state.session, point, y);
        }
    }

    private static void spawn(ServerLevel level, UUID session, VehiclePoint point, int y) {
        for (int dy = 0; dy <= 3; dy++) {
            PitBike bike = new PitBike(level, point.x() + 0.5, y + dy, point.z() + 0.5, point.yaw());
            if (level.noCollision(bike, bike.getBoundingBox())) {
                bike.setRaidSession(session);
                level.addFreshEntity(bike);
                return;
            }
        }
    }

    private static void end(MinecraftServer server, State state) {
        liveSessions.remove(state.session);
        ServerLevel level = server.getLevel(state.dimension);
        if (level == null) {
            return;
        }
        List<PitBike> bikes = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof PitBike bike && state.session.equals(bike.raidSession())) {
                bikes.add(bike);
            }
        }
        for (PitBike bike : bikes) {
            bike.ejectPassengers();
            bike.discard();
        }
    }
}
