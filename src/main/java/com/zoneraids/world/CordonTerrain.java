package com.zoneraids.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure height and surface functions of the "Cordon" map. Everything here depends only on (x, z),
 * so terrain chunks can be generated in any order and buildings know the ground height without
 * reading the world.
 */
final class CordonTerrain {
    static final long SEED = 0x5A0EC0D0L;
    /** Perimeter wall line; the raid area is strictly inside it. */
    static final int WALL = 157;
    /** Decorative terrain is generated out to this distance so the map does not end at the wall. */
    static final int OUTER = 191;
    static final int FLOOR = 57;
    static final int WATER = 62;
    static final int RAIL_Z = -40;
    static final int RAIL_Y = 66;

    static final int HILL_X = -105;
    static final int HILL_Z = -100;
    static final int SWAMP_X = 115;
    static final int SWAMP_Z = 108;
    static final double SWAMP_R = 46;
    static final int ANOMALY_X = 38;
    static final int ANOMALY_Z = 86;
    static final double ANOMALY_R = 21;

    enum Surface { NORMAL, ASPHALT, ASPHALT_EDGE, DIRT_ROAD, RAIL_BED, RIVER_BED, BANK, SWAMP, ANOMALY }

    enum RoadKind { ASPHALT, DIRT }

    record Pad(int cx, int cz, int hx, int hz, int y, int margin) {
        double weight(int x, int z) {
            double dx = Math.max(0, Math.abs(x - cx) - hx);
            double dz = Math.max(0, Math.abs(z - cz) - hz);
            double d = Math.sqrt(dx * dx + dz * dz);
            return d >= margin ? 0 : Noise.smoothstep(margin, 0, d);
        }
    }

    record Rect(int x1, int z1, int x2, int z2) {
        boolean contains(int x, int z, int margin) {
            return x >= x1 - margin && x <= x2 + margin && z >= z1 - margin && z <= z2 + margin;
        }
    }

    static final class Road {
        final RoadKind kind;
        final double half;
        final double[] xs;
        final double[] zs;
        final double minX;
        final double maxX;
        final double minZ;
        final double maxZ;

        Road(RoadKind kind, double half, List<double[]> points) {
            this.kind = kind;
            this.half = half;
            xs = new double[points.size()];
            zs = new double[points.size()];
            double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z0 = Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                xs[i] = points.get(i)[0];
                zs[i] = points.get(i)[1];
                x0 = Math.min(x0, xs[i]);
                x1 = Math.max(x1, xs[i]);
                z0 = Math.min(z0, zs[i]);
                z1 = Math.max(z1, zs[i]);
            }
            minX = x0;
            maxX = x1;
            minZ = z0;
            maxZ = z1;
        }

