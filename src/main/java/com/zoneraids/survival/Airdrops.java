package com.zoneraids.survival;

import com.mojang.logging.LogUtils;
import com.zoneraids.RaidConfig;
import com.zoneraids.block.HiddenStashBlockEntity;
import com.zoneraids.network.MapMarkerPacket;
import com.zoneraids.network.RaidNetwork;
import com.zoneraids.raid.EnemySpawner;
import com.zoneraids.registry.ModRegistry;
import com.zoneraids.stash.StashDrives;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.List;

/**
 * Supply drops in survival mode: now and then a military crate comes down a few hundred blocks from a random
 * player. Everyone in the overworld hears of it and gets a PDA map marker; the crate smokes so it can be found
 * from afar, and a squad of {@code airdropGuardKind} turns up once someone comes near. The crate is a hidden
 * stash with {@code airdropLootTable}, gone once emptied; after {@code airdropLifetimeMinutes} it stops smoking
 * and is no longer tracked, but stays where it is.
 */
public final class Airdrops {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int ATTEMPTS = 24;
    /** The guards turn up when a player comes this close. */
    private static final int GUARD_DISTANCE = 64;
    /** They spawn at least this far from any player. */
    private static final int GUARD_MIN_DISTANCE = 16;
    /** Smoke is sent to players this close; long-distance particles are drawn whatever the particle setting. */
    private static final double SMOKE_DISTANCE = 480;
    private static final RandomSource random = RandomSource.create();

    private Airdrops() {
    }

    public static boolean enabled() {
        return SurvivalMode.enabled() && RaidConfig.SURVIVAL_AIRDROPS.get();
    }

    /** Called every second in survival mode. */
    static void second(MinecraftServer server, int seconds) {
        if (!enabled()) {
            return;
        }
        ServerLevel level = server.overworld();
        EventData data = EventData.get(server);
        long now = level.getGameTime();
        if (data.nextAirdrop == 0) {
            schedule(data, now);
        } else if (now >= data.nextAirdrop) {
            schedule(data, now);
            drop(level, null);
        }
        if (seconds % 2 == 0) {
            track(level, data, now);
        }
    }

    private static void schedule(EventData data, long now) {
        long interval = RaidConfig.SURVIVAL_AIRDROP_INTERVAL.get() * 60L * 20L;
        data.nextAirdrop = now + (long) (interval * (0.75 + random.nextDouble() * 0.5));
        data.setDirty();
    }

    /**
     * Drops a crate near {@code near}, or near a random player in survival. Null if there is nobody to drop it
     * for or no open ground was found.
     */
    @Nullable
    public static BlockPos drop(ServerLevel level, @Nullable ServerPlayer near) {
        List<ServerPlayer> players = SurvivalMode.players(level);
        ServerPlayer target = near != null ? near : players.isEmpty() ? null : players.get(random.nextInt(players.size()));
        if (target == null) {
            return null;
        }
        BlockPos pos = pickSpot(level, target);
        if (pos == null) {
            LOGGER.debug("Zone Raids: no open ground for a supply drop near {}", target.getName().getString());
            return null;
        }
        level.setBlock(pos, ModRegistry.HIDDEN_STASH.get().defaultBlockState(), 3);
        if (level.getBlockEntity(pos) instanceof HiddenStashBlockEntity crate) {
            ResourceLocation table = ResourceLocation.tryParse(RaidConfig.SURVIVAL_AIRDROP_LOOT_TABLE.get());
            if (table != null) {
                crate.setLootTable(table, random.nextLong());
            }
            crate.setCustomName(Component.translatable("zoneraids.airdrop.crate"));
        }
        EventData data = EventData.get(level.getServer());
        data.addDrop(pos, level.getGameTime() + RaidConfig.SURVIVAL_AIRDROP_LIFETIME.get() * 60L * 20L);
        for (ServerPlayer player : players.contains(target) ? players : List.of(target)) {
            int distance = (int) Math.sqrt(Math.pow(player.getX() - pos.getX(), 2) + Math.pow(player.getZ() - pos.getZ(), 2));
            player.sendSystemMessage(Component.translatable("zoneraids.airdrop.announce", distance,
                    Lairs.direction(player, pos.getX(), pos.getZ()), pos.getX(), pos.getZ()).withStyle(ChatFormatting.GOLD));
            RaidNetwork.sendTo(player, new MapMarkerPacket(pos.getX(), pos.getZ(), MapMarkerPacket.DROP, "zoneraids.airdrop.marker"));
            player.playNotifySound(SoundEvents.NOTE_BLOCK_BIT.get(), SoundSource.PLAYERS, 0.6F, 0.8F);
        }
        LOGGER.info("Zone Raids: supply drop at {}", pos);
        return pos;
    }

