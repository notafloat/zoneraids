package com.zoneraids.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Where everything on the "Cordon" map goes. Pure data (no Minecraft types) so the layout can be
 * checked and previewed outside the game.
 */
final class CordonLayout {
    static final String MAP_ID = "cordon";

    enum Kind {
        HOUSE, VILLAGE_CENTER, FARM, CHECKPOINT, SOUTH_GATE, NORTH_GATE, GAS_STATION, APARTMENTS, FACTORY,
        RADIO_HILL, HELIPAD, RAILWAY, ROAD_BRIDGE, SWAMP_HUT, ANOMALY_FIELD, CAR, POWER_LINE, WALL, GRAVES
    }

    /** A thing to build. {@code y} is the ground height under it, {@code turns} quarter turns clockwise. */
    record Site(Kind kind, int x, int y, int z, int turns, int size) {
    }

    record Spawn(int x, int z, float yaw) {
    }

    /** {@code y} is the zone centre height; the marker post stands at (markerX, markerZ). */
    record Extract(String name, int x, int y, int z, double radius, double chance, int markerX, int markerZ) {
    }

    /** An enemy group; {@code y} is where they stand (spawning searches a few blocks up and down). */
    record Enemy(String kind, int x, int y, int z, int min, int max, double chance, int trigger) {
    }

    final CordonTerrain terrain = new CordonTerrain();
    final List<Site> sites = new ArrayList<>();
    final List<Spawn> spawns = new ArrayList<>();
    final List<Extract> extracts = new ArrayList<>();
    final List<Enemy> enemies = new ArrayList<>();
    final int bridgeX;
    final int bridgeZ;
    final int railBridgeX;

