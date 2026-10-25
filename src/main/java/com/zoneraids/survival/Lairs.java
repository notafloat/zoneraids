package com.zoneraids.survival;

import com.mojang.logging.LogUtils;
import com.zoneraids.RaidConfig;
import com.zoneraids.ZoneRaids;
import com.zoneraids.perk.PerkService;
import com.zoneraids.raid.EnemySpawner;
import com.zoneraids.registry.ModEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Survival lairs: abandoned Zone places with a boss, one site per region of the overworld (some
 * regions have none). A site is built when a player first comes near, the player's PDA reports the
 * signal, and the boss with its guards wakes when someone walks in. A beaten boss stays beaten.
 * <p>
 * Sites follow from the world seed, so nothing is stored for regions nobody visited; {@link LairData}
 * keeps what happened to the visited ones.
 */
public final class Lairs {
    public static final String TAG_LAIR = "zoneraids.lair";
    public static final String TAG_BOSS = "zoneraids.boss";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BUILD_DISTANCE = 112;
    private static final int SIGNAL_DISTANCE = 160;
    /** Passes a woken boss may be missing from its loaded lair before the lair counts as asleep again. */
    private static final int MISSING_PASSES = 3;
    private static final Map<String, Integer> missing = new HashMap<>();

    public enum Type {
        BANDIT_BASE("bandit_boss", "bandit", 4),
        AGROPROM("pseudogiant", "flesh", 3),
        LAB("controller", "snork", 2);

        public final String boss;
        final String guardKind;
        final int guards;

        Type(String boss, String guardKind, int guards) {
            this.boss = boss;
            this.guardKind = guardKind;
            this.guards = guards;
        }

        public Component displayName() {
            return Component.translatable("zoneraids.lair." + name().toLowerCase());
        }
    }

    private record Planned(String key, Type type, int x, int z) {
    }

    private Lairs() {
    }

    public static boolean enabled() {
        return SurvivalMode.enabled() && RaidConfig.SURVIVAL_LAIRS.get();
    }

