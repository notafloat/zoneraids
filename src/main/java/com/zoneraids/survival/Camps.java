package com.zoneraids.survival;

import com.mojang.logging.LogUtils;
import com.zoneraids.RaidConfig;
import com.zoneraids.perk.PerkService;
import com.zoneraids.raid.EnemySpawner;
import com.zoneraids.raid.RaidManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Temporary camps in survival mode: now and then a group of NPCs makes camp somewhere near a player,
 * out of sight, with a campfire, a tent and bedrolls (see {@link CampBuilder}), and sits round the
 * fire. After {@code campLifetimeMinutes} the camp packs up and is gone, blocks and all, the next
 * time nobody is near it.
 * <p>
 * A friendly camp ({@code stalker}) is TaCZ NPCs with the {@code zr_stalker} template, one of them a
 * trader once there are three; right-click the others for a word or a tip about a place nearby, and
 * no roaming enemies spawn next to it. A hostile camp (any enemy kind, bandits and soldiers by
 * default) gets up and fights when a player comes close or one of them is hurt; killing the last of
 * them gives a perk point. Members sit on invisible marker armor stands with their AI off until they
 * get up.
 */
public final class Camps {
    public static final String FRIENDLY = "stalker";
    public static final String TAG_CAMP = "zoneraids.camp";
    private static final Logger LOGGER = LogUtils.getLogger();
    /** How often {@link #pass} runs, in seconds (SurvivalMode calls it every 100 ticks). */
    private static final int PASS_SECONDS = 5;
    private static final int SPAWN_MIN = 72;
    private static final int SPAWN_MAX = 128;
    /** No camp appears or packs up while a player is this close, so nobody sees it happen. */
    private static final int PRIVACY = 56;
    /** No two camps closer than this. */
    private static final int GAP = 160;
    /** A hostile camp gets up when a player comes this close. */
    private static final int WAKE_RADIUS = 18;
    /** No roaming enemies this close to a friendly camp. */
    private static final int SAFE_RADIUS = 40;
    private static final int SIGNAL_DISTANCE = 192;
    private static final int MAX_MEMBERS = 6;
    /** A cleared camp packs up this many ticks after its last member died, at the latest. */
    private static final long CLEARED_TICKS = 5 * 60 * 20;
    private static final int CHAT_LINES = 10;
    private static final RandomSource random = RandomSource.create();
    private static final Map<UUID, Long> lastTalk = new HashMap<>();

    private record Kind(String name, int weight, int min, int max) {
        boolean friendly() {
            return FRIENDLY.equals(name);
        }
    }

    private Camps() {
    }

    public static boolean enabled() {
        return SurvivalMode.enabled() && RaidConfig.SURVIVAL_CAMPS.get();
    }

    // ------------------------------------------------------------------ passes

    /** Every few seconds: packs up camps whose time is over, then maybe sets up a new one near a player. */
    static void pass(ServerLevel level) {
        if (level != level.getServer().overworld()) {
            return;
        }
        CampData data = CampData.get(level.getServer());
        long now = level.getGameTime();
        for (CampData.Camp camp : List.copyOf(data.all().values())) {
            if (now >= camp.expires && unwatched(level, camp)) {
                packUp(level, data, camp);
            }
        }
        if (!enabled()) {
            return;
        }
        long standing = data.all().values().stream().filter(c -> c.expires > now).count();
        if (standing >= RaidConfig.SURVIVAL_CAMP_MAX.get()) {
            return;
        }
        double chance = PASS_SECONDS / (RaidConfig.SURVIVAL_CAMP_INTERVAL.get() * 60.0);
        RaidManager manager = RaidManager.get();
        for (ServerPlayer player : level.players()) {
            if (player.isCreative() || player.isSpectator() || (manager != null && manager.isInRaid(player.getUUID()))
                    || random.nextDouble() >= chance) {
                continue;
            }
            BlockPos origin = findSpot(level, data, player);
            Kind kind = origin == null ? null : pickKind(level, origin);
            if (kind != null && create(level, data, kind.name(), kind.min(), kind.max(), origin) != null) {
                return;
            }
        }
    }

