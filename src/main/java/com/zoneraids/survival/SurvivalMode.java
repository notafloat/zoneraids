package com.zoneraids.survival;

import com.mojang.logging.LogUtils;
import com.zoneraids.RaidConfig;
import com.zoneraids.data.PlayerRaidData;
import com.zoneraids.perk.PerkService;
import com.zoneraids.raid.EnemySpawner;
import com.zoneraids.raid.RaidManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Survival mode ({@code [survival] enabled}): the open world instead of raids. Groups of the raid
 * enemy kinds roam near players (more of them at night and in Anomaly Zone biomes), each newcomer
 * gets a starter kit once, and killing roaming enemies earns perk points.
 * <p>
 * Roaming enemies are ordinary {@link EnemySpawner} spawns without a raid, so they keep the raid
 * enemies' loot tables, damage caps and hip-fire, and carry {@link #TAG_WILD}. Nothing here is held
 * per player in memory; the enemy list is rebuilt from the loaded entities on every pass.
 */
public final class SurvivalMode {
    public static final String TAG_WILD = "zoneraids.wild";
    private static final String STARTED = "SurvivalStarted";
    private static final String KILLS = "SurvivalKills";
    private static final String ZONE_NAMESPACE = "anomalyzone";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Roaming enemies seen on the last pass, by dimension; checked every second for hip-fire and loot pickup. */
    private static final Map<ResourceKey<Level>, List<UUID>> wild = new HashMap<>();
    private static final RandomSource random = RandomSource.create();
    private static int ticks;

    private record Group(String kind, int weight, int min, int max, boolean nightOnly, boolean zoneOnly) {
    }

    private SurvivalMode() {
    }

    public static boolean enabled() {
        return RaidConfig.SURVIVAL.get();
    }

    public static void tick(MinecraftServer server) {
        if (!enabled()) {
            return;
        }
        ticks++;
        if (ticks % 20 == 0) {
            int seconds = ticks / 20;
            Emissions.second(server);
            Airdrops.second(server, seconds);
            Contracts.second(server, seconds);
            wild.forEach((dimension, ids) -> {
                ServerLevel level = server.getLevel(dimension);
                if (level != null) {
                    for (UUID id : ids) {
                        if (level.getEntity(id) instanceof Mob mob && mob.isAlive()) {
                            EnemySpawner.keepInCheck(mob);
                        }
                    }
                }
            });
        }
        if (ticks % 100 == 0) {
            Lairs.pass(server.overworld());
            Places.pass(server.overworld());
        }
        if (ticks % (RaidConfig.SURVIVAL_SPAWN_INTERVAL.get() * 20) != 0) {
            return;
        }
        for (String name : RaidConfig.SURVIVAL_DIMENSIONS.get()) {
            ResourceLocation id = ResourceLocation.tryParse(name.trim());
            ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (level != null) {
                pass(level);
            }
        }
    }

    public static void onServerStarted() {
        if (enabled()) {
            LOGGER.info("Zone Raids: survival mode, enemies roam in {}", RaidConfig.SURVIVAL_DIMENSIONS.get());
        }
    }

    public static void stop() {
        wild.clear();
        ticks = 0;
        Emissions.stop();
    }

