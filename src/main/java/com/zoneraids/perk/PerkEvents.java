package com.zoneraids.perk;

import com.zoneraids.ZoneRaids;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.PlayLevelSoundEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/** Server-side effects of perks. Levels are read from the player's own data, so nothing is cached. */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PerkEvents {
    private PerkEvents() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PerkService.apply(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PerkService.apply(player);
        }
    }

    @SubscribeEvent
    public static void onHeal(LivingHealEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            int level = PerkService.level(player, Perk.FIELD_MEDIC);
            if (level > 0) {
                event.setAmount(event.getAmount() * (1.0F + 0.08F * level));
            }
        }
    }

    @SubscribeEvent
    public static void onUseStart(LivingEntityUseItemEvent.Start event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UseAnim anim = event.getItem().getUseAnimation();
        if (anim != UseAnim.EAT && anim != UseAnim.DRINK) {
            return;
        }
        int level = PerkService.level(player, Perk.FIELD_MEDIC);
        if (level > 0) {
            event.setDuration(Math.max(1, Math.round(event.getDuration() * (1.0F - 0.1F * level))));
        }
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            int level = PerkService.level(player, Perk.FALL_TRAINING);
            if (level > 0) {
                event.setDamageMultiplier(event.getDamageMultiplier() * (1.0F - 0.12F * level));
            }
        }
    }

    /** Mobs and NPCs that look for targets notice a careful player from a shorter distance. */
    @SubscribeEvent
    public static void onVisibility(LivingEvent.LivingVisibilityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            int level = PerkService.level(player, Perk.QUIET_STEP);
            if (level > 0) {
                event.modifyVisibility(1.0 - 0.08 * level);
            }
        }
    }

    /** Footsteps that other players hear: the server plays them at the walker's exact position. */
    @SubscribeEvent
    public static void onSound(PlayLevelSoundEvent.AtPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getSound() == null
                || !event.getSound().value().getLocation().getPath().endsWith(".step")) {
            return;
        }
        Vec3 pos = event.getPosition();
        List<ServerPlayer> walkers = level.getEntitiesOfClass(ServerPlayer.class, new AABB(pos, pos).inflate(0.01));
        if (walkers.isEmpty()) {
            return;
        }
        int quiet = PerkService.level(walkers.get(0), Perk.QUIET_STEP);
        if (quiet > 0) {
            event.setNewVolume(event.getNewVolume() * (1.0F - 0.15F * quiet));
        }
    }
}
