package com.zoneraids.raid;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.zoneraids.RaidConfig;
import com.zoneraids.ZoneRaids;
import com.zoneraids.data.EnemyPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Raid enemies: spawns each map's enemy groups as raiders approach, caps how many are alive and
 * removes them when the raid ends.
 * <p>
 * Every enemy carries its raid's id in its persistent data. An enemy whose raid is no longer
 * running is removed the moment it is loaded again (unloaded chunk, server restart), so no raid
 * leaves mobs behind. Sessions only hold entity UUIDs.
 */
public final class EnemySpawner {
    public static final String TAG_RAID = "zoneraids.raid";
    public static final String TAG_KIND = "zoneraids.kind";
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Raiders this close (with line of sight) get noticed by idle enemies, whatever their own AI thinks. */
    private static final double AGGRO_RANGE = 24.0;
    private static final int SPOT_ATTEMPTS = 16;
    private static final int SPOT_SPREAD = 5;

    public record EnemyType(String kind, ResourceLocation entity, CompoundTag nbt) {
        /** Zone Raids' own mutants are cheap; anything else (armed NPCs) counts against the NPC cap. */
        public boolean isNpc() {
            return !entity.getNamespace().equals(ZoneRaids.MODID);
        }

        public boolean isInstalled() {
            return ForgeRegistries.ENTITY_TYPES.containsKey(entity);
        }
    }

    @Nullable
    private static List<? extends String> parsedFrom;
    private static Map<String, EnemyType> types = Map.of();
    private static final Set<String> warned = new HashSet<>();

    private EnemySpawner() {
    }

    public static Map<String, EnemyType> types() {
        List<? extends String> config = RaidConfig.ENEMY_TYPES.get();
        if (config != parsedFrom) {
            Map<String, EnemyType> parsed = new LinkedHashMap<>();
            for (String line : config) {
                int eq = line.indexOf('=');
                String kind = line.substring(0, eq).trim();
                String spec = line.substring(eq + 1).trim();
                int brace = spec.indexOf('{');
                String id = brace < 0 ? spec : spec.substring(0, brace);
                CompoundTag nbt = new CompoundTag();
                try {
                    if (brace >= 0) {
                        nbt = TagParser.parseTag(spec.substring(brace));
                    }
                    parsed.put(kind, new EnemyType(kind, new ResourceLocation(id.trim()), nbt));
                } catch (CommandSyntaxException | RuntimeException e) {
                    LOGGER.error("Zone Raids: bad enemy type '{}': {}", line, e.getMessage());
                }
            }
            types = parsed;
            parsedFrom = config;
        }
        return types;
    }

    @Nullable
    private static EnemyType usableType(String kind) {
        EnemyType type = types().get(kind);
        if (type == null || !type.isInstalled()) {
            if (warned.add(kind)) {
                LOGGER.warn("Zone Raids: enemy kind '{}' is {}, its points stay empty", kind,
                        type == null ? "not in the enemyTypes config" : "not installed (" + type.entity() + ")");
            }
            return null;
        }
        return type;
    }

    // ------------------------------------------------------------------ raid lifecycle

    /** Called about once a second per running raid. */
    static void tick(ServerLevel level, RaidSession session, List<ServerPlayer> raiders, RandomSource random) {
        if (!RaidConfig.ENEMIES_ENABLED.get() || raiders.isEmpty()) {
            return;
        }
        List<EnemyPoint> points = session.enemyPoints;
        for (int i = 0; i < points.size(); i++) {
            if (session.enemyPointsDone.get(i)) {
                continue;
            }
            EnemyPoint point = points.get(i);
            Vec3 center = Vec3.atBottomCenterOf(point.pos());
            if (!level.isLoaded(point.pos()) || nearestDistanceSqr(center, raiders) > (double) point.trigger() * point.trigger()) {
                continue;
            }
            session.enemyPointsDone.set(i);
            if (random.nextDouble() < point.chance()) {
                spawnGroup(level, session, point, raiders, random);
            }
        }
        aggro(level, session, raiders);
    }