    /** Every second: a resting hostile camp gets up when a player comes close; awake enemies are kept in check. */
    static void second(ServerLevel level) {
        if (level != level.getServer().overworld()) {
            return;
        }
        CampData data = CampData.get(level.getServer());
        for (CampData.Camp camp : List.copyOf(data.all().values())) {
            if (camp.state == CampData.State.CLEARED || !level.isPositionEntityTicking(camp.origin)) {
                continue;
            }
            if (camp.state == CampData.State.AWAKE) {
                if (!camp.friendly()) {
                    for (UUID id : camp.members) {
                        if (level.getEntity(id) instanceof Mob mob && mob.isAlive()) {
                            EnemySpawner.keepInCheck(mob);
                        }
                    }
                }
                continue;
            }
            if (camp.friendly()) {
                continue;
            }
            ServerPlayer intruder = null;
            double best = (double) WAKE_RADIUS * WAKE_RADIUS;
            for (ServerPlayer player : level.players()) {
                double d = player.distanceToSqr(camp.origin.getCenter());
                if (!player.isCreative() && !player.isSpectator() && player.isAlive() && d < best) {
                    best = d;
                    intruder = player;
                }
            }
            if (intruder != null) {
                wake(level, data, camp, intruder);
            }
        }
    }

    /** A camp member was hurt: a resting camp gets up (hostile ones go for the attacker if it is a player). */
    public static void onHurt(LivingEntity hurt, @Nullable Entity attacker) {
        CompoundTag tag = hurt.getPersistentData();
        if (!tag.contains(TAG_CAMP) || !(hurt.level() instanceof ServerLevel level)) {
            return;
        }
        CampData data = CampData.get(level.getServer());
        CampData.Camp camp = data.get(tag.getInt(TAG_CAMP));
        if (camp != null && camp.state == CampData.State.RESTING) {
            wake(level, data, camp, attacker instanceof ServerPlayer player ? player : null);
        }
    }

    // ------------------------------------------------------------------ setting up

    /**
     * Sets up a camp of {@code kind} at {@code origin} with min..max members. Returns null (and
     * leaves nothing behind) if nobody could be spawned, e.g. TaCZ NPCs are not installed.
     */
    @Nullable
    public static CampData.Camp create(ServerLevel level, CampData data, String kind, int min, int max, BlockPos origin) {
        boolean friendly = FRIENDLY.equals(kind);
        EnemySpawner.EnemyType type = friendly ? null : EnemySpawner.types().get(kind);
        if (friendly ? !Places.friendlyAvailable() : (type == null || !type.isInstalled())) {
            return null;
        }
        int count = Mth.clamp(Mth.nextInt(random, min, Math.max(min, max)), 1, MAX_MEMBERS);
        CampBuilder.Result built = CampBuilder.build(level, origin, kind, count, random.nextLong());
        CampData.Camp camp = data.add(kind, origin);
        camp.blocks.addAll(built.blocks());
        long lifetime = RaidConfig.SURVIVAL_CAMP_LIFETIME.get() * 60L * 20L;
        camp.expires = level.getGameTime() + lifetime * 3 / 4 + (long) (random.nextDouble() * lifetime / 2);
        for (int i = 0; i < built.seats().size(); i++) {
            CampBuilder.Seat seat = built.seats().get(i);
            BlockPos at = BlockPos.containing(seat.x(), seat.y() + 1, seat.z());
            Mob mob;
            if (friendly) {
                boolean trader = i == 0 && count >= 3;
                mob = Places.spawnFriendly(level, trader ? "zr_trader" : "zr_stalker", at, null,
                        trader ? "entity.zoneraids.trader" : "entity.zoneraids.stalker");
                if (mob != null && trader) {
                    mob.getPersistentData().putBoolean(Places.TAG_TRADER, true);
                }
            } else {
                Entity e = EnemySpawner.spawn(level, type, at, List.of(), 0, random, null);
                mob = e instanceof Mob m ? m : null;
                if (mob == null && e != null) {
                    e.discard();
                }
            }
            if (mob == null) {
                continue;
            }
            mob.setPersistenceRequired();
            mob.getPersistentData().putInt(TAG_CAMP, camp.id);
            camp.members.add(mob.getUUID());
            sit(level, camp, mob, seat);
        }
        if (camp.members.isEmpty()) {
            packUp(level, data, camp);
            return null;
        }
        data.setDirty();
        LOGGER.info("Zone Raids: {} camp of {} at {}", kind, camp.members.size(), origin);
        announce(level, camp);
        return camp;
    }

