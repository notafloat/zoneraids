package com.zoneraids.survival;

import com.mojang.logging.LogUtils;
import com.zoneraids.RaidConfig;
import com.zoneraids.perk.PerkService;
import com.zoneraids.raid.EnemySpawner;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Survival places, next to the lairs: a friendly stalker camp with a trader, a hostile military
 * base, a ruined village and an abandoned base. One site per place region of the overworld (some
 * regions have none), built when a player first comes near, the way lairs are.
 * <p>
 * The crew of a hostile place or a ruin comes out when someone walks in; once it is dead the place
 * is cleared for good (a cleared military base gives perk points). A stalker camp keeps its trader
 * and guards: whoever is missing is sent again while no player is inside the camp, and no roaming
 * enemies spawn near it. Friendly NPCs are the {@code bandit} enemy kind with a friendly TaCZ NPC
 * template ({@code zr_stalker}, {@code zr_trader} in tacznpc_loadouts.json); without TaCZ NPCs the
 * camp still has its trader counter.
 */
public final class Places {
    public static final String TAG_PLACE = "zoneraids.place";
    public static final String TAG_TRADER = "zoneraids.trader";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BUILD_DISTANCE = 112;
    private static final int SIGNAL_DISTANCE = 160;
    /** No roaming enemies this close to a stalker camp. */
    private static final int SAFE_RADIUS = 48;
    /** Missing camp NPCs are sent again only while no player is this close, so nobody sees them appear. */
    private static final int STAFF_PRIVACY = 24;
    /** No place this close to a lair site. */
    private static final int LAIR_GAP = 96;
    /** Passes a crew may be missing from its loaded place before it counts as gone. */
    private static final int MISSING_PASSES = 3;
    private static final Map<String, Integer> missing = new HashMap<>();

    public enum Type {
        STALKER_CAMP(true, 14, "zr_stalker", 3, 2),
        MILITARY_BASE(false, 16, "military", 5, 3),
        RUINED_VILLAGE(false, 22, "blind_dog", 3, 3),
        RUINED_BASE(false, 18, "snork", 2, 2);

        public final boolean friendly;
        /** Half size of the footprint. */
        final int half;
        /** An enemy kind, or for a friendly place the TaCZ NPC template of its guards. */
        final String crewKind;
        final int crewCount;
        final int weight;

        Type(boolean friendly, int half, String crewKind, int crewCount, int weight) {
            this.friendly = friendly;
            this.half = half;
            this.crewKind = crewKind;
            this.crewCount = crewCount;
            this.weight = weight;
        }

        public Component displayName() {
            return Component.translatable("zoneraids.place." + name().toLowerCase());
        }
    }

    public record Found(String key, Type type, BlockPos pos) {
    }

    private record Planned(String key, Type type, int x, int z) {
    }

    private Places() {
    }

    public static boolean enabled() {
        return SurvivalMode.enabled() && RaidConfig.SURVIVAL_PLACES.get();
    }