    private static void spawnGroup(ServerLevel level, RaidSession session, EnemyPoint point, List<ServerPlayer> raiders, RandomSource random) {
        EnemyType type = usableType(point.kind());
        if (type == null) {
            return;
        }
        int count = point.min() + (point.max() > point.min() ? random.nextInt(point.max() - point.min() + 1) : 0);
        count = Math.min(count, RaidConfig.MAX_ENEMIES_ALIVE.get() - session.enemies.size());
        if (type.isNpc()) {
            count = Math.min(count, RaidConfig.MAX_NPCS_ALIVE.get() - session.npcs.size());
        }
        int minDistance = RaidConfig.ENEMY_MIN_SPAWN_DISTANCE.get();
        for (int n = 0; n < count; n++) {
            Entity entity = spawn(level, type, point.pos(), raiders, minDistance, random, session.id);
            if (entity == null) {
                break;
            }
            session.enemies.add(entity.getUUID());
            if (type.isNpc()) {
                session.npcs.add(entity.getUUID());
            }
        }
    }

    /** Enemies that stand around without a target turn on a raider they can see; none of them picks up loot. */
    private static void aggro(ServerLevel level, RaidSession session, List<ServerPlayer> raiders) {
        for (UUID id : session.enemies) {
            if (!(level.getEntity(id) instanceof Mob mob) || !mob.isAlive()) {
                continue;
            }
            if (mob.canPickUpLoot()) {
                // TaCZ NPCs re-enable it when their loadout is applied on the first tick.
                mob.setCanPickUpLoot(false);
            }
            if (mob.getTarget() != null) {
                continue;
            }
            ServerPlayer best = null;
            double bestDistance = AGGRO_RANGE * AGGRO_RANGE;
            for (ServerPlayer player : raiders) {
                double d = mob.distanceToSqr(player);
                if (d < bestDistance && !player.isCreative() && !player.isSpectator() && mob.getSensing().hasLineOfSight(player)) {
                    best = player;
                    bestDistance = d;
                }
            }
            if (best != null) {
                mob.setTarget(best);
            }
        }
    }