    /** Seats a member on an invisible marker armor stand facing the fire, with its AI off. */
    private static void sit(ServerLevel level, CampData.Camp camp, Mob mob, CampBuilder.Seat seat) {
        mob.setNoAi(true);
        mob.moveTo(seat.x(), seat.y(), seat.z(), seat.yaw(), 0.0F);
        mob.setYHeadRot(seat.yaw());
        mob.setYBodyRot(seat.yaw());
        ArmorStand stand = new ArmorStand(level, seat.x(), seat.y(), seat.z());
        CompoundTag tag = new CompoundTag();
        stand.addAdditionalSaveData(tag);
        tag.putBoolean("Marker", true);
        tag.putBoolean("Invisible", true);
        stand.readAdditionalSaveData(tag);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.moveTo(seat.x(), seat.y(), seat.z(), seat.yaw(), 0.0F);
        stand.setYBodyRot(seat.yaw());
        stand.getPersistentData().putInt(TAG_CAMP, camp.id);
        if (level.addFreshEntity(stand)) {
            camp.seats.add(stand.getUUID());
            mob.startRiding(stand, true);
        }
    }

    /** A spot SPAWN_MIN..SPAWN_MAX blocks from the player, loaded, out of everyone's sight, clear of other camps and places. */
    @Nullable
    private static BlockPos findSpot(ServerLevel level, CampData data, ServerPlayer player) {
        int r = CampBuilder.HALF + 1;
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = SPAWN_MIN + random.nextDouble() * (SPAWN_MAX - SPAWN_MIN);
            int x = Mth.floor(player.getX() + Math.cos(angle) * distance);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * distance);
            boolean loaded = true;
            for (int[] c : new int[][]{{-r, -r}, {r, -r}, {-r, r}, {r, r}}) {
                loaded &= level.hasChunk((x + c[0]) >> 4, (z + c[1]) >> 4);
            }
            if (!loaded) {
                continue;
            }
            BlockPos origin = SiteBuilder.ground(level, x, z);
            if (origin == null || !level.isPositionEntityTicking(origin)
                    || level.getNearestPlayer(x + 0.5, origin.getY(), z + 0.5, PRIVACY, false) != null
                    || campNear(data, x, z, GAP) || Places.inSafeZone(level, origin) || Lairs.siteNear(level, x, z, 64)) {
                continue;
            }
            Places.Found place = Places.nearest(level, x, z, null);
            if (place != null && Math.pow(place.pos().getX() - x, 2) + Math.pow(place.pos().getZ() - z, 2) < 64 * 64) {
                continue;
            }
            if (CampBuilder.fits(level, origin)) {
                return origin;
            }
        }
        return null;
    }

    /** A kind from {@code campKinds} that can be spawned here: hostile ones not near the world spawn and only if enemies are on. */
    @Nullable
    private static Kind pickKind(ServerLevel level, BlockPos origin) {
        BlockPos spawn = level.getSharedSpawnPos();
        double safe = RaidConfig.SURVIVAL_SAFE_RADIUS.get() * 2.0;
        boolean nearSpawn = Math.pow(origin.getX() - spawn.getX(), 2) + Math.pow(origin.getZ() - spawn.getZ(), 2) < safe * safe;
        boolean enemies = level.getDifficulty() != Difficulty.PEACEFUL && RaidConfig.ENEMIES_ENABLED.get();
        List<Kind> kinds = new ArrayList<>();
        int total = 0;
        for (String line : RaidConfig.SURVIVAL_CAMP_KINDS.get()) {
            Kind kind = parse(line);
            if (kind == null) {
                continue;
            }
            if (kind.friendly() ? !Places.friendlyAvailable() : (nearSpawn || !enemies || !installed(kind.name()))) {
                continue;
            }
            kinds.add(kind);
            total += kind.weight();
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (Kind kind : kinds) {
            roll -= kind.weight();
            if (roll < 0) {
                return kind;
            }
        }
        return null;
    }

    private static boolean installed(String kind) {
        EnemySpawner.EnemyType type = EnemySpawner.types().get(kind);
        return type != null && type.isInstalled();
    }

    @Nullable
    private static Kind parse(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 4) {
            return null;
        }
        try {
            int weight = Integer.parseInt(parts[1]);
            int min = Math.max(1, Integer.parseInt(parts[2]));
            int max = Math.max(min, Integer.parseInt(parts[3]));
            return weight > 0 ? new Kind(parts[0], weight, min, max) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void announce(ServerLevel level, CampData.Camp camp) {
        int x = camp.origin.getX();
        int z = camp.origin.getZ();
        for (ServerPlayer p : level.players()) {
            int distance = (int) Math.sqrt(Math.pow(p.getX() - x, 2) + Math.pow(p.getZ() - z, 2));
            if (distance >= SIGNAL_DISTANCE) {
                continue;
            }
            Component msg = camp.friendly()
                    ? Component.translatable("zoneraids.camp.signal.friendly", distance, Lairs.direction(p, x, z)).withStyle(ChatFormatting.AQUA)
                    : Component.translatable("zoneraids.camp.signal", kindName(camp.kind), distance, Lairs.direction(p, x, z))
                    .withStyle(ChatFormatting.GOLD);
            p.sendSystemMessage(msg);
        }
    }

    // ------------------------------------------------------------------ waking, deaths, packing up

    /** The camp gets up: everyone off their seat with their AI on; hostile members go for {@code target}. */
    private static void wake(ServerLevel level, CampData data, CampData.Camp camp, @Nullable ServerPlayer target) {
        for (UUID id : camp.members) {
            if (level.getEntity(id) instanceof Mob mob) {
                mob.stopRiding();
                mob.setNoAi(false);
                if (!camp.friendly() && target != null) {
                    mob.setTarget(target);
                }
            }
        }
        discard(level, camp.seats);
        camp.seats.clear();
        camp.state = CampData.State.AWAKE;
        data.setDirty();
        if (camp.friendly()) {
            return;
        }
        Component alarm = Component.translatable("zoneraids.camp.alarm", kindName(camp.kind)).withStyle(ChatFormatting.RED);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(camp.origin.getCenter()) < 48 * 48) {
                p.displayClientMessage(alarm, true);
            }
        }
    }

    /** A camp member died: the last of a hostile camp clears it (a perk point for the killer) and the fire goes out. */
    public static void onDeath(LivingEntity dead, @Nullable Entity killer) {
        CompoundTag tag = dead.getPersistentData();
        if (!tag.contains(TAG_CAMP) || !(dead.level() instanceof ServerLevel level)) {
            return;
        }
        CampData data = CampData.get(level.getServer());
        CampData.Camp camp = data.get(tag.getInt(TAG_CAMP));
        if (camp == null || !camp.members.remove(dead.getUUID())) {
            return;
        }
        data.setDirty();
        if (!camp.members.isEmpty() || camp.state == CampData.State.CLEARED) {
            return;
        }
        camp.state = CampData.State.CLEARED;
        camp.expires = Math.min(camp.expires, level.getGameTime() + CLEARED_TICKS);
        for (CampData.Placed placed : camp.blocks) {
            BlockState state = level.getBlockState(placed.pos());
            if (state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT)) {
                level.setBlock(placed.pos(), state.setValue(CampfireBlock.LIT, false), Block.UPDATE_ALL);
            }
        }
        if (camp.friendly()) {
            return;
        }
        Component msg = Component.translatable("zoneraids.camp.cleared", kindName(camp.kind)).withStyle(ChatFormatting.GREEN);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(dead) < 96 * 96) {
                p.sendSystemMessage(msg);
            }
        }
        if (killer instanceof ServerPlayer player) {
            PerkService.award(player, 1, "zoneraids.perk.reason.camp");
        }
    }

    /** True if no player is near the camp or any of its loaded members. */
    private static boolean unwatched(ServerLevel level, CampData.Camp camp) {
        BlockPos o = camp.origin;
        if (level.getNearestPlayer(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, PRIVACY, false) != null) {
            return false;
        }
        for (UUID id : camp.members) {
            Entity e = level.getEntity(id);
            if (e != null && level.getNearestPlayer(e, PRIVACY) != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Takes the camp away: its blocks (only those still there; chunks are loaded for it if needed)
     * and its loaded members and seats. Members in unloaded chunks are dropped when they load (see
     * {@link #shouldRejectOnLoad}).
     */
    public static void packUp(ServerLevel level, CampData data, CampData.Camp camp) {
        discard(level, camp.members);
        discard(level, camp.seats);
        // Last placed first: the lantern comes off its post before the post goes.
        for (int i = camp.blocks.size() - 1; i >= 0; i--) {
            CampData.Placed placed = camp.blocks.get(i);
            level.getChunk(placed.pos());
            if (level.getBlockState(placed.pos()).is(placed.block())) {
                level.setBlock(placed.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        data.remove(camp.id);
        LOGGER.debug("Zone Raids: {} camp at {} packed up", camp.kind, camp.origin);
    }

    private static void discard(ServerLevel level, List<UUID> ids) {
        for (UUID id : List.copyOf(ids)) {
            Entity e = level.getEntity(id);
            if (e != null) {
                e.discard();
            }
        }
    }

    /** True for a camp member or seat whose camp is gone (saved in a chunk that was not loaded when it packed up). */
    public static boolean shouldRejectOnLoad(Entity entity) {
        CompoundTag tag = entity.getPersistentData();
        if (!tag.contains(TAG_CAMP) || !(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        return CampData.get(level.getServer()).get(tag.getInt(TAG_CAMP)) == null;
    }

    // ------------------------------------------------------------------ friendly camps

    /**
     * A player right-clicked a friendly camp member (not the trader): a line of chat, sometimes a tip
     * about the nearest place or lair. Returns true if the click was handled.
     */
    public static boolean talk(ServerPlayer player, Entity target) {
        if (!isCamper(target) || !(target.level() instanceof ServerLevel level)) {
            return false;
        }
        long now = level.getGameTime();
        Long last = lastTalk.get(player.getUUID());
        if (last != null && now - last < 20) {
            return true;
        }
        lastTalk.put(player.getUUID(), now);
        Component line = random.nextInt(3) == 0 ? tip(level, player) : null;
        if (line == null) {
            line = Component.translatable("zoneraids.camp.chat." + (1 + random.nextInt(CHAT_LINES)));
        }
        player.sendSystemMessage(Component.translatable("zoneraids.camp.say", target.getDisplayName(), line));
        return true;
    }

    /** True for a member of a friendly camp other than its trader (server side). */
    public static boolean isCamper(Entity entity) {
        CompoundTag tag = entity.getPersistentData();
        if (!tag.contains(TAG_CAMP) || Places.isTrader(entity) || entity instanceof ArmorStand || !(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        CampData.Camp camp = CampData.get(level.getServer()).get(tag.getInt(TAG_CAMP));
        return camp != null && camp.friendly();
    }

    @Nullable
    private static Component tip(ServerLevel level, ServerPlayer player) {
        int x = player.getBlockX();
        int z = player.getBlockZ();
        if (random.nextBoolean()) {
            BlockPos lair = Lairs.nearestOpen(level, x, z);
            if (lair != null) {
                int distance = (int) Math.sqrt(Math.pow(lair.getX() - x, 2) + Math.pow(lair.getZ() - z, 2));
                return Component.translatable("zoneraids.camp.tip_lair", distance, Lairs.direction(player, lair.getX(), lair.getZ()));
            }
        }
        Places.Found place = Places.nearest(level, x, z, null);
        if (place == null) {
            return null;
        }
        int distance = (int) Math.sqrt(Math.pow(place.pos().getX() - x, 2) + Math.pow(place.pos().getZ() - z, 2));
        return Component.translatable("zoneraids.camp.tip", place.type().displayName(), distance,
                Lairs.direction(player, place.pos().getX(), place.pos().getZ()));
    }

    /** True near a friendly camp that is still standing, where roaming enemies do not spawn. */
    public static boolean inSafeZone(ServerLevel level, BlockPos pos) {
        if (!enabled() || level != level.getServer().overworld()) {
            return false;
        }
        for (CampData.Camp camp : CampData.get(level.getServer()).all().values()) {
            if (camp.friendly() && camp.state != CampData.State.CLEARED
                    && Math.pow(camp.origin.getX() - pos.getX(), 2) + Math.pow(camp.origin.getZ() - pos.getZ(), 2) < SAFE_RADIUS * SAFE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static boolean campNear(CampData data, int x, int z, int distance) {
        for (CampData.Camp camp : data.all().values()) {
            if (Math.pow(camp.origin.getX() - x, 2) + Math.pow(camp.origin.getZ() - z, 2) < (double) distance * distance) {
                return true;
            }
        }
        return false;
    }

    /** "stalkers", "bandits", "soldiers"; other enemy kinds by their raw name. */
    public static Component kindName(String kind) {
        return switch (kind) {
            case FRIENDLY, "bandit", "military" -> Component.translatable("zoneraids.camp.kind." + kind);
            default -> Component.literal(kind);
        };
    }
}
