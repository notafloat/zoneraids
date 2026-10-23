package com.zoneraids.world;

import com.zoneraids.vehicle.VehiclePoint;

import java.util.ArrayList;
import java.util.List;

/** Default pit bike points on the Cordon: parked on the roads (no trees or buildings there) next to the places worth driving between. */
public final class CordonVehicles {
    public static final String MAP_ID = CordonLayout.MAP_ID;

    private CordonVehicles() {
    }

    public static List<VehiclePoint> defaults() {
        List<VehiclePoint> list = new ArrayList<>();
        // Village track, between the crossroad and the first houses.
        list.add(ground(-92, 42, 180.0F, 0.6));
        // Farm track.
        list.add(ground(-95, 6, 0.0F, 0.5));
        // Main road between the railway crossing and the gas station.
        list.add(ground((int) Math.round(CordonTerrain.mainRoadX(-26)) - 2, -26, 180.0F, 0.6));
        // Main road by the apartments.
        list.add(ground((int) Math.round(CordonTerrain.mainRoadX(-100)) + 2, -100, 0.0F, 0.5));
        // Factory track, south of the railway.
        list.add(ground(110, -24, 180.0F, 0.5));
        // Swamp hut track.
        list.add(ground(115, 52, 0.0F, 0.4));
        // Main road north of the checkpoint.
        list.add(ground((int) Math.round(CordonTerrain.mainRoadX(110)) + 2, 110, 180.0F, 0.6));
        return list;
    }

    private static VehiclePoint ground(int x, int z, float yaw, double chance) {
        return new VehiclePoint(x, VehiclePoint.GROUND, z, yaw, chance);
    }
}