    /** Players in the level whom the open world's events concern: alive, not in creative or spectator mode, not in a raid. */
    public static List<ServerPlayer> players(ServerLevel level) {
        RaidManager manager = RaidManager.get();
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isAlive() && !player.isCreative() && !player.isSpectator()
                    && (manager == null || !manager.isInRaid(player.getUUID()))) {
                players.add(player);
            }
        }
        return players;
    }

    /** Makes an enemy spawned elsewhere (supply drop guards) a roaming one: kept in check, removed when nobody is near. */
    static void adopt(ServerLevel level, Entity entity) {
        entity.getPersistentData().putBoolean(TAG_WILD, true);
        wild.computeIfAbsent(level.dimension(), d -> new ArrayList<>()).add(entity.getUUID());
    }

    /** Removes roaming enemies nobody is near, then gives every player a chance of a new group nearby. */
    private static void pass(ServerLevel level) {
        double despawn = RaidConfig.SURVIVAL_DESPAWN_DISTANCE.get();
        List<Entity> roaming = new ArrayList<>();
        List<Entity> stale = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (!entity.getPersistentData().getBoolean(TAG_WILD) || !entity.isAlive()) {
                continue;
            }
            if (level.getNearestPlayer(entity, despawn) == null) {
                stale.add(entity);
            } else {
                roaming.add(entity);
            }
        }
        stale.forEach(Entity::discard);
        List<UUID> ids = new ArrayList<>(roaming.size());
        roaming.forEach(e -> ids.add(e.getUUID()));
        wild.put(level.dimension(), ids);

        // Everyone hides from an emission, the enemies too.
        if (level.getDifficulty() == Difficulty.PEACEFUL || !RaidConfig.ENEMIES_ENABLED.get() || Emissions.active()) {
            return;
        }
        RaidManager manager = RaidManager.get();
        BlockPos spawn = level.getSharedSpawnPos();
        double safe = RaidConfig.SURVIVAL_SAFE_RADIUS.get();
        int maxDistance = Math.max(RaidConfig.SURVIVAL_MIN_DISTANCE.get(), RaidConfig.SURVIVAL_MAX_DISTANCE.get());
        double crowd = 4.0 * maxDistance * maxDistance;
        for (ServerPlayer player : level.players()) {
            if (player.isCreative() || player.isSpectator() || (manager != null && manager.isInRaid(player.getUUID()))) {
                continue;
            }
            long near = roaming.stream().filter(e -> e.distanceToSqr(player) < crowd).count();
            if (near >= RaidConfig.SURVIVAL_MAX_NEAR_PLAYER.get()) {
                continue;
            }
            boolean zone = inZone(level, player.blockPosition());
            boolean night = !level.isDay();
            double chance = RaidConfig.SURVIVAL_SPAWN_CHANCE.get()
                    * (zone ? RaidConfig.SURVIVAL_ZONE_BIOME_MULTIPLIER.get() : 1.0)
                    * (night ? RaidConfig.SURVIVAL_NIGHT_MULTIPLIER.get() : 1.0);
            if (random.nextDouble() >= chance) {
                continue;
            }
            BlockPos center = pickCenter(level, player, maxDistance);
            if (center == null || (safe > 0 && horizontalDistanceSqr(center, spawn) < safe * safe) || Places.inSafeZone(level, center)) {
                continue;
            }
            Group group = pickGroup(night, inZone(level, center));
            if (group != null) {
                spawnGroup(level, group, center, roaming, (int) (RaidConfig.SURVIVAL_MAX_NEAR_PLAYER.get() - near));
            }
        }
    }

    private static void spawnGroup(ServerLevel level, Group group, BlockPos center, List<Entity> roaming, int room) {
        EnemySpawner.EnemyType type = EnemySpawner.types().get(group.kind());
        if (type == null || !type.isInstalled()) {
            return;
        }
        int count = Math.min(room, Mth.nextInt(random, group.min(), group.max()));
        int minDistance = RaidConfig.ENEMY_MIN_SPAWN_DISTANCE.get();
        int spawned = 0;
        for (int n = 0; n < count; n++) {
            Entity entity = EnemySpawner.spawn(level, type, center, level.players(), minDistance, random, null);
            if (entity == null) {
                break;
            }
            entity.getPersistentData().putBoolean(TAG_WILD, true);
            roaming.add(entity);
            wild.computeIfAbsent(level.dimension(), d -> new ArrayList<>()).add(entity.getUUID());
            spawned++;
        }
        if (spawned > 0) {
            LOGGER.debug("Zone Raids survival: {} x {} at {}", spawned, group.kind(), center);
        }
    }

    /** A surface spot in a loaded chunk, between minDistance and maxDistance from the player. */
    private static BlockPos pickCenter(ServerLevel level, ServerPlayer player, int maxDistance) {
        int minDistance = Math.min(RaidConfig.SURVIVAL_MIN_DISTANCE.get(), maxDistance);
        for (int attempt = 0; attempt < 4; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
            int x = Mth.floor(player.getX() + Math.cos(angle) * distance);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * distance);
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y > level.getMinBuildHeight()) {
                return new BlockPos(x, y, z);
            }
        }
        return null;
    }

    private static Group pickGroup(boolean night, boolean zone) {
        List<Group> groups = new ArrayList<>();
        int total = 0;
        for (String line : RaidConfig.SURVIVAL_SPAWNS.get()) {
            Group group = parse(line);
            if (group == null || (group.nightOnly() && !night) || (group.zoneOnly() && !zone)) {
                continue;
            }
            groups.add(group);
            total += group.weight();
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (Group group : groups) {
            roll -= group.weight();
            if (roll < 0) {
                return group;
            }
        }
        return null;
    }

    private static Group parse(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 4) {
            return null;
        }
        try {
            int weight = Integer.parseInt(parts[1]);
            int min = Math.max(1, Integer.parseInt(parts[2]));
            int max = Math.max(min, Integer.parseInt(parts[3]));
            boolean nightOnly = false;
            boolean zoneOnly = false;
            for (int i = 4; i < parts.length; i++) {
                nightOnly |= parts[i].equalsIgnoreCase("night");
                zoneOnly |= parts[i].equalsIgnoreCase("zone");
            }
            return weight > 0 ? new Group(parts[0], weight, min, max, nightOnly, zoneOnly) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean inZone(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(k -> k.location().getNamespace().equals(ZONE_NAMESPACE)).orElse(false);
    }

    private static double horizontalDistanceSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------ players

    /** First login in survival mode: the starter kit, the artifact container and a basic gun and armor, once. */
    public static void onLogin(ServerPlayer player) {
        if (!enabled() || player.isCreative() || player.isSpectator()) {
            return;
        }
        CompoundTag root = PlayerRaidData.root(player);
        if (root.getBoolean(STARTED)) {
            return;
        }
        root.putBoolean(STARTED, true);
        RaidManager.giveItems(player, RaidConfig.SURVIVAL_STARTER_KIT.get());
        RaidManager.giveStarterContainer(player);
        RaidManager.giveLootKit(player, player.serverLevel(), RaidConfig.SURVIVAL_STARTER_LOOT_TABLE.get());
        player.sendSystemMessage(Component.translatable("zoneraids.survival.welcome").withStyle(ChatFormatting.GOLD));
    }

    /** A player killed a roaming enemy: every {@code killsPerPerkPoint} kills give a perk point. */
    public static void onDeath(Entity dead, Entity killer) {
        if (!enabled() || !(killer instanceof ServerPlayer player)) {
            return;
        }
        Contracts.onKill(player, dead);
        if (!dead.getPersistentData().getBoolean(TAG_WILD)) {
            return;
        }
        int per = RaidConfig.SURVIVAL_KILLS_PER_PERK_POINT.get();
        if (per <= 0) {
            return;
        }
        CompoundTag root = PlayerRaidData.root(player);
        int kills = root.getInt(KILLS) + 1;
        root.putInt(KILLS, kills);
        if (kills % per == 0) {
            PerkService.award(player, 1, "zoneraids.perk.reason.survival_kills");
        }
    }
}
