package com.zoneraids.lean;

import com.zoneraids.ZoneRaids;
import com.zoneraids.network.LeanSyncPacket;
import com.zoneraids.network.RaidNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays leaning (TACtical Movement's lean keys) to the players who can see the leaning player.
 *
 * TACtical Movement tilts the leaning player's camera, but its body animation is only a 12.5 degree torso turn
 * that other players could not make out (and that competes with TaCZ's own third-person gun pose). The client
 * tells the server when the lean keys go down or up, and every client draws the whole body tilted (see
 * LeanClient). Hitboxes are not moved.
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class Lean {
    private static final Map<UUID, Integer> LEANING = new ConcurrentHashMap<>();

    private Lean() {
    }

    public static void set(ServerPlayer player, int direction) {
        direction = Mth.clamp(direction, -1, 1);
        Integer old = direction == 0 ? LEANING.remove(player.getUUID()) : LEANING.put(player.getUUID(), direction);
        if ((old == null ? 0 : old) != direction) {
            RaidNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> player),
                    new LeanSyncPacket(player.getId(), direction));
        }
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer target && event.getEntity() instanceof ServerPlayer watcher) {
            Integer direction = LEANING.get(target.getUUID());
            if (direction != null) {
                RaidNetwork.sendTo(watcher, new LeanSyncPacket(target.getId(), direction));
            }
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            set(player, 0);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        LEANING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LEANING.remove(event.getEntity().getUUID());
    }
}