        double distance(double x, double z) {
            double reach = half + 6;
            if (x < minX - reach || x > maxX + reach || z < minZ - reach || z > maxZ + reach) {
                return Double.MAX_VALUE;
            }
            double best = Double.MAX_VALUE;
            for (int i = 0; i + 1 < xs.length; i++) {
                double ax = xs[i], az = zs[i], bx = xs[i + 1], bz = zs[i + 1];
                double dx = bx - ax, dz = bz - az;
                double len = dx * dx + dz * dz;
                double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / len));
                double px = ax + dx * t - x, pz = az + dz * t - z;
                best = Math.min(best, px * px + pz * pz);
            }
            return Math.sqrt(best);
        }
    }

    /** Mutable result of {@link #sample}, reused to avoid an allocation per column. */
    static final class Column {
        int top;
        Surface surface;
        double roadDistance;
    }

    private final Noise noise = new Noise(SEED);
    private final Noise detail = new Noise(SEED * 31 + 7);
    final List<Pad> pads = new ArrayList<>();
    final List<Road> roads = new ArrayList<>();
    /** Footprints that scattered trees and rocks must avoid. */
    final List<Rect> reserved = new ArrayList<>();

    CordonTerrain() {
        // Main asphalt road, south gate to north gate.
        List<double[]> main = new ArrayList<>();
        for (int z = -OUTER; z <= OUTER; z += 6) {
            main.add(new double[]{mainRoadX(z), z});
        }
        roads.add(new Road(RoadKind.ASPHALT, 3.5, main));
        // East-west dirt road through the crossroads and over the river bridge.
        List<double[]> cross = new ArrayList<>();
        for (int x = -150; x <= 150; x += 6) {
            cross.add(new double[]{x, crossRoadZ(x)});
        }
        roads.add(new Road(RoadKind.DIRT, 2.5, cross));
        dirt(2.0, -92, crossRoadZ(-92), -92, 84);                // village
        dirt(2.0, -95, crossRoadZ(-95), -95, 0);                 // farm
        dirt(2.0, 110, crossRoadZ(110), 110, -78);               // factory, across the railway
        dirt(1.5, mainRoadX(-95), -95, 10, -95);                 // apartments
        dirt(1.5, mainRoadX(-104), -104, -95, -104);             // radio hill
        dirt(1.5, -112, -110, -128, -126);                       // helipad
        dirt(1.5, 115, crossRoadZ(115), 115, 76);                // swamp hut
    }

    private void dirt(double half, double x1, double z1, double x2, double z2) {
        List<double[]> pts = new ArrayList<>();
        int n = (int) Math.max(2, Math.ceil(Math.hypot(x2 - x1, z2 - z1) / 6));
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            // A slight wobble so paths do not look ruler-straight.
            double wobble = Math.sin(t * Math.PI) * 2.0 * Math.sin(t * 9.0 + x1);
            double nx = -(z2 - z1), nz = x2 - x1;
            double nl = Math.hypot(nx, nz);
            pts.add(new double[]{x1 + (x2 - x1) * t + nx / nl * wobble, z1 + (z2 - z1) * t + nz / nl * wobble});
        }
        roads.add(new Road(RoadKind.DIRT, half, pts));
    }

    static double mainRoadX(double z) {
        return -8 + 10 * Math.sin(z / 55.0);
    }

    static double crossRoadZ(double x) {
        return 22 + 6 * Math.sin(x / 45.0);
    }

    static double riverX(double z) {
        return 70 + 14 * Math.sin(z / 38.0) + 6 * Math.sin(z / 17.0 + 1);
    }

    /** Adds a flattened building pad at the natural ground height of its centre and returns that height. */
    int pad(int cx, int cz, int hx, int hz, int margin) {
        int y = Math.max(WATER + 1, (int) Math.round(rawHeight(cx, cz)));
        return pad(cx, cz, hx, hz, margin, y);
    }

    int pad(int cx, int cz, int hx, int hz, int margin, int y) {
        pads.add(new Pad(cx, cz, hx, hz, y, margin));
        reserved.add(new Rect(cx - hx, cz - hz, cx + hx, cz + hz));
        return y;
    }

    void reserve(int x1, int z1, int x2, int z2) {
        reserved.add(new Rect(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2)));
    }

    boolean isReserved(int x, int z, int margin) {
        for (Rect rect : reserved) {
            if (rect.contains(x, z, margin)) {
                return true;
            }
        }
        return false;
    }

    double hash(int x, int z) {
        return detail.hash(x, z);
    }

    double patch(int x, int z, double scale) {
        return detail.fbm(x + 500, z - 300, scale, 2);
    }

    /** Ground height before roads, pads, railway and river. */
    double rawHeight(int x, int z) {
        double n = noise.fbm(x, z, 70, 3);
        double h = 65.0 + n * 3.0 + detail.fbm(x, z, 18, 2);
        double edge = Math.max(Math.abs(x + 0.5), Math.abs(z + 0.5));
        if (edge > 118) {
            h += (edge - 118) / 40.0 * 10.0 * (0.75 + 0.25 * n);
        }
        double hd = Math.hypot(x - HILL_X, z - HILL_Z);
        h += 13.0 * Math.exp(-(hd * hd) / (30.0 * 30.0));
        double sd = Math.hypot(x - SWAMP_X, z - SWAMP_Z) + detail.value(x / 9.0, z / 9.0) * 4;
        double sw = Noise.smoothstep(SWAMP_R, SWAMP_R * 0.45, sd);
        if (sw > 0) {
            h += (61.3 + n * 1.2 - h) * sw;
        }
        return h;
    }

    void sample(int x, int z, Column out) {
        double h = rawHeight(x, z);
        double edge = Math.max(Math.abs(x + 0.5), Math.abs(z + 0.5));
        Surface surface = Surface.NORMAL;
        double sd = Math.hypot(x - SWAMP_X, z - SWAMP_Z) + detail.value(x / 9.0, z / 9.0) * 4;
        if (sd < SWAMP_R * 0.8) {
            surface = Surface.SWAMP;
        }
        double ad = Math.hypot(x - ANOMALY_X, z - ANOMALY_Z) + detail.value(x / 7.0, z / 7.0) * 3;
        if (ad < ANOMALY_R) {
            surface = Surface.ANOMALY;
        }

        double bestEdge = Double.MAX_VALUE;
        Road nearestRoad = null;
        for (Road road : roads) {
            double d = road.distance(x, z);
            if (d < road.half + 4) {
                double level = 65 + 1.5 * noise.fbm(x, z, 120, 2);
                h += (level - h) * Noise.smoothstep(road.half + 4, road.half + 0.5, d);
            }
            if (d - road.half < bestEdge) {
                bestEdge = d - road.half;
                nearestRoad = road;
            }
        }
        out.roadDistance = bestEdge;
        if (nearestRoad != null && bestEdge <= 0) {
            if (nearestRoad.kind == RoadKind.ASPHALT) {
                surface = bestEdge > -1 ? Surface.ASPHALT_EDGE : Surface.ASPHALT;
            } else {
                surface = Surface.DIRT_ROAD;
            }
        }

        for (Pad pad : pads) {
            double w = pad.weight(x, z);
            if (w > 0) {
                h += (pad.y() - h) * w;
            }
        }

        double rd = Math.abs(z - RAIL_Z);
        if (rd < 7 && Math.abs(x) <= WALL + 3) {
            h += (RAIL_Y - h) * Noise.smoothstep(7, 2, rd);
            if (rd <= 1) {
                surface = Surface.RAIL_BED;
            }
        }

        boolean nearEdge = edge > OUTER - 5;
        double rx = Math.abs(x - riverX(z));
        if (!nearEdge && rx < 11) {
            if (rx < 4.5) {
                h = 58.6 + rx * 0.35 + detail.value(x / 3.0, z / 3.0) * 0.4;
                surface = Surface.RIVER_BED;
            } else {
                double t = Noise.smooth((rx - 4.5) / 6.5);
                h = 61.5 + (Math.max(h, 63.2) - 61.5) * t;
                if (surface == Surface.NORMAL || surface == Surface.SWAMP) {
                    surface = Surface.BANK;
                }
            }
        }
        if (nearEdge) {
            h = Math.max(h, WATER + 1);
        }
        out.top = (int) Math.floor(h);
        out.surface = surface;
    }

    /** Top solid block at (x, z). */
    int top(int x, int z) {
        Column c = new Column();
        sample(x, z, c);
        return c.top;
    }
}
