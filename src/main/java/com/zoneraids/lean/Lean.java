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
 * Relays leaning to the players who can see the leaning player. The client tells the server how far it leans
 * (in percent, negative to the left), and every client draws the whole body tilted (see LeanClient).
 * Hitboxes are not moved.
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class Lean {
    private static final Map<UUID, Integer> LEANING = new ConcurrentHashMap<>();

    private Lean() {
    }

    public static void set(ServerPlayer player, int amount) {
        amount = Mth.clamp(amount, -100, 100);
        Integer old = amount == 0 ? LEANING.remove(player.getUUID()) : LEANING.put(player.getUUID(), amount);
        if ((old == null ? 0 : old) != amount) {
            RaidNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> player),
                    new LeanSyncPacket(player.getId(), amount));
        }
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer target && event.getEntity() instanceof ServerPlayer watcher) {
            Integer amount = LEANING.get(target.getUUID());
            if (amount != null) {
                RaidNetwork.sendTo(watcher, new LeanSyncPacket(target.getId(), amount));
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
