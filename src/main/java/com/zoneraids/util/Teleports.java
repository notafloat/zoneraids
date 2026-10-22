package com.zoneraids.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Teleports performed by the mod. Cross-dimension travel of players in a raid is otherwise blocked,
 * so our own transfers are flagged while they run (the server thread is single-threaded).
 */
public final class Teleports {
    private static boolean authorized;

    private Teleports() {
    }

    public static boolean isAuthorized() {
        return authorized;
    }

    public static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        player.stopRiding();
        authorized = true;
        try {
            player.teleportTo(level, x, y, z, yaw, pitch);
        } finally {
            authorized = false;
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
    }
}