    CordonLayout() {
        CordonTerrain t = terrain;

        // Village of newcomers (south-west).
        int[][] houses = {
                {-114, 60, 0, 3}, {-101, 57, 0, 4}, {-80, 61, 1, 3}, {-120, 82, 1, 4},
                {-66, 82, 3, 3}, {-106, 100, 2, 3}, {-88, 103, 2, 4}, {-70, 104, 2, 3}
        };
        for (int[] h : houses) {
            boolean turned = (h[2] & 1) == 1;
            int hx = (turned ? 3 : h[3]) + 1;
            int hz = (turned ? h[3] : 3) + 1;
            sites.add(new Site(Kind.HOUSE, h[0], t.pad(h[0], h[1], hx, hz, 4), h[1], h[2], h[3]));
        }
        sites.add(new Site(Kind.VILLAGE_CENTER, -94, t.pad(-94, 79, 6, 6, 5), 79, 0, 0));
        sites.add(new Site(Kind.GRAVES, -124, t.pad(-124, 112, 5, 4, 4), 112, 0, 0));

        sites.add(new Site(Kind.FARM, -95, t.pad(-95, -12, 22, 12, 8), -12, 0, 0));

        int cpX = (int) Math.round(CordonTerrain.mainRoadX(132));
        sites.add(new Site(Kind.CHECKPOINT, cpX, t.pad(cpX, 132, 18, 9, 8), 132, 0, 0));
        int sgX = (int) Math.round(CordonTerrain.mainRoadX(152));
        sites.add(new Site(Kind.SOUTH_GATE, sgX, t.pad(sgX, 152, 8, 4, 6), 152, 0, 0));
        int ngX = (int) Math.round(CordonTerrain.mainRoadX(-152));
        sites.add(new Site(Kind.NORTH_GATE, ngX, t.pad(ngX, -152, 9, 4, 6), -152, 0, 0));

        sites.add(new Site(Kind.GAS_STATION, 15, t.pad(15, 6, 12, 9, 6), 6, 0, 0));
        sites.add(new Site(Kind.APARTMENTS, 25, t.pad(25, -95, 15, 7, 8), -95, 0, 0));
        sites.add(new Site(Kind.FACTORY, 118, t.pad(118, -105, 28, 25, 8), -105, 0, 0));
        sites.add(new Site(Kind.RADIO_HILL, CordonTerrain.HILL_X, t.pad(CordonTerrain.HILL_X, CordonTerrain.HILL_Z, 9, 9, 12),
                CordonTerrain.HILL_Z, 0, 0));
        sites.add(new Site(Kind.HELIPAD, -134, t.pad(-134, -131, 7, 7, 8), -131, 0, 0));
        sites.add(new Site(Kind.SWAMP_HUT, 115, t.pad(115, 80, 4, 3, 4), 80, 0, 0));
        t.reserve(113, 84, 117, 101);

        sites.add(new Site(Kind.ANOMALY_FIELD, CordonTerrain.ANOMALY_X, 0, CordonTerrain.ANOMALY_Z, 0, 0));

        // Railway: siding with wagons, tunnel portal in the east, dead end in the west.
        t.pad(-70, -36, 24, 2, 3, CordonTerrain.RAIL_Y);
        t.reserve(-96, -38, -44, -33);
        t.reserve(144, -47, CordonTerrain.WALL, -33);
        t.reserve(-CordonTerrain.WALL, -47, -144, -33);
        t.reserve(-CordonTerrain.WALL, -42, CordonTerrain.WALL, -38);
        sites.add(new Site(Kind.RAILWAY, 0, CordonTerrain.RAIL_Y, CordonTerrain.RAIL_Z, 0, 0));

        // Road bridge where the east-west road meets the river.
        int bx = 80;
        int bz = 22;
        for (int i = 0; i < 8; i++) {
            bz = (int) Math.round(CordonTerrain.crossRoadZ(bx));
            bx = (int) Math.round(CordonTerrain.riverX(bz));
        }
        bridgeX = bx;
        bridgeZ = bz;
        t.reserve(bx - 12, bz - 4, bx + 12, bz + 4);
        sites.add(new Site(Kind.ROAD_BRIDGE, bx, 0, bz, 0, 0));
        railBridgeX = (int) Math.round(CordonTerrain.riverX(CordonTerrain.RAIL_Z));

        int[][] cars = {{-125, 0}, {-70, 2}, {-14, 0}, {48, 2}, {100, 0}};
        for (int[] c : cars) {
            int z = c[0];
            int x = (int) Math.round(CordonTerrain.mainRoadX(z) + (c[1] == 0 ? 1.5 : -1.5));
            sites.add(new Site(Kind.CAR, x, t.top(x, z), z, c[1], 0));
            t.reserve(x - 2, z - 3, x + 2, z + 3);
        }
        int[][] crossCars = {{-125, 1}, {-55, 3}, {35, 1}, {128, 3}};
        for (int[] c : crossCars) {
            int x = c[0];
            int z = (int) Math.round(CordonTerrain.crossRoadZ(x) + (c[1] == 1 ? 1 : -1));
            sites.add(new Site(Kind.CAR, x, t.top(x, z), z, c[1], 0));
            t.reserve(x - 3, z - 2, x + 3, z + 2);
        }
        sites.add(new Site(Kind.POWER_LINE, 0, 0, 0, 0, 0));
        sites.add(new Site(Kind.WALL, 0, 0, 0, 0, 0));

        int[][] spawnSpots = {
                {-130, 140}, {-148, 30}, {-148, -82}, {-70, -148}, {35, -148}, {95, -149},
                {148, 8}, {148, 62}, {98, 148}, {45, 148}, {-60, 148}, {-30, 100}
        };
        for (int[] s : spawnSpots) {
            float yaw = (float) Math.toDegrees(Math.atan2(s[0], -s[1]));
            spawns.add(new Spawn(s[0], s[1], yaw));
        }

        int e1 = (int) Math.round(CordonTerrain.mainRoadX(150));
        int e2 = (int) Math.round(CordonTerrain.mainRoadX(-150));
        int rail = CordonTerrain.RAIL_Y + 1;
        int deck = CordonTerrain.WATER + 2;
        extracts.add(new Extract("КПП «Юг»", e1, t.top(e1, 150) + 1, 150, 4, 1.0, e1 + 5, 150));
        extracts.add(new Extract("Северная трасса", e2, t.top(e2, -150) + 1, -150, 4, 0.85, e2 + 5, -150));
        extracts.add(new Extract("Ж/д тоннель", 151, rail, CordonTerrain.RAIL_Z, 3, 0.7, 151, CordonTerrain.RAIL_Z + 2));
        extracts.add(new Extract("Ж/д тупик", -150, rail, CordonTerrain.RAIL_Z, 3, 0.6, -150, CordonTerrain.RAIL_Z + 2));
        extracts.add(new Extract("Вертолётная площадка", -134, t.top(-134, -131) + 1, -131, 5, 0.35, -127, -124));
        extracts.add(new Extract("Причал на болоте", 115, deck, 99, 3, 0.5, 116, 100));
        extracts.add(new Extract("Задний двор завода", 135, t.top(135, -149) + 1, -149, 3, 0.5, 135, -152));
        extracts.add(new Extract("Дыра в заборе", -151, t.top(-151, 96) + 1, 96, 3, 0.6, -153, 96));

        addEnemies();
    }