    @Nullable
    private static BlockPos pickSpot(ServerLevel level, ServerPlayer player) {
        int max = Math.max(RaidConfig.SURVIVAL_AIRDROP_MIN_DISTANCE.get(), RaidConfig.SURVIVAL_AIRDROP_MAX_DISTANCE.get());
        int min = Math.min(RaidConfig.SURVIVAL_AIRDROP_MIN_DISTANCE.get(), max);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = min + random.nextDouble() * (max - min);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            BlockPos pos = StashDrives.surface(level, x, z);
            // Not inside a stalker camp: the guards would fight the camp.
            if (pos != null && !Places.inSafeZone(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    /** Smoke over every tracked crate, guards for the ones someone walks up to; emptied or old crates are dropped. */
    private static void track(ServerLevel level, EventData data, long now) {
        Iterator<EventData.Drop> it = data.drops.iterator();
        while (it.hasNext()) {
            EventData.Drop drop = it.next();
            boolean loaded = level.hasChunk(drop.pos.getX() >> 4, drop.pos.getZ() >> 4);
            if (now > drop.expires || (loaded && !level.getBlockState(drop.pos).is(ModRegistry.HIDDEN_STASH.get()))) {
                it.remove();
                data.setDirty();
                continue;
            }
            double x = drop.pos.getX() + 0.5;
            double y = drop.pos.getY() + 0.8;
            double z = drop.pos.getZ() + 0.5;
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(x, y, z) < SMOKE_DISTANCE * SMOKE_DISTANCE) {
                    level.sendParticles(player, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, x, y, z, 2, 0.15, 0.3, 0.15, 0.01);
                }
            }
            if (!drop.guarded && loaded && level.isPositionEntityTicking(drop.pos) && someoneNear(level, drop.pos)) {
                drop.guarded = true;
                data.setDirty();
                guard(level, drop.pos);
            }
        }
    }

    private static boolean someoneNear(ServerLevel level, BlockPos pos) {
        for (ServerPlayer player : SurvivalMode.players(level)) {
            if (player.distanceToSqr(pos.getCenter()) < (double) GUARD_DISTANCE * GUARD_DISTANCE) {
                return true;
            }
        }
        return false;
    }

    private static void guard(ServerLevel level, BlockPos pos) {
        EnemySpawner.EnemyType kind = EnemySpawner.types().get(RaidConfig.SURVIVAL_AIRDROP_GUARD_KIND.get());
        int count = RaidConfig.SURVIVAL_AIRDROP_GUARDS.get();
        if (kind == null || !kind.isInstalled() || count <= 0) {
            return;
        }
        int spawned = 0;
        for (int n = 0; n < count; n++) {
            Entity entity = EnemySpawner.spawn(level, kind, pos, level.players(), GUARD_MIN_DISTANCE, random, null);
            if (entity != null) {
                SurvivalMode.adopt(level, entity);
                spawned++;
            }
        }
        if (spawned == 0) {
            return;
        }
        Component warning = Component.translatable("zoneraids.airdrop.guarded").withStyle(ChatFormatting.RED);
        for (ServerPlayer player : SurvivalMode.players(level)) {
            if (player.distanceToSqr(pos.getCenter()) < 96 * 96) {
                player.displayClientMessage(warning, true);
            }
        }
    }
}