    /** Called every few seconds in survival mode. */
    static void pass(ServerLevel level) {
        if (!enabled() || level != level.getServer().overworld()) {
            return;
        }
        LairData data = LairData.get(level.getServer());
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            for (Planned site : sitesNear(level, player.getBlockX(), player.getBlockZ())) {
                LairData.Entry entry = data.get(site.key());
                if (entry == null) {
                    if (horizontal(player, site.x(), site.z()) < BUILD_DISTANCE) {
                        tryBuild(level, data, site, player);
                    }
                } else if (entry.state == LairData.State.BUILT) {
                    if (!player.isCreative() && player.distanceToSqr(entry.boss.getCenter()) < (double) entry.wakeRadius * entry.wakeRadius) {
                        wake(level, data, site.key(), entry);
                    }
                } else if (entry.state == LairData.State.ACTIVE) {
                    checkBoss(level, data, site.key(), entry);
                }
            }
        }
    }

    private static void tryBuild(ServerLevel level, LairData data, Planned site, ServerPlayer player) {
        int r = 17;
        for (int[] c : new int[][]{{-r, -r}, {r, -r}, {-r, r}, {r, r}, {0, 0}}) {
            if (!level.hasChunk((site.x() + c[0]) >> 4, (site.z() + c[1]) >> 4)) {
                return;
            }
        }
        BlockPos origin = SiteBuilder.ground(level, site.x(), site.z());
        if (origin == null) {
            data.put(site.key(), LairData.State.SKIPPED, site.type(), new BlockPos(site.x(), 0, site.z()));
            return;
        }
        build(level, data, site.key(), site.type(), origin);
        Component signal = Component.translatable("zoneraids.lair.signal", site.type().displayName(),
                (int) horizontal(player, site.x(), site.z()), direction(player, site.x(), site.z())).withStyle(ChatFormatting.GOLD);
        for (ServerPlayer p : level.players()) {
            if (horizontal(p, site.x(), site.z()) < SIGNAL_DISTANCE) {
                p.sendSystemMessage(signal);
            }
        }
    }

    /** Builds a lair at {@code origin}; with a {@code key} it is tracked as that region's site. */
    public static LairData.Entry build(ServerLevel level, @Nullable LairData data, String key, Type type, BlockPos origin) {
        LairBuilder.Site built = LairBuilder.build(level, type, origin, level.getSeed() ^ origin.asLong());
        LairData.Entry entry = data == null ? new LairData.Entry(LairData.State.BUILT, type, origin)
                : data.put(key, LairData.State.BUILT, type, origin);
        entry.boss = built.boss();
        entry.guards = built.guards();
        entry.wakeRadius = built.wakeRadius();
        if (data != null) {
            data.setDirty();
        }
        LOGGER.info("Zone Raids: built lair {} at {}", type.name().toLowerCase(), origin);
        return entry;
    }

    /** Spawns the boss and its guards. */
    public static void wake(ServerLevel level, @Nullable LairData data, String key, LairData.Entry entry) {
        Entity boss = spawnBoss(level, entry.type, entry.boss);
        if (boss == null) {
            return;
        }
        boss.getPersistentData().putString(TAG_LAIR, key);
        boss.getPersistentData().putString(TAG_BOSS, entry.type.boss);
        EnemySpawner.EnemyType guard = EnemySpawner.types().get(entry.type.guardKind);
        RandomSource random = level.getRandom();
        if (guard != null && guard.isInstalled()) {
            for (int n = 0; n < entry.type.guards; n++) {
                Entity g = EnemySpawner.spawn(level, guard, entry.guards, List.of(), 0, random, null);
                if (g instanceof Mob mob) {
                    mob.setPersistenceRequired();
                }
            }
        }
        entry.state = LairData.State.ACTIVE;
        entry.bossId = boss.getUUID();
        if (data != null) {
            data.setDirty();
        }
        Component warning = Component.translatable("zoneraids.lair.wake", entry.type.displayName()).withStyle(ChatFormatting.RED);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(entry.boss.getCenter()) < 48 * 48) {
                p.displayClientMessage(warning, true);
            }
        }
    }

    @Nullable
    private static Entity spawnBoss(ServerLevel level, Type type, BlockPos pos) {
        Entity entity;
        if (type == Type.BANDIT_BASE) {
            EnemySpawner.EnemyType bandit = EnemySpawner.types().get("bandit");
            if (bandit == null || !bandit.isInstalled()) {
                return null;
            }
            // Borov is a bandit with his own look and guns: the zr_bandit_boss loadout (tacznpc_loadouts.json).
            CompoundTag nbt = bandit.nbt().copy();
            if (nbt.contains("template")) {
                nbt.putString("template", "zr_bandit_boss");
            }
            EnemySpawner.EnemyType borov = new EnemySpawner.EnemyType("bandit_boss", bandit.entity(), nbt);
            entity = EnemySpawner.spawn(level, borov, pos, List.of(), 0, level.getRandom(), null);
            if (entity instanceof Mob mob) {
                // The gang leader: a bandit with a boss's health and armor.
                setBase(mob, Attributes.MAX_HEALTH, 140.0);
                setBase(mob, Attributes.ARMOR, 12.0);
                mob.setHealth(mob.getMaxHealth());
                mob.setCustomName(Component.translatable("entity.zoneraids.bandit_boss"));
                mob.setCustomNameVisible(true);
                mob.setPersistenceRequired();
            }
            return entity;
        }
        Mob mob = type == Type.AGROPROM ? ModEntities.PSEUDOGIANT.get().create(level) : ModEntities.CONTROLLER.get().create(level);
        if (mob == null) {
            return null;
        }
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.STRUCTURE, null, null);
        mob.setPersistenceRequired();
        return level.addFreshEntity(mob) ? mob : null;
    }

    private static void setBase(Mob mob, net.minecraft.world.entity.ai.attributes.Attribute attribute, double value) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /** A woken boss gone without dying (peaceful difficulty, a command) puts its lair back to sleep. */
    private static void checkBoss(ServerLevel level, LairData data, String key, LairData.Entry entry) {
        if (!level.isPositionEntityTicking(entry.boss) || (entry.bossId != null && level.getEntity(entry.bossId) != null)) {
            missing.remove(key);
            return;
        }
        int passes = missing.merge(key, 1, Integer::sum);
        if (passes >= MISSING_PASSES) {
            missing.remove(key);
            entry.state = LairData.State.BUILT;
            entry.bossId = null;
            data.setDirty();
        }
    }

    /** A lair boss died: the lair is beaten for good and the killer earns perk points. */
    public static void onDeath(LivingEntity dead, @Nullable Entity killer) {
        CompoundTag tag = dead.getPersistentData();
        if (!tag.contains(TAG_LAIR) || !(dead.level() instanceof ServerLevel level)) {
            return;
        }
        String key = tag.getString(TAG_LAIR);
        LairData data = LairData.get(level.getServer());
        LairData.Entry entry = data.get(key);
        if (entry != null) {
            entry.state = LairData.State.DEFEATED;
            entry.bossId = null;
            data.setDirty();
        }
        Component name = entry != null ? entry.type.displayName() : dead.getDisplayName();
        Component msg = Component.translatable("zoneraids.lair.defeated", dead.getDisplayName(), name).withStyle(ChatFormatting.GREEN);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(dead) < 96 * 96) {
                p.sendSystemMessage(msg);
            }
        }
        if (killer instanceof ServerPlayer player) {
            PerkService.award(player, 3, "zoneraids.perk.reason.boss");
        }
    }

    /** Boss loot from {@code zoneraids:bosses/<boss>}. */
    public static void addDrops(LivingEntity entity, Collection<ItemEntity> drops) {
        CompoundTag tag = entity.getPersistentData();
        if (!tag.contains(TAG_BOSS) || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        LootTable table = level.getServer().getLootData().getLootTable(new ResourceLocation(ZoneRaids.MODID, "bosses/" + tag.getString(TAG_BOSS)));
        if (table == LootTable.EMPTY) {
            return;
        }
        for (ItemStack stack : table.getRandomItems(new LootParams.Builder(level).create(LootContextParamSets.EMPTY))) {
            drops.add(new ItemEntity(level, entity.getX(), entity.getY() + 0.5, entity.getZ(), stack));
        }
    }

    public record Open(String key, Type type, BlockPos pos) {
    }

    /** The nearest site from here that is not beaten yet (for admins), or null. */
    @Nullable
    public static BlockPos nearestOpen(ServerLevel level, int x, int z) {
        Open open = nearestOpenSite(level, x, z);
        return open == null ? null : open.pos();
    }

    /** The nearest site from here that is not beaten yet, with its region key, or null. */
    @Nullable
    public static Open nearestOpenSite(ServerLevel level, int x, int z) {
        LairData data = LairData.get(level.getServer());
        Open best = null;
        double bestDistance = Double.MAX_VALUE;
        int size = RaidConfig.SURVIVAL_LAIR_REGION.get();
        int rx = Math.floorDiv(x, size);
        int rz = Math.floorDiv(z, size);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                Planned site = planned(level, rx + dx, rz + dz);
                if (site == null) {
                    continue;
                }
                LairData.Entry entry = data.get(site.key());
                if (entry != null && (entry.state == LairData.State.DEFEATED || entry.state == LairData.State.SKIPPED)) {
                    continue;
                }
                double d = Math.pow(site.x() - x, 2) + Math.pow(site.z() - z, 2);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = new Open(site.key(), site.type(), new BlockPos(site.x(), 0, site.z()));
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ placement

    /** True if a lair site lies within {@code distance} blocks of x/z (never with lairs off). */
    static boolean siteNear(ServerLevel level, int x, int z, int distance) {
        if (!enabled()) {
            return false;
        }
        for (Planned site : sitesNear(level, x, z)) {
            if (Math.pow(site.x() - x, 2) + Math.pow(site.z() - z, 2) < (double) distance * distance) {
                return true;
            }
        }
        return false;
    }

    private static List<Planned> sitesNear(ServerLevel level, int x, int z) {
        int size = RaidConfig.SURVIVAL_LAIR_REGION.get();
        int rx = Math.floorDiv(x, size);
        int rz = Math.floorDiv(z, size);
        List<Planned> sites = new java.util.ArrayList<>(4);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Planned site = planned(level, rx + dx, rz + dz);
                if (site != null) {
                    sites.add(site);
                }
            }
        }
        return sites;
    }

    /** The region's site: decided by the world seed alone, kept away from the world spawn. */
    @Nullable
    private static Planned planned(ServerLevel level, int rx, int rz) {
        int size = RaidConfig.SURVIVAL_LAIR_REGION.get();
        Random random = new Random(level.getSeed() ^ (rx * 341873128712L) ^ (rz * 132897987541L) ^ 0x5A0E7A1L);
        if (random.nextDouble() >= RaidConfig.SURVIVAL_LAIR_CHANCE.get()) {
            return null;
        }
        Type type = Type.values()[random.nextInt(Type.values().length)];
        int x = rx * size + size / 4 + random.nextInt(Math.max(1, size / 2));
        int z = rz * size + size / 4 + random.nextInt(Math.max(1, size / 2));
        BlockPos spawn = level.getSharedSpawnPos();
        double min = RaidConfig.SURVIVAL_LAIR_MIN_SPAWN_DISTANCE.get();
        if (Math.pow(x - spawn.getX(), 2) + Math.pow(z - spawn.getZ(), 2) < min * min) {
            return null;
        }
        return new Planned(rx + "," + rz, type, x, z);
    }

    private static double horizontal(ServerPlayer player, int x, int z) {
        return Math.sqrt(Math.pow(player.getX() - x, 2) + Math.pow(player.getZ() - z, 2));
    }

    static Component direction(ServerPlayer player, int x, int z) {
        double angle = Math.toDegrees(Math.atan2(x - player.getX(), -(z - player.getZ())));
        String[] names = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
        int index = (int) Math.floorMod(Math.round(angle / 45.0), 8L);
        return Component.translatable("zoneraids.dir." + names[index]);
    }
}