    /**
     * Enemy groups, away from the player spawns at the edges so nobody is jumped on arrival.
     * Rookie village stays quiet; bandits hold the buildings, the military the checkpoint and the hill,
     * mutants the fields, the swamp, the anomalies and the lab.
     */
    private void addEnemies() {
        Site farm = site(Kind.FARM);
        Site gas = site(Kind.GAS_STATION);
        Site flats = site(Kind.APARTMENTS);
        Site factory = site(Kind.FACTORY);
        Site checkpoint = site(Kind.CHECKPOINT);
        Site hill = site(Kind.RADIO_HILL);
        Site swamp = site(Kind.SWAMP_HUT);

        enemies.add(new Enemy("bandit", gas.x(), gas.y() + 1, gas.z(), 2, 3, 0.75, 40));
        enemies.add(new Enemy("bandit", flats.x(), flats.y() + 1, flats.z(), 2, 3, 0.85, 36));
        enemies.add(new Enemy("bandit", factory.x(), factory.y() + 1, factory.z(), 2, 4, 0.9, 40));
        enemies.add(new Enemy("military", checkpoint.x(), checkpoint.y() + 1, checkpoint.z(), 2, 3, 0.6, 36));
        enemies.add(new Enemy("military", hill.x() + 6, hill.y() + 1, hill.z() - 2, 2, 2, 0.6, 40));
        // Lab under the hill: 12 blocks down the shaft.
        enemies.add(new Enemy("bloodsucker", hill.x() - 5, hill.y() - 11, hill.z() + 3, 1, 1, 0.7, 14));

        enemies.add(new Enemy("flesh", farm.x() + 10, farm.y() + 1, farm.z() + 4, 2, 3, 0.85, 40));
        int[][] dogs = {{-100, 30}, {-55, -105}, {105, 35}};
        for (int[] d : dogs) {
            enemies.add(new Enemy("blind_dog", d[0], terrain.top(d[0], d[1]) + 1, d[1], 2, 4, 0.7, 40));
        }
        int ax = CordonTerrain.ANOMALY_X, az = CordonTerrain.ANOMALY_Z;
        enemies.add(new Enemy("snork", ax, terrain.top(ax, az) + 1, az, 2, 3, 0.6, 36));
        enemies.add(new Enemy("snork", swamp.x(), swamp.y() + 1, swamp.z(), 1, 2, 0.5, 32));
    }

    private Site site(Kind kind) {
        for (Site s : sites) {
            if (s.kind() == kind) {
                return s;
            }
        }
        throw new IllegalStateException("no " + kind);
    }

    /** Raid area: everything strictly inside the perimeter wall. */
    static int minBound() {
        return -CordonTerrain.WALL + 1;
    }

    static int maxBound() {
        return CordonTerrain.WALL - 1;
    }
}