    /** Called every few seconds in survival mode. */
    static void pass(ServerLevel level) {
        if (!enabled() || level != level.getServer().overworld()) {
            return;
        }
        PlaceData data = PlaceData.get(level.getServer());
        Set<String> done = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            for (Planned site : sitesNear(level, player.getBlockX(), player.getBlockZ())) {
                PlaceData.Entry entry = data.get(site.key());
                if (entry == null) {
                    if (horizontal(player, site.x(), site.z()) < BUILD_DISTANCE) {
                        tryBuild(level, data, site, player);
                    }
                } else if (entry.type.friendly) {
                    if (entry.state != PlaceData.State.SKIPPED && horizontal(player, site.x(), site.z()) < BUILD_DISTANCE && done.add(site.key())) {
                        staff(level, data, site.key(), entry, false);
                    }
                } else if (entry.state == PlaceData.State.BUILT) {
                    if (!player.isCreative() && player.distanceToSqr(entry.crewPos.getCenter()) < (double) entry.wakeRadius * entry.wakeRadius) {
                        wake(level, data, site.key(), entry);
                    }
                } else if (entry.state == PlaceData.State.ACTIVE && done.add(site.key())) {
                    checkCrew(level, data, site.key(), entry);
                }
            }
        }
    }

    private static void tryBuild(ServerLevel level, PlaceData data, Planned site, ServerPlayer player) {
        int r = site.type().half + 1;
        for (int[] c : new int[][]{{-r, -r}, {r, -r}, {-r, r}, {r, r}, {0, 0}}) {
            if (!level.hasChunk((site.x() + c[0]) >> 4, (site.z() + c[1]) >> 4)) {
                return;
            }
        }
        BlockPos origin = SiteBuilder.ground(level, site.x(), site.z());
        if (origin == null) {
            data.put(site.key(), PlaceData.State.SKIPPED, site.type(), new BlockPos(site.x(), 0, site.z()));
            return;
        }
        build(level, data, site.key(), site.type(), origin);
        String key = site.type().friendly ? "zoneraids.place.signal.friendly" : "zoneraids.place.signal";
        Component signal = Component.translatable(key, site.type().displayName(),
                (int) horizontal(player, site.x(), site.z()), Lairs.direction(player, site.x(), site.z()))
                .withStyle(site.type().friendly ? ChatFormatting.AQUA : ChatFormatting.GOLD);
        for (ServerPlayer p : level.players()) {
            if (horizontal(p, site.x(), site.z()) < SIGNAL_DISTANCE) {
                p.sendSystemMessage(signal);
            }
        }
    }

    /** Builds a place at {@code origin}; with {@code data} it is tracked as the region's site {@code key}. */
    public static PlaceData.Entry build(ServerLevel level, @Nullable PlaceData data, String key, Type type, BlockPos origin) {
        PlaceBuilder.Site built = PlaceBuilder.build(level, type, origin, level.getSeed() ^ origin.asLong() ^ 0x91ACE5L);
        PlaceData.Entry entry = data == null ? new PlaceData.Entry(PlaceData.State.BUILT, type, origin)
                : data.put(key, PlaceData.State.BUILT, type, origin);
        entry.crewPos = built.crew();
        entry.traderPos = built.trader();
        entry.wakeRadius = built.wakeRadius();
        if (data != null) {
            data.setDirty();
        }
        LOGGER.info("Zone Raids: built place {} at {}", type.name().toLowerCase(), origin);
        return entry;
    }

    /** Sends out the crew of a hostile place or a ruin. A place whose crew cannot spawn (the kind is not installed) is simply cleared. */
    public static void wake(ServerLevel level, @Nullable PlaceData data, String key, PlaceData.Entry entry) {
        EnemySpawner.EnemyType kind = EnemySpawner.types().get(entry.type.crewKind);
        entry.crew.clear();
        if (kind != null && kind.isInstalled()) {
            for (int n = 0; n < entry.type.crewCount; n++) {
                Entity e = EnemySpawner.spawn(level, kind, entry.crewPos, List.of(), 0, level.getRandom(), null);
                if (e instanceof Mob mob) {
                    mob.setPersistenceRequired();
                    mob.getPersistentData().putString(TAG_PLACE, key);
                    entry.crew.add(mob.getUUID());
                }
            }
        }
        entry.state = entry.crew.isEmpty() ? PlaceData.State.CLEARED : PlaceData.State.ACTIVE;
        if (data != null) {
            data.setDirty();
        }
        if (entry.crew.isEmpty()) {
            return;
        }
        Component warning = Component.translatable(entry.type == Type.MILITARY_BASE ? "zoneraids.place.alarm" : "zoneraids.lair.wake",
                entry.type.displayName()).withStyle(ChatFormatting.RED);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(entry.crewPos.getCenter()) < 48 * 48) {
                p.displayClientMessage(warning, true);
            }
        }
    }

    /** Keeps the crew from looting; a crew gone without dying (peaceful difficulty, a command) puts the place back to sleep. */
    private static void checkCrew(ServerLevel level, PlaceData data, String key, PlaceData.Entry entry) {
        boolean anyone = false;
        for (UUID id : entry.crew) {
            if (level.getEntity(id) instanceof Mob mob && mob.isAlive()) {
                EnemySpawner.keepInCheck(mob);
                anyone = true;
            }
        }
        if (anyone || !level.isPositionEntityTicking(entry.crewPos)) {
            missing.remove(key);
            return;
        }
        if (missing.merge(key, 1, Integer::sum) >= MISSING_PASSES) {
            missing.remove(key);
            entry.state = PlaceData.State.BUILT;
            entry.crew.clear();
            data.setDirty();
        }
    }

    /**
     * Fills a stalker camp up to its trader and guards. Someone missing for a few passes is sent
     * again, out of sight of players; with {@code now} at once (admin spawns).
     */
    public static void staff(ServerLevel level, @Nullable PlaceData data, String key, PlaceData.Entry entry, boolean now) {
        if (!level.isPositionEntityTicking(entry.traderPos)) {
            missing.remove(key);
            return;
        }
        boolean traderHere = entry.trader != null && level.getEntity(entry.trader) != null;
        long guards = entry.crew.stream().filter(id -> level.getEntity(id) != null).count();
        if (traderHere && guards >= entry.type.crewCount) {
            missing.remove(key);
            return;
        }
        if (!now) {
            if (missing.merge(key, 1, Integer::sum) < MISSING_PASSES) {
                return;
            }
            BlockPos c = entry.crewPos;
            if (level.getNearestPlayer(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, STAFF_PRIVACY, false) != null) {
                return;
            }
        }
        missing.remove(key);
        entry.crew.removeIf(id -> level.getEntity(id) == null);
        if (!traderHere) {
            Mob trader = spawnFriendly(level, "zr_trader", entry.traderPos, key, "entity.zoneraids.trader");
            entry.trader = trader == null ? null : trader.getUUID();
            if (trader != null) {
                trader.setNoAi(true);
                trader.setInvulnerable(true);
                trader.setYRot(180.0F);
                trader.setYHeadRot(180.0F);
                trader.setYBodyRot(180.0F);
                trader.getPersistentData().putBoolean(TAG_TRADER, true);
            }
        }
        for (int n = entry.crew.size(); n < entry.type.crewCount; n++) {
            Mob guard = spawnFriendly(level, entry.type.crewKind, entry.crewPos, key, "entity.zoneraids.stalker");
            if (guard == null) {
                break;
            }
            entry.crew.add(guard.getUUID());
        }
        if (data != null) {
            data.setDirty();
        }
    }

    /** A TaCZ NPC like the bandits but with a friendly template, or null without TaCZ NPCs. */
    @Nullable
    private static Mob spawnFriendly(ServerLevel level, String template, BlockPos pos, String key, String name) {
        EnemySpawner.EnemyType bandit = EnemySpawner.types().get("bandit");
        if (bandit == null || !bandit.isInstalled() || !bandit.nbt().contains("template")) {
            return null;
        }
        CompoundTag nbt = bandit.nbt().copy();
        nbt.putString("template", template);
        Entity entity = EnemySpawner.spawn(level, new EnemySpawner.EnemyType(template, bandit.entity(), nbt), pos, List.of(), 0,
                level.getRandom(), null);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) {
                entity.discard();
            }
            return null;
        }
        // Not an enemy: no enemy loot, no damage scaling, no flash drives.
        mob.getPersistentData().remove(EnemySpawner.TAG_KIND);
        mob.getPersistentData().putString(TAG_PLACE, key);
        mob.setPersistenceRequired();
        mob.setCustomName(Component.translatable(name));
        mob.setCustomNameVisible(true);
        return mob;
    }

    public static boolean isTrader(Entity entity) {
        return entity.getPersistentData().getBoolean(TAG_TRADER);
    }

    /** True near a stalker camp, where roaming enemies do not spawn. */
    public static boolean inSafeZone(ServerLevel level, BlockPos pos) {
        if (!enabled() || level != level.getServer().overworld()) {
            return false;
        }
        for (PlaceData.Entry entry : PlaceData.get(level.getServer()).all().values()) {
            if (entry.type.friendly && entry.state != PlaceData.State.SKIPPED) {
                double dx = entry.origin.getX() - pos.getX();
                double dz = entry.origin.getZ() - pos.getZ();
                if (dx * dx + dz * dz < SAFE_RADIUS * SAFE_RADIUS) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A member of a place's crew died: the last of a hostile crew clears the place. */
    public static void onDeath(LivingEntity dead, @Nullable Entity killer) {
        CompoundTag tag = dead.getPersistentData();
        if (!tag.contains(TAG_PLACE) || !(dead.level() instanceof ServerLevel level)) {
            return;
        }
        String key = tag.getString(TAG_PLACE);
        PlaceData data = PlaceData.get(level.getServer());
        PlaceData.Entry entry = data.get(key);
        if (entry == null) {
            return;
        }
        entry.crew.remove(dead.getUUID());
        if (dead.getUUID().equals(entry.trader)) {
            entry.trader = null;
        }
        data.setDirty();
        if (entry.type.friendly || entry.state != PlaceData.State.ACTIVE || !entry.crew.isEmpty()) {
            return;
        }
        entry.state = PlaceData.State.CLEARED;
        Component msg = Component.translatable("zoneraids.place.cleared", entry.type.displayName()).withStyle(ChatFormatting.GREEN);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(dead) < 96 * 96) {
                p.sendSystemMessage(msg);
            }
        }
        if (entry.type == Type.MILITARY_BASE && killer instanceof ServerPlayer player) {
            PerkService.award(player, 2, "zoneraids.perk.reason.base");
        }
    }

    /** The nearest site from here (of the given type, if any) that is not cleared yet, or null. */
    @Nullable
    public static Found nearest(ServerLevel level, int x, int z, @Nullable Type type) {
        return nearest(level, x, z, t -> type == null || t == type);
    }

    /** The nearest hostile place or ruin from here that is not cleared yet, or null. */
    @Nullable
    public static Found nearestHostile(ServerLevel level, int x, int z) {
        return nearest(level, x, z, t -> !t.friendly);
    }

    @Nullable
    private static Found nearest(ServerLevel level, int x, int z, Predicate<Type> wanted) {
        PlaceData data = PlaceData.get(level.getServer());
        Found best = null;
        double bestDistance = Double.MAX_VALUE;
        int size = RaidConfig.SURVIVAL_PLACE_REGION.get();
        int rx = Math.floorDiv(x, size);
        int rz = Math.floorDiv(z, size);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                Planned site = planned(level, rx + dx, rz + dz);
                if (site == null || !wanted.test(site.type())) {
                    continue;
                }
                PlaceData.Entry entry = data.get(site.key());
                if (entry != null && (entry.state == PlaceData.State.CLEARED || entry.state == PlaceData.State.SKIPPED)) {
                    continue;
                }
                double d = Math.pow(site.x() - x, 2) + Math.pow(site.z() - z, 2);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = new Found(site.key(), site.type(), new BlockPos(site.x(), 0, site.z()));
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ placement

    private static List<Planned> sitesNear(ServerLevel level, int x, int z) {
        int size = RaidConfig.SURVIVAL_PLACE_REGION.get();
        int rx = Math.floorDiv(x, size);
        int rz = Math.floorDiv(z, size);
        List<Planned> sites = new ArrayList<>(4);
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

    /** The region's site: decided by the world seed alone, hostile ones kept away from the world spawn, none on a lair. */
    @Nullable
    private static Planned planned(ServerLevel level, int rx, int rz) {
        int size = RaidConfig.SURVIVAL_PLACE_REGION.get();
        Random random = new Random(level.getSeed() ^ (rx * 198491317L) ^ (rz * 6542989L) ^ 0x9A1ACE5L);
        if (random.nextDouble() >= RaidConfig.SURVIVAL_PLACE_CHANCE.get()) {
            return null;
        }
        int total = 0;
        for (Type t : Type.values()) {
            total += t.weight;
        }
        int roll = random.nextInt(total);
        Type type = Type.values()[0];
        for (Type t : Type.values()) {
            roll -= t.weight;
            if (roll < 0) {
                type = t;
                break;
            }
        }
        int x = rx * size + size / 4 + random.nextInt(Math.max(1, size / 2));
        int z = rz * size + size / 4 + random.nextInt(Math.max(1, size / 2));
        BlockPos spawn = level.getSharedSpawnPos();
        double min = type.friendly ? 64 : RaidConfig.SURVIVAL_PLACE_MIN_SPAWN_DISTANCE.get();
        if (Math.pow(x - spawn.getX(), 2) + Math.pow(z - spawn.getZ(), 2) < min * min || Lairs.siteNear(level, x, z, LAIR_GAP)) {
            return null;
        }
        return new Planned(rx + "," + rz, type, x, z);
    }

    private static double horizontal(ServerPlayer player, int x, int z) {
        return Math.sqrt(Math.pow(player.getX() - x, 2) + Math.pow(player.getZ() - z, 2));
    }
}
