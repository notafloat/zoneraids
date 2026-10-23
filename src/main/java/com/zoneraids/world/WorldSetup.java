package com.zoneraids.world;

import com.zoneraids.RaidConfig;
import com.zoneraids.data.AnomalyPoint;
import com.zoneraids.data.EnemyPoint;
import com.zoneraids.data.ExtractPoint;
import com.zoneraids.data.HubPoint;
import com.zoneraids.data.RaidMap;
import com.zoneraids.data.RaidWorldData;
import com.zoneraids.data.SpawnPoint;
import com.zoneraids.raid.RaidManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * Makes a fresh world playable without admin work: builds the hub and the Cordon map once, then
 * registers the map (spawns, extracts, bounds) and the hub point.
 */
public final class WorldSetup {
    public static final String HUB = "hub";
    public static final String CORDON = CordonLayout.MAP_ID;
    /** Bump a version to make existing worlds rebuild that layout on their next start. */
    private static final String HUB_LAYOUT = "hub@1";
    private static final String CORDON_LAYOUT = "cordon@2";
    /** Enemy points are added to an already built Cordon without rebuilding it. Bump to reset them. */
    private static final String CORDON_ENEMIES = "cordon-enemies@1";
    /** Weapon crates are added to an already built Cordon without rebuilding it. */
    private static final String CORDON_WEAPONS = "cordon-weapons@1";

    private WorldSetup() {
    }

    public static void onServerStarted(MinecraftServer server) {
        if (!RaidConfig.AUTO_SETUP.get()) {
            return;
        }
        RaidWorldData data = RaidWorldData.get(server);
        if (!data.isBuilt(HUB_LAYOUT)) {
            buildHub();
        }
        if (!data.isBuilt(CORDON_LAYOUT)) {
            buildCordon(server);
        } else {
            if (!data.isBuilt(CORDON_ENEMIES)) {
                RaidMap map = data.getMap(CORDON);
                if (map != null) {
                    setCordonEnemies(map, new CordonLayout());
                    data.markBuilt(CORDON_ENEMIES);
                }
            }
            if (!data.isBuilt(CORDON_WEAPONS)) {
                WorldBuilder.enqueue(new WorldBuilder.Job(CORDON, ZoneDimensions.CORDON, CordonBuilder.weaponCrateSteps(),
                        s -> RaidWorldData.get(s).markBuilt(CORDON_WEAPONS)));
            }
        }
    }

    /** @return false if that layout is already being built */
    public static boolean buildHub() {
        if (WorldBuilder.isBuilding(HUB)) {
            return false;
        }
        WorldBuilder.enqueue(new WorldBuilder.Job(HUB, ZoneDimensions.HUB, HubBuilder.steps(), server -> {
            RaidWorldData data = RaidWorldData.get(server);
            data.setHub(new HubPoint(ZoneDimensions.HUB, HubBuilder.SPAWN_X, HubBuilder.SPAWN_Y, HubBuilder.SPAWN_Z, HubBuilder.SPAWN_YAW, 0.0F));
            data.markBuilt(HUB_LAYOUT);
            RaidManager manager = RaidManager.get();
            if (manager != null && RaidConfig.FORCE_HUB_ON_LOGIN.get()) {
                server.getPlayerList().getPlayers().forEach(manager::sendToHubIfOutside);
            }
        }));
        return true;
    }

    /** @return false if the map is already being built or a raid is running on it */
    public static boolean buildCordon(MinecraftServer server) {
        RaidManager manager = RaidManager.get();
        if (WorldBuilder.isBuilding(CORDON) || (manager != null && manager.sessionOnMap(CORDON) != null)) {
            return false;
        }
        RaidMap existing = RaidWorldData.get(server).getMap(CORDON);
        boolean wasEnabled = existing == null || existing.enabled;
        if (existing != null) {
            // Nobody can queue for the map while it is being rebuilt.
            existing.enabled = false;
            RaidWorldData.get(server).setDirty();
        }
        CordonBuilder builder = new CordonBuilder();
        CordonLayout layout = builder.layout();
        WorldBuilder.enqueue(new WorldBuilder.Job(CORDON, ZoneDimensions.CORDON, builder.steps(), s -> {
            RaidWorldData data = RaidWorldData.get(s);
            RaidMap map = data.getMap(CORDON);
            if (map == null) {
                map = new RaidMap(CORDON, ZoneDimensions.CORDON);
                map.displayName = "Кордон";
                map.durationSeconds = 25 * 60;
                map.minPlayers = 1;
                map.maxPlayers = 12;
                map.extractsPerPlayer = 2;
            }
            map.dimension = ZoneDimensions.CORDON;
            map.enabled = wasEnabled;
            map.spawns.clear();
            for (CordonLayout.Spawn spawn : layout.spawns) {
                int y = layout.terrain.top(spawn.x(), spawn.z()) + 1;
                map.spawns.add(new SpawnPoint(new BlockPos(spawn.x(), y, spawn.z()), spawn.yaw()));
            }
            map.extracts.clear();
            for (CordonLayout.Extract e : layout.extracts) {
                map.extracts.add(new ExtractPoint(e.name(), new BlockPos(e.x(), e.y(), e.z()), e.radius(), e.chance()));
            }
            map.anomalies.clear();
            for (CordonLayout.Anomaly a : layout.anomalies) {
                AnomalyPoint.Type type = AnomalyPoint.Type.valueOf(a.kind().name());
                map.anomalies.add(new AnomalyPoint(type, new BlockPos(a.x(), a.y(), a.z()), a.radius()));
            }
            map.setBounds(CordonLayout.minBound(), CordonLayout.minBound(), CordonLayout.maxBound(), CordonLayout.maxBound());
            setCordonEnemies(map, layout);
            data.putMap(map);
            data.markBuilt(CORDON_LAYOUT);
            data.markBuilt(CORDON_ENEMIES);
            data.markBuilt(CORDON_WEAPONS);
        }));
        return true;
    }

    private static void setCordonEnemies(RaidMap map, CordonLayout layout) {
        map.enemies.clear();
        for (CordonLayout.Enemy e : layout.enemies) {
            map.enemies.add(new EnemyPoint(e.kind(), new BlockPos(e.x(), e.y(), e.z()), e.min(), e.max(), e.chance(), e.trigger()));
        }
    }
}