    /** Removes the raid's enemies that are loaded; unloaded ones are removed when they load (see {@link #shouldRejectOnLoad}). */
    static void cleanup(ServerLevel level, RaidSession session) {
        for (UUID id : session.enemies) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                entity.discard();
            }
        }
        session.enemies.clear();
        session.npcs.clear();
        sweep(level);
    }

    /** Removes loaded enemies that belong to no running raid. */
    static void sweep(ServerLevel level) {
        List<Entity> stale = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (shouldRejectOnLoad(entity)) {
                stale.add(entity);
            }
        }
        stale.forEach(Entity::discard);
    }

    /** True for a raid enemy whose raid is over. */
    public static boolean shouldRejectOnLoad(Entity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(TAG_RAID)) {
            return false;
        }
        RaidManager manager = RaidManager.get();
        UUID raid = parseUuid(data.getString(TAG_RAID));
        return manager == null || raid == null || manager.sessionById(raid) == null;
    }

    /** Forgets an enemy that died or was removed for good, so the alive caps free up. */
    public static void onRemoved(Entity entity) {
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) {
            return;
        }
        CompoundTag data = entity.getPersistentData();
        RaidManager manager = RaidManager.get();
        UUID raid = data.contains(TAG_RAID) ? parseUuid(data.getString(TAG_RAID)) : null;
        RaidSession session = manager == null || raid == null ? null : manager.sessionById(raid);
        if (session != null) {
            session.enemies.remove(entity.getUUID());
            session.npcs.remove(entity.getUUID());
        }
    }

    /** Adds the kind's loot table ({@code zoneraids:enemies/<kind>}) to an enemy's drops. */
    public static void addDrops(LivingEntity entity, Collection<ItemEntity> drops) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(TAG_KIND) || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        for (ItemStack stack : roll(level, new ResourceLocation(ZoneRaids.MODID, "enemies/" + data.getString(TAG_KIND)))) {
            drops.add(new ItemEntity(level, entity.getX(), entity.getY() + 0.5, entity.getZ(), stack));
        }
    }

    // ------------------------------------------------------------------ spawning

    /**
     * Spawns one enemy near {@code center}. With a {@code raid} id the enemy belongs to that raid
     * (persistent until the raid ends); without one it is a free test spawn.
     */
    @Nullable
    public static Entity spawn(ServerLevel level, EnemyType type, BlockPos center, Collection<ServerPlayer> avoid, int minDistance,
                               RandomSource random, @Nullable UUID raid) {
        EntityType<?> entityType = ForgeRegistries.ENTITY_TYPES.getValue(type.entity());
        if (entityType == null || !type.isInstalled()) {
            return null;
        }
        Vec3 pos = findSpot(level, entityType, center, avoid, minDistance, random);
        if (pos == null) {
            return null;
        }
        CompoundTag tag = type.nbt().copy();
        tag.putString("id", type.entity().toString());
        Entity entity = EntityType.loadEntityRecursive(tag, level, e -> {
            e.moveTo(pos.x, pos.y, pos.z, random.nextFloat() * 360.0F, 0.0F);
            return e;
        });
        if (entity == null) {
            return null;
        }
        if (entity instanceof Mob mob) {
            if (type.nbt().isEmpty()) {
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.EVENT, null, null);
            }
            equip(level, mob, type.kind());
            // Enemies must not hoover up what raiders drop: loot comes only from their own tables.
            mob.setCanPickUpLoot(false);
            mob.setYHeadRot(mob.getYRot());
            if (raid != null) {
                mob.setPersistenceRequired();
            }
        }
        CompoundTag data = entity.getPersistentData();
        data.putString(TAG_KIND, type.kind());
        if (raid != null) {
            data.putString(TAG_RAID, raid.toString());
        }
        return level.tryAddFreshEntityWithPassengers(entity) ? entity : null;
    }

    /** Gear from {@code zoneraids:enemies/<kind>_gear}: the first weapon goes to the main hand, armor to its slot. */
    private static void equip(ServerLevel level, Mob mob, String kind) {
        List<ItemStack> gear = roll(level, new ResourceLocation(ZoneRaids.MODID, "enemies/" + kind + "_gear"));
        if (gear.isEmpty()) {
            return;
        }
        float dropChance = RaidConfig.ENEMY_GEAR_DROP_CHANCE.get().floatValue();
        Set<EquipmentSlot> used = EnumSet.noneOf(EquipmentSlot.class);
        for (ItemStack stack : gear) {
            EquipmentSlot slot = Mob.getEquipmentSlotForItem(stack);
            if (slot == EquipmentSlot.OFFHAND || !used.add(slot)) {
                continue;
            }
            mob.setItemSlot(slot, stack);
            mob.setDropChance(slot, dropChance);
        }
    }

    private static List<ItemStack> roll(ServerLevel level, ResourceLocation id) {
        LootTable table = level.getServer().getLootData().getLootTable(id);
        if (table == LootTable.EMPTY) {
            return List.of();
        }
        return table.getRandomItems(new LootParams.Builder(level).create(LootContextParamSets.EMPTY));
    }

    @Nullable
    private static Vec3 findSpot(ServerLevel level, EntityType<?> type, BlockPos center, Collection<ServerPlayer> avoid, int minDistance,
                                 RandomSource random) {
        Vec3 fallback = null;
        double minSqr = (double) minDistance * minDistance;
        for (int attempt = 0; attempt < SPOT_ATTEMPTS; attempt++) {
            int dx = attempt == 0 ? 0 : random.nextInt(SPOT_SPREAD * 2 + 1) - SPOT_SPREAD;
            int dz = attempt == 0 ? 0 : random.nextInt(SPOT_SPREAD * 2 + 1) - SPOT_SPREAD;
            for (int dy = 3; dy >= -6; dy--) {
                BlockPos feet = center.offset(dx, dy, dz);
                if (!level.isLoaded(feet) || !isStandable(level, type, feet)) {
                    continue;
                }
                Vec3 spot = Vec3.atBottomCenterOf(feet);
                if (nearestDistanceSqr(spot, avoid) >= minSqr) {
                    return spot;
                }
                if (fallback == null) {
                    fallback = spot;
                }
                break;
            }
        }
        return fallback;
    }

    private static boolean isStandable(ServerLevel level, EntityType<?> type, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        if (!ground.isFaceSturdy(level, below, Direction.UP) || ground.is(Blocks.MAGMA_BLOCK) || ground.is(BlockTags.CAMPFIRES)) {
            return false;
        }
        for (int y = 0; y < Math.ceil(type.getHeight()); y++) {
            BlockState state = level.getBlockState(feet.above(y));
            if (!state.isAir() && (!state.canBeReplaced() || state.is(BlockTags.FIRE) || !state.getFluidState().isEmpty())) {
                return false;
            }
        }
        AABB box = type.getAABB(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        return level.noCollision(box) && !level.containsAnyLiquid(box);
    }

    private static double nearestDistanceSqr(Vec3 pos, Collection<ServerPlayer> players) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            best = Math.min(best, player.distanceToSqr(pos));
        }
        return best;
    }

    @Nullable
    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
