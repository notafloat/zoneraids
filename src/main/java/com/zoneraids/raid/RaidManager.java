package com.zoneraids.raid;

import com.zoneraids.RaidConfig;
import com.zoneraids.data.ExtractPoint;
import com.zoneraids.data.HubPoint;
import com.zoneraids.data.PlayerRaidData;
import com.zoneraids.data.PlayerRaidData.Stat;
import com.zoneraids.data.RaidMap;
import com.zoneraids.data.RaidWorldData;
import com.zoneraids.data.SpawnPoint;
import com.zoneraids.menu.ArtifactContainerMenu;
import com.zoneraids.network.RaidHudPacket;
import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.TerminalActionPacket;
import com.zoneraids.network.TerminalDataPacket;
import com.zoneraids.perk.Perk;
import com.zoneraids.perk.PerkService;
import com.zoneraids.registry.ModRegistry;
import com.zoneraids.shop.ShopService;
import com.zoneraids.util.CuriosCompat;
import com.zoneraids.util.FirstAidCompat;
import com.zoneraids.util.Teleports;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side raid logic: matchmaking queues, running sessions and their rounds, extraction and the hub.
 * <p>
 * One instance exists while a server is running; it is dropped on server stop so no state leaks
 * between integrated-server worlds. Every per-player map is keyed by UUID and pruned on
 * logout, extraction, death or session end.
 */
public final class RaidManager {
    private static final int[] TIME_WARNINGS = {600, 300, 120, 60, 30, 10};
    private static final int ACTION_COOLDOWN_TICKS = 4;
    /** Seconds before the next round at which session members in the hub get a countdown title. */
    private static final int[] ROUND_COUNTDOWN = {10, 5, 3, 2, 1};

    @Nullable
    private static RaidManager instance;

    private final MinecraftServer server;
    private final PartyManager parties = new PartyManager();
    private final Map<String, RaidSession> sessions = new HashMap<>();
    /** Players on the map in a round. */
    private final Map<UUID, RaidSession> playerSessions = new HashMap<>();
    /** Every member of a multi-round session, also while waiting in the hub between rounds. */
    private final Map<UUID, RaidSession> rosterSessions = new HashMap<>();
    private final Map<String, LinkedHashSet<UUID>> queues = new HashMap<>();
    private final Map<UUID, String> playerQueue = new HashMap<>();
    private final Map<String, Integer> countdowns = new HashMap<>();
    private final Map<UUID, Long> lastAction = new HashMap<>();
    private final Random random = new Random();
    private final Anomalies anomalies = new Anomalies();
    private final List<ServerPlayer> sessionPlayers = new ArrayList<>();
    private final Spectators spectators;
    private long tickCounter;

    private RaidManager(MinecraftServer server) {
        this.server = server;
        this.spectators = new Spectators(this, server);
    }

    @Nullable
    public static RaidManager get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        instance = new RaidManager(server);
    }

    public static void stop() {
        if (instance != null) {
            instance.sessions.clear();
            instance.playerSessions.clear();
            instance.rosterSessions.clear();
            instance.queues.clear();
            instance.playerQueue.clear();
            instance.countdowns.clear();
            instance.lastAction.clear();
            instance.parties.clear();
            instance.spectators.clear();
        }
        instance = null;
    }

    public RaidWorldData data() {
        return RaidWorldData.get(server);
    }

    public PartyManager parties() {
        return parties;
    }

    public long now() {
        return tickCounter;
    }

    public boolean isInRaid(UUID player) {
        return playerSessions.containsKey(player);
    }

    @Nullable
    public RaidSession sessionOf(UUID player) {
        return playerSessions.get(player);
    }

    /** In a multi-round session, on the map or waiting in the hub for the next round. */
    public boolean isInSession(UUID player) {
        return rosterSessions.containsKey(player);
    }

    @Nullable
    public RaidSession sessionOnMap(String mapId) {
        return sessions.get(mapId);
    }

    @Nullable
    public RaidSession sessionById(UUID id) {
        for (RaidSession session : sessions.values()) {
            if (session.id.equals(id)) {
                return session;
            }
        }
        return null;
    }

    @Nullable
    public String queuedMap(UUID player) {
        return playerQueue.get(player);
    }

    Collection<RaidSession> runningSessions() {
        return sessions.values();
    }

    @Nullable
    RaidSession rosterSessionOf(UUID player) {
        return rosterSessions.get(player);
    }

    /** Watching a raider from the hub terminal (spectator mode on the raid map). */
    public boolean isWatching(UUID player) {
        return spectators.isWatching(player);
    }

    // ------------------------------------------------------------------ ticking

    public void tick() {
        tickCounter++;
        if (tickCounter % 10 == 0) {
            tickSessions(10);
        }
        if (tickCounter % 20 == 0) {
            tickQueues();
            parties.tick(tickCounter);
        }
        spectators.tick();
    }

    private void tickSessions(int dt) {
        List<RaidSession> finished = null;
        for (RaidSession session : sessions.values()) {
            if (session.intermission ? tickIntermission(session, dt) : tickSession(session, dt)) {
                if (finished == null) {
                    finished = new ArrayList<>();
                }
                finished.add(session);
            }
        }
        if (finished != null) {
            for (RaidSession session : finished) {
                if (session.intermission) {
                    startNextRound(session);
                } else {
                    finishRound(session);
                }
            }
        }
    }

    /** @return true when the break is over and the next round should start. */
    private boolean tickIntermission(RaidSession session, int dt) {
        int before = session.ticksLeft;
        session.ticksLeft -= dt;
        for (int warning : ROUND_COUNTDOWN) {
            if (before > warning * 20 && session.ticksLeft <= warning * 20) {
                Component title = Component.translatable("zoneraids.round.countdown", session.round + 1,
                        Component.translatable("zoneraids.round." + session.nextType.id)).withStyle(ChatFormatting.GOLD);
                Component subtitle = Component.literal(String.valueOf(warning)).withStyle(ChatFormatting.YELLOW);
                for (UUID id : session.roster.keySet()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null && !playerSessions.containsKey(id)) {
                        title(player, title, subtitle);
                    }
                }
            }
        }
        if (tickCounter % 20 == 0) {
            for (UUID id : session.roster.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null && !playerSessions.containsKey(id) && !spectators.isWatching(id)) {
                    sendIntermissionHud(player, session);
                }
            }
        }
        return session.ticksLeft <= 0;
    }

    /** @return true when the round should end. */
    private boolean tickSession(RaidSession session, int dt) {
        int before = session.ticksLeft;
        session.ticksLeft -= dt;
        for (int warning : TIME_WARNINGS) {
            if (before > warning * 20 && session.ticksLeft <= warning * 20) {
                Component msg = Component.translatable("zoneraids.raid.time_warning", formatTime(warning)).withStyle(ChatFormatting.GOLD);
                forEachOnline(session, p -> p.sendSystemMessage(msg));
            }
        }

        ServerLevel level = server.getLevel(session.map.dimension);
        int baseNeeded = RaidConfig.EXTRACT_SECONDS.get() * 20;
        for (RaidSession.Participant part : new ArrayList<>(session.participants.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(part.id);
            if (player == null || !player.isAlive() || player.level() != level) {
                part.extracting = null;
                part.extractTicks = 0;
                continue;
            }
            RaidMap map = session.map;
            if (!map.inBounds(player.getX(), player.getZ())) {
                // Walls stop walkers; this also catches grappling hooks, elytra and other ways over them.
                double x = Mth.clamp(player.getX(), map.minX + 1.5, map.maxX - 0.5);
                double z = Mth.clamp(player.getZ(), map.minZ + 1.5, map.maxZ - 0.5);
                // A rider would be pulled straight back out by the vehicle, so off the bike first.
                player.stopRiding();
                player.connection.teleport(x, player.getY(), z, player.getYRot(), player.getXRot());
                player.displayClientMessage(Component.translatable("zoneraids.raid.out_of_bounds").withStyle(ChatFormatting.RED), true);
            }
            int needed = Math.max(20, Math.round(baseNeeded * (1.0F - 0.1F * PerkService.level(player, Perk.ORIENTATION))));
            ExtractPoint zone = null;
            for (ExtractPoint point : part.extracts) {
                if (point.contains(player.position())) {
                    zone = point;
                    break;
                }
            }
            if (zone == null) {
                if (part.extracting != null) {
                    player.displayClientMessage(Component.translatable("zoneraids.raid.extract_cancelled").withStyle(ChatFormatting.RED), true);
                }
                part.extracting = null;
                part.extractTicks = 0;
            } else {
                if (zone != part.extracting) {
                    part.extracting = zone;
                    part.extractTicks = 0;
                }
                part.extractTicks += dt;
                if (part.extractTicks >= needed) {
                    extract(player, session, part);
                    continue;
                }
            }
            part.hudTicks += dt;
            if (part.extracting != null || part.hudTicks >= 20) {
                part.hudTicks = 0;
                sendHud(player, session, part);
            }
        }
        if (level != null) {
            for (RaidSession.Participant part : session.participants.values()) {
                ServerPlayer player = server.getPlayerList().getPlayer(part.id);
                if (player != null) {
                    sessionPlayers.add(player);
                }
            }
            anomalies.tick(session, level, sessionPlayers);
            sessionPlayers.clear();
            RoundObjectives.tick(server, level, session, dt, tickCounter, random);
        }
        if (session.rounds > 1 && tickCounter % 20 == 0) {
            for (UUID id : session.roster.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null && !session.participants.containsKey(id) && !spectators.isWatching(id)) {
                    sendIntermissionHud(player, session);
                }
            }
        }
        if (session.type == RoundType.DEATHMATCH && !lateJoinOpen(session)
                && RoundObjectives.onlineParticipants(server, null, session).size() <= 1) {
            return true;
        }
        // The deathmatch is players only.
        if (level != null && tickCounter % 20 == 0 && session.type != RoundType.DEATHMATCH) {
            List<ServerPlayer> raiders = new ArrayList<>();
            forEachOnline(session, p -> {
                if (p.isAlive() && p.level() == level) {
                    raiders.add(p);
                }
            });
            EnemySpawner.tick(level, session, raiders, level.getRandom());
        }
        return session.ticksLeft <= 0 || (session.participants.isEmpty() && !lateJoinOpen(session));
    }

    /** True while a session member who missed the start of the round can still join it. */
    private boolean lateJoinOpen(RaidSession session) {
        if (session.rounds <= 1 || (session.totalTicks - session.ticksLeft) / 20 >= RaidConfig.LATE_JOIN_SECONDS.get()) {
            return false;
        }
        for (UUID id : session.roster.keySet()) {
            if (!session.playedThisRound.contains(id) && server.getPlayerList().getPlayer(id) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts a session member who was dead or offline when the round began into the round, so nobody
     * has to do anything between rounds. Called on login and respawn.
     */
    public void tryLateJoin(ServerPlayer player) {
        UUID id = player.getUUID();
        RaidSession session = rosterSessions.get(id);
        if (session == null || session.intermission || session.round == 0 || playerSessions.containsKey(id)
                || session.playedThisRound.contains(id) || !player.isAlive() || !lateJoinOpen(session)) {
            return;
        }
        ServerLevel level = server.getLevel(session.map.dimension);
        if (level == null || session.map.spawns.isEmpty()) {
            return;
        }
        // Join the group's teammates where possible: same exits, spawn next to the first one found.
        List<ExtractPoint> assigned = null;
        Integer group = session.groupOf.get(id);
        if (session.type != RoundType.DEATHMATCH && group != null) {
            for (RaidSession.Participant part : session.participants.values()) {
                if (group.equals(session.groupOf.get(part.id))) {
                    assigned = part.extracts;
                    break;
                }
            }
        }
        if (assigned == null) {
            assigned = List.of();
            if (!session.openExtracts.isEmpty()) {
                List<ExtractPoint> shuffled = new ArrayList<>(session.openExtracts);
                Collections.shuffle(shuffled, random);
                assigned = List.copyOf(shuffled.subList(0, Math.max(1, Math.min(session.map.extractsPerPlayer, shuffled.size()))));
            }
        }
        SpawnPoint spawn = session.map.spawns.get(random.nextInt(session.map.spawns.size()));
        enterRound(session, level, player, spawn, assigned);
        session.roundStartSize = Math.max(session.roundStartSize, session.participants.size());
        sendHud(player, session, session.participants.get(id));
    }

    private void tickQueues() {
        Iterator<Map.Entry<String, LinkedHashSet<UUID>>> it = queues.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, LinkedHashSet<UUID>> entry = it.next();
            String mapId = entry.getKey();
            LinkedHashSet<UUID> queue = entry.getValue();

            queue.removeIf(id -> {
                boolean stale = server.getPlayerList().getPlayer(id) == null || playerSessions.containsKey(id)
                        || rosterSessions.containsKey(id);
                if (stale) {
                    playerQueue.remove(id);
                }
                return stale;
            });

            RaidMap map = data().getMap(mapId);
            if (queue.isEmpty() || map == null || !map.isPlayable()) {
                if (!queue.isEmpty()) {
                    Component msg = Component.translatable("zoneraids.queue.map_unavailable", mapId).withStyle(ChatFormatting.RED);
                    queue.forEach(id -> message(id, msg));
                }
                queue.forEach(playerQueue::remove);
                countdowns.remove(mapId);
                it.remove();
                continue;
            }

            RaidSession running = sessions.get(mapId);
            if (running != null) {
                countdowns.remove(mapId);
                actionBar(queue, Component.translatable("zoneraids.queue.busy", map.displayName, formatTime(running.secondsLeft())));
                continue;
            }
            if (queue.size() < map.minPlayers) {
                countdowns.remove(mapId);
                actionBar(queue, Component.translatable("zoneraids.queue.waiting", map.displayName, queue.size(), map.minPlayers));
                continue;
            }

            int countdown = countdowns.getOrDefault(mapId, RaidConfig.LOBBY_COUNTDOWN_SECONDS.get() + 1) - 1;
            if (queue.size() >= map.maxPlayers) {
                countdown = Math.min(countdown, RaidConfig.FULL_LOBBY_COUNTDOWN_SECONDS.get());
            }
            if (countdown > 0) {
                countdowns.put(mapId, countdown);
                actionBar(queue, Component.translatable("zoneraids.queue.countdown", map.displayName, countdown,
                        Math.min(queue.size(), map.maxPlayers), map.maxPlayers).withStyle(ChatFormatting.YELLOW));
                continue;
            }

            countdowns.remove(mapId);
            List<List<UUID>> groups = takeGroups(queue, map.maxPlayers);
            if (queue.isEmpty()) {
                it.remove();
            }
            startSession(map, groups);
        }
    }

    private List<List<UUID>> takeGroups(LinkedHashSet<UUID> queue, int max) {
        List<List<UUID>> groups = new ArrayList<>();
        Set<UUID> taken = new HashSet<>();
        for (UUID id : queue) {
            if (taken.contains(id)) {
                continue;
            }
            List<UUID> group = new ArrayList<>();
            for (UUID member : parties.groupOf(id)) {
                if (queue.contains(member) && !taken.contains(member)) {
                    group.add(member);
                }
            }
            if (!group.contains(id)) {
                group.add(0, id);
            }
            if (taken.size() + group.size() > max) {
                if (!taken.isEmpty()) {
                    continue;
                }
                group = new ArrayList<>(group.subList(0, max));
            }
            taken.addAll(group);
            groups.add(group);
            if (taken.size() >= max) {
                break;
            }
        }
        queue.removeAll(taken);
        taken.forEach(playerQueue::remove);
        return groups;
    }

    // ------------------------------------------------------------------ queue

    public void joinQueue(ServerPlayer player, String mapId) {
        RaidMap map = data().getMap(mapId);
        if (map == null) {
            player.sendSystemMessage(Component.translatable("zoneraids.queue.unknown_map", mapId).withStyle(ChatFormatting.RED));
            return;
        }
        if (!map.isPlayable() || server.getLevel(map.dimension) == null) {
            player.sendSystemMessage(Component.translatable("zoneraids.queue.map_unavailable", map.displayName).withStyle(ChatFormatting.RED));
            return;
        }
        if (isInRaid(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("zoneraids.queue.in_raid").withStyle(ChatFormatting.RED));
            return;
        }
        if (isInSession(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("zoneraids.queue.in_session").withStyle(ChatFormatting.RED));
            return;
        }
        if (!parties.isLeaderOrSolo(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("zoneraids.queue.not_leader").withStyle(ChatFormatting.RED));
            return;
        }
        List<ServerPlayer> group = new ArrayList<>();
        for (UUID id : parties.groupOf(player.getUUID())) {
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            if (member != null && !isInRaid(id) && !isInSession(id)) {
                group.add(member);
            }
        }
        if (group.size() > map.maxPlayers) {
            player.sendSystemMessage(Component.translatable("zoneraids.queue.party_too_big", map.maxPlayers).withStyle(ChatFormatting.RED));
            return;
        }
        LinkedHashSet<UUID> queue = queues.computeIfAbsent(mapId, k -> new LinkedHashSet<>());
        Component msg = Component.translatable("zoneraids.queue.joined", map.displayName).withStyle(ChatFormatting.GREEN);
        for (ServerPlayer member : group) {
            removeFromQueue(member.getUUID());
            queue.add(member.getUUID());
            playerQueue.put(member.getUUID(), mapId);
            member.sendSystemMessage(msg);
        }
        // removeFromQueue may have dropped the set if the player re-queued for the same map.
        queues.put(mapId, queue);
    }

    /** Leaves the queue; a party leaves together so it is never split across raids. */
    public void leaveQueue(ServerPlayer player) {
        if (!playerQueue.containsKey(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("zoneraids.queue.not_queued").withStyle(ChatFormatting.GRAY));
            return;
        }
        Component msg = Component.translatable("zoneraids.queue.left").withStyle(ChatFormatting.GRAY);
        for (UUID id : parties.groupOf(player.getUUID())) {
            if (removeFromQueue(id)) {
                message(id, msg);
            }
        }
    }

    private boolean removeFromQueue(UUID id) {
        String mapId = playerQueue.remove(id);
        if (mapId == null) {
            return false;
        }
        LinkedHashSet<UUID> queue = queues.get(mapId);
        if (queue != null) {
            queue.remove(id);
            if (queue.isEmpty()) {
                queues.remove(mapId);
                countdowns.remove(mapId);
            }
        }
        return true;
    }

    public int queueSize(String mapId) {
        LinkedHashSet<UUID> queue = queues.get(mapId);
        return queue == null ? 0 : queue.size();
    }

    // ------------------------------------------------------------------ sessions

    /** Admin force start: launches whoever is queued, ignoring the minimum player count. */
    public boolean forceStart(String mapId) {
        RaidMap map = data().getMap(mapId);
        LinkedHashSet<UUID> queue = queues.get(mapId);
        if (map == null || !map.isPlayable() || sessions.containsKey(mapId) || queue == null || queue.isEmpty()) {
            return false;
        }
        countdowns.remove(mapId);
        List<List<UUID>> groups = takeGroups(queue, map.maxPlayers);
        if (queue.isEmpty()) {
            queues.remove(mapId);
        }
        return startSession(map, groups);
    }

    private boolean startSession(RaidMap map, List<List<UUID>> groups) {
        ServerLevel level = server.getLevel(map.dimension);
        if (level == null) {
            Component msg = Component.translatable("zoneraids.raid.dimension_missing", map.dimension.location().toString()).withStyle(ChatFormatting.RED);
            groups.forEach(g -> g.forEach(id -> message(id, msg)));
            return false;
        }
        boolean rounds = RaidConfig.ROUNDS.get();
        RaidSession session = new RaidSession(map, rounds ? 3 : 1);
        sessions.put(map.id, session);
        for (List<UUID> group : groups) {
            List<UUID> online = new ArrayList<>();
            for (UUID id : group) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null || !player.isAlive()) {
                    continue;
                }
                online.add(id);
                if (rounds) {
                    session.roster.put(id, player.getGameProfile().getName());
                    session.groupOf.put(id, session.groups.size());
                    rosterSessions.put(id, session);
                    PlayerRaidData.setMatch(player, session.id);
                    giveStarterKit(player);
                    giveStarterContainer(player);
                }
            }
            if (!online.isEmpty()) {
                session.groups.add(online);
            }
        }
        session.nextType = RoundType.EXTRACTION;
        beginRound(session, level, session.groups);
        return true;
    }

    /** Puts the given groups on the map for the session's next round. */
    private void beginRound(RaidSession session, ServerLevel level, List<List<UUID>> groups) {
        RaidMap map = session.map;
        session.intermission = false;
        session.round++;
        session.type = session.nextType;
        session.totalTicks = switch (session.type) {
            case EXTRACTION -> map.durationSeconds * 20;
            case DEATHMATCH -> RaidConfig.DEATHMATCH_MINUTES.get() * 60 * 20;
            default -> RaidConfig.SECOND_ROUND_MINUTES.get() * 60 * 20;
        };
        session.ticksLeft = session.totalTicks;
        // Bumping the generation makes every loot crate in this dimension reroll on next open.
        data().nextGeneration(map.dimension);
        clearDroppedItems(level);
        Anomalies.rollArtifacts(session, random);
        EnemySpawner.sweep(level);

        List<ExtractPoint> open = new ArrayList<>();
        if (session.type.extracts) {
            for (ExtractPoint point : map.extracts) {
                if (random.nextDouble() < point.chance()) {
                    open.add(point);
                }
            }
            if (open.isEmpty()) {
                open.add(map.extracts.get(random.nextInt(map.extracts.size())));
            }
        }
        List<SpawnPoint> spawns = new ArrayList<>(map.spawns);
        Collections.shuffle(spawns, random);

        session.openExtracts = List.copyOf(open);
        session.playedThisRound.clear();
        int spawnIndex = 0;
        for (List<UUID> group : groups) {
            SpawnPoint spawn = spawns.get(spawnIndex++ % spawns.size());
            List<ExtractPoint> assigned = List.of();
            if (!open.isEmpty()) {
                List<ExtractPoint> shuffled = new ArrayList<>(open);
                Collections.shuffle(shuffled, random);
                assigned = List.copyOf(shuffled.subList(0, Math.max(1, Math.min(map.extractsPerPlayer, shuffled.size()))));
            }
            for (UUID id : group) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null && player.isAlive()) {
                    enterRound(session, level, player, spawn, assigned);
                }
            }
        }
        session.roundStartSize = session.participants.size();
        RoundObjectives.setup(server, level, session, random);
        forEachOnline(session, p -> sendHud(p, session, session.participants.get(p.getUUID())));
    }

    /** Puts one player on the map for the session's current round. */
    private void enterRound(RaidSession session, ServerLevel level, ServerPlayer player, SpawnPoint spawn, List<ExtractPoint> assigned) {
        RaidMap map = session.map;
        UUID id = player.getUUID();
        int protection = RaidConfig.SPAWN_PROTECTION_SECONDS.get() * 20;
        int seconds = session.secondsLeft();
        session.playedThisRound.add(id);
        RaidSession.Participant part = new RaidSession.Participant(id, player.getGameProfile().getName(), assigned);
        session.participants.put(id, part);
        playerSessions.put(id, session);
        PlayerRaidData.setSession(player, session.id);
        PlayerRaidData.incrementStat(player, Stat.RAIDS);

        spectators.release(player);
        BlockPos pos = spawn.pos();
        Teleports.teleport(player, level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, spawn.yaw(), 0.0F);
        if (protection > 0) {
            player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, protection, 4, false, false));
        }
        giveRoundKit(player, level);
        if (session.rounds > 1) {
            title(player, Component.translatable("zoneraids.round.title", session.round, session.rounds,
                            Component.translatable("zoneraids.round." + session.type.id)).withStyle(ChatFormatting.RED),
                    Component.translatable("zoneraids.round." + session.type.id + ".subtitle", formatTime(seconds)));
            player.sendSystemMessage(Component.translatable("zoneraids.round." + session.type.id + ".rules").withStyle(ChatFormatting.GOLD));
        } else {
            title(player, Component.translatable("zoneraids.raid.start.title").withStyle(ChatFormatting.RED),
                    Component.translatable("zoneraids.raid.start.subtitle", map.displayName, formatTime(seconds)));
        }
        if (!assigned.isEmpty()) {
            sendExtractList(player, part);
        }
    }

    /** Players still on the map when a round ends go missing, or survive if the round has no extraction. */
    private void finishRound(RaidSession session) {
        boolean keepGear = session.type.survivorsKeepGear;
        boolean lastStanding = session.type == RoundType.DEATHMATCH && session.roundStartSize >= 2
                && RoundObjectives.onlineParticipants(server, null, session).size() == 1;
        RaidWorldData data = data();
        for (RaidSession.Participant part : new ArrayList<>(session.participants.values())) {
            playerSessions.remove(part.id);
            ServerPlayer player = server.getPlayerList().getPlayer(part.id);
            if (player == null) {
                if (!keepGear) {
                    data.addPendingMia(part.id);
                }
                continue;
            }
            PlayerRaidData.setSession(player, null);
            clearHud(player);
            if (!keepGear) {
                applyMia(player);
                continue;
            }
            PlayerRaidData.incrementStat(player, Stat.SURVIVED);
            sendToHub(player);
            if (lastStanding) {
                PerkService.award(player, 2, "zoneraids.perk.reason.winner");
                title(player, Component.translatable("zoneraids.round.winner.title").withStyle(ChatFormatting.GOLD),
                        Component.translatable("zoneraids.round.winner.subtitle"));
            } else {
                PerkService.award(player, 1, "zoneraids.perk.reason.survived");
                title(player, Component.translatable("zoneraids.raid.extracted.title").withStyle(ChatFormatting.GREEN),
                        Component.translatable("zoneraids.round.survived.subtitle", session.map.displayName));
            }
        }
        session.participants.clear();
        if (session.type == RoundType.DOCUMENTS) {
            for (UUID id : session.roster.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) {
                    RoundObjectives.stripDocuments(player);
                }
            }
        }
        ServerLevel level = server.getLevel(session.map.dimension);
        if (level != null) {
            Anomalies.clearArtifacts(session, level);
            EnemySpawner.cleanup(level, session);
            clearDroppedItems(level);
        }
        // Enemy points get a fresh chance to spawn in the next round.
        session.enemyPointsDone.clear();
        if (session.round >= session.rounds || !anyOnline(session.roster.keySet())) {
            endSession(session, false);
            return;
        }
        session.intermission = true;
        session.nextType = session.round + 1 >= session.rounds ? RoundType.DEATHMATCH : pickSecondRound();
        session.totalTicks = RaidConfig.INTERMISSION_SECONDS.get() * 20;
        session.ticksLeft = session.totalTicks;
        Component msg = Component.translatable("zoneraids.round.intermission", session.round, session.round + 1,
                Component.translatable("zoneraids.round." + session.nextType.id), formatTime(session.secondsLeft())).withStyle(ChatFormatting.YELLOW);
        for (UUID id : session.roster.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                player.sendSystemMessage(msg);
                sendIntermissionHud(player, session);
            }
        }
    }

    private RoundType pickSecondRound() {
        List<RoundType> modes = new ArrayList<>();
        for (String id : RaidConfig.SECOND_ROUND_MODES.get()) {
            RoundType type = RoundType.byId(id.trim().toLowerCase());
            if (type == RoundType.SUPPLY_DROP || type == RoundType.EMISSION || type == RoundType.DOCUMENTS) {
                modes.add(type);
            }
        }
        return modes.isEmpty() ? RoundType.EXTRACTION : modes.get(random.nextInt(modes.size()));
    }

    private void startNextRound(RaidSession session) {
        ServerLevel level = server.getLevel(session.map.dimension);
        List<List<UUID>> groups = new ArrayList<>();
        int players = 0;
        for (List<UUID> group : session.groups) {
            List<UUID> ready = new ArrayList<>();
            for (UUID id : group) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null && !playerSessions.containsKey(id)) {
                    players++;
                    // Dead players (still on the respawn screen) join as soon as they respawn.
                    if (player.isAlive()) {
                        ready.add(id);
                    }
                }
            }
            if (session.nextType == RoundType.DEATHMATCH) {
                // Everyone for themselves: every player gets their own spawn point.
                ready.forEach(id -> groups.add(List.of(id)));
            } else if (!ready.isEmpty()) {
                groups.add(ready);
            }
        }
        int needed = session.nextType == RoundType.DEATHMATCH ? 2 : 1;
        // An admin may have cleared the map's spawns or exits during the break; beginRound needs both.
        if (level == null || players < needed || !session.map.isPlayable()) {
            Component msg = Component.translatable("zoneraids.round.not_enough").withStyle(ChatFormatting.YELLOW);
            session.roster.keySet().forEach(id -> message(id, msg));
            endSession(session, false);
            return;
        }
        beginRound(session, level, groups);
    }

    private boolean anyOnline(Iterable<UUID> ids) {
        for (UUID id : ids) {
            if (server.getPlayerList().getPlayer(id) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ends a session. Normal end: everyone still inside is missing in action and loses their gear,
     * then every session member's progress is reset (perks stay).
     * Abort (admin): everyone is returned to the hub with their gear and nothing is reset.
     */
    public void endSession(RaidSession session, boolean abort) {
        sessions.remove(session.map.id, session);
        RaidWorldData data = data();
        for (RaidSession.Participant part : session.participants.values()) {
            playerSessions.remove(part.id);
            ServerPlayer player = server.getPlayerList().getPlayer(part.id);
            if (player == null) {
                if (!abort) {
                    data.addPendingMia(part.id);
                }
                continue;
            }
            PlayerRaidData.setSession(player, null);
            clearHud(player);
            if (abort) {
                player.sendSystemMessage(Component.translatable("zoneraids.raid.aborted").withStyle(ChatFormatting.YELLOW));
                sendToHub(player);
            } else {
                applyMia(player);
            }
        }
        session.participants.clear();
        for (UUID id : session.roster.keySet()) {
            rosterSessions.remove(id, session);
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                // Reset on the next login: the player's match tag no longer names a running session.
                continue;
            }
            clearHud(player);
            RoundObjectives.stripDocuments(player);
            if (abort) {
                PlayerRaidData.setMatch(player, null);
            } else {
                PerkService.award(player, 1, "zoneraids.perk.reason.session");
                resetProgress(player);
            }
        }
        session.roster.clear();
        session.groups.clear();
        session.groupOf.clear();
        ServerLevel level = server.getLevel(session.map.dimension);
        if (level != null) {
            Anomalies.clearArtifacts(session, level);
            EnemySpawner.cleanup(level, session);
            clearDroppedItems(level);
        }
    }

    public boolean stopSession(String mapId) {
        RaidSession session = sessions.get(mapId);
        if (session == null) {
            return false;
        }
        endSession(session, true);
        return true;
    }

    /** End of a session: everything gathered in it is gone, perks and stats stay (money too, unless configured otherwise). */
    private void resetProgress(ServerPlayer player) {
        wipeInventory(player);
        player.getEnderChestInventory().clearContent();
        PlayerRaidData.clearStash(player);
        if (!RaidConfig.KEEP_MONEY_AFTER_SESSION.get()) {
            PlayerRaidData.setMoney(player, 0);
        }
        PlayerRaidData.setMatch(player, null);
        if (player.isAlive()) {
            player.removeAllEffects();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
            if (player.level().dimension().equals(hubDimension())) {
                giveMap(player);
            } else {
                sendToHub(player);
            }
        } else {
            PlayerRaidData.markReturnToHub(player);
        }
        title(player, Component.translatable("zoneraids.session.over.title").withStyle(ChatFormatting.GOLD),
                Component.translatable("zoneraids.session.over.subtitle"));
    }

    private static void giveStarterKit(ServerPlayer player) {
        for (String entry : RaidConfig.STARTER_KIT.get()) {
            String[] parts = entry.trim().split("\\s+");
            ResourceLocation id = ResourceLocation.tryParse(parts[0]);
            Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            int count = 1;
            if (parts.length > 1) {
                try {
                    count = Math.max(1, Integer.parseInt(parts[1]));
                } catch (NumberFormatException ignored) {
                }
            }
            while (count > 0) {
                int n = Math.min(count, item.getMaxStackSize());
                count -= n;
                ItemStack stack = new ItemStack(item, n);
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            }
        }
    }

    /** An artifact container (config {@code starterContainer}), unless the player has one in the inventory or a Curios slot. */
    private static void giveStarterContainer(ServerPlayer player) {
        ResourceLocation id = ResourceLocation.tryParse(RaidConfig.STARTER_CONTAINER.get().trim());
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR || player.getInventory().hasAnyOf(Set.of(item))
                || CuriosCompat.has(player, item)) {
            return;
        }
        ItemStack stack = new ItemStack(item);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /**
     * Basic gear for the round from {@code roundKitLootTable}. Armor is put on only where the slot is
     * empty and a single item (a gun) only if the player has none of it, so the kit fills what a
     * player lacks instead of piling up over the rounds; stackable items are always handed out.
     */
    private static void giveRoundKit(ServerPlayer player, ServerLevel level) {
        String name = RaidConfig.ROUND_KIT_LOOT_TABLE.get().trim();
        ResourceLocation id = name.isEmpty() ? null : ResourceLocation.tryParse(name);
        if (id == null) {
            return;
        }
        LootTable table = level.getServer().getLootData().getLootTable(id);
        if (table == LootTable.EMPTY) {
            return;
        }
        for (ItemStack stack : table.getRandomItems(new LootParams.Builder(level).create(LootContextParamSets.EMPTY))) {
            EquipmentSlot slot = Mob.getEquipmentSlotForItem(stack);
            if (slot.getType() == EquipmentSlot.Type.ARMOR) {
                if (player.getItemBySlot(slot).isEmpty()) {
                    player.setItemSlot(slot, stack);
                }
                continue;
            }
            if (!stack.isStackable() && player.getInventory().hasAnyOf(Set.of(stack.getItem()))) {
                continue;
            }
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }

    private void extract(ServerPlayer player, RaidSession session, RaidSession.Participant part) {
        session.participants.remove(part.id);
        playerSessions.remove(part.id);
        PlayerRaidData.setSession(player, null);
        PlayerRaidData.incrementStat(player, Stat.SURVIVED);
        clearHud(player);
        boolean docs = RoundObjectives.stripDocuments(player);
        sendToHub(player);
        int spent = (session.totalTicks - session.ticksLeft) / 20;
        title(player, Component.translatable("zoneraids.raid.extracted.title").withStyle(ChatFormatting.GREEN),
                Component.translatable("zoneraids.raid.extracted.subtitle", session.map.displayName, formatTime(spent)));
        PerkService.award(player, 1, "zoneraids.perk.reason.extracted");
        if (docs) {
            PerkService.award(player, 2, "zoneraids.perk.reason.documents");
            Component msg = Component.translatable("zoneraids.round.documents.delivered", player.getDisplayName()).withStyle(ChatFormatting.GOLD);
            forEachOnline(session, p -> p.sendSystemMessage(msg));
        }
        if (session.rounds > 1) {
            sendIntermissionHud(player, session);
        }
    }

    private void applyMia(ServerPlayer player) {
        wipeInventory(player);
        PlayerRaidData.incrementStat(player, Stat.MIA);
        if (player.isAlive()) {
            sendToHub(player);
        } else {
            PlayerRaidData.markReturnToHub(player);
        }
        title(player, Component.translatable("zoneraids.raid.mia.title").withStyle(ChatFormatting.DARK_RED),
                Component.translatable("zoneraids.raid.mia.subtitle"));
    }

    private static void wipeInventory(ServerPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.doCloseContainer();
        }
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.getCraftSlots().clearContent();
        player.getInventory().clearContent();
        CuriosCompat.clear(player);
        player.inventoryMenu.broadcastChanges();
    }

    private void clearDroppedItems(ServerLevel level) {
        if (!RaidConfig.CLEAR_DROPPED_ITEMS_ON_END.get() || level.dimension().equals(hubDimension())) {
            return;
        }
        List<Entity> items = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity) {
                items.add(entity);
            }
        }
        items.forEach(Entity::discard);
    }

    // ------------------------------------------------------------------ player lifecycle

    public void onLogin(ServerPlayer player) {
        UUID id = player.getUUID();
        spectators.onLogin(player);
        UUID match = PlayerRaidData.getMatch(player);
        RaidSession rosterSession = rosterSessions.get(id);
        if (match != null && (rosterSession == null || !rosterSession.id.equals(match))) {
            // The session ended (or the server restarted) while the player was away: reset like everyone else.
            PlayerRaidData.setSession(player, null);
            data().consumePendingMia(id);
            RoundObjectives.stripDocuments(player);
            player.sendSystemMessage(Component.translatable("zoneraids.session.over_offline").withStyle(ChatFormatting.YELLOW));
            resetProgress(player);
            return;
        }
        if (!isInRaid(id)) {
            RoundObjectives.stripDocuments(player);
        }
        if (rosterSession != null && !isInRaid(id)) {
            sendIntermissionHud(player, rosterSession);
        }
        UUID tag = PlayerRaidData.getSession(player);
        RaidSession session = playerSessions.get(id);
        if (session != null && session.id.equals(tag)) {
            player.sendSystemMessage(Component.translatable("zoneraids.raid.resumed", session.map.displayName).withStyle(ChatFormatting.YELLOW));
            RaidSession.Participant part = session.participants.get(id);
            if (part != null) {
                sendHud(player, session, part);
            }
            return;
        }
        boolean pendingMia = data().consumePendingMia(id);
        if (tag == null) {
            sendToHubIfOutside(player);
            return;
        }
        PlayerRaidData.setSession(player, null);
        if (pendingMia || !RaidConfig.RETURN_GEAR_AFTER_RESTART.get()) {
            applyMia(player);
        } else {
            player.sendSystemMessage(Component.translatable("zoneraids.raid.interrupted").withStyle(ChatFormatting.YELLOW));
            if (player.isAlive()) {
                sendToHub(player);
            } else {
                PlayerRaidData.markReturnToHub(player);
            }
        }
    }

    public void onLogout(ServerPlayer player) {
        UUID id = player.getUUID();
        // Close menus before the player file is written (vanilla closes them only after saving),
        // so stash/crate contents and the inventory are saved consistently.
        if (player.containerMenu != player.inventoryMenu) {
            player.doCloseContainer();
        }
        removeFromQueue(id);
        parties.onLogout(id);
        lastAction.remove(id);
        spectators.onLogout(player);
        RaidSession session = playerSessions.get(id);
        if (session != null) {
            RaidSession.Participant part = session.participants.get(id);
            if (part != null) {
                part.extracting = null;
                part.extractTicks = 0;
            }
        }
    }

    public void onDeath(ServerPlayer player, DamageSource source) {
        if (player.containerMenu != player.inventoryMenu) {
            player.doCloseContainer();
        }
        if (source.getEntity() instanceof ServerPlayer killer && killer != player && isInRaid(killer.getUUID())) {
            PlayerRaidData.incrementStat(killer, Stat.KILLS);
            RaidSession killerSession = playerSessions.get(killer.getUUID());
            Integer killerGroup = killerSession.groupOf.get(killer.getUUID());
            boolean teammate = killerSession.type != RoundType.DEATHMATCH && killerGroup != null
                    && killerGroup.equals(killerSession.groupOf.get(player.getUUID()));
            if (!teammate) {
                PerkService.award(killer, 1, "zoneraids.perk.reason.kill");
            }
        }
        UUID id = player.getUUID();
        RaidSession session = playerSessions.remove(id);
        if (session == null) {
            return;
        }
        session.participants.remove(id);
        if (RaidConfig.FORCE_DROP_ON_RAID_DEATH.get() && player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            player.getInventory().dropAll();
        }
        PlayerRaidData.setSession(player, null);
        PlayerRaidData.incrementStat(player, Stat.KIA);
        PlayerRaidData.markReturnToHub(player);
        clearHud(player);
        Component killerName = source.getEntity() != null ? source.getEntity().getDisplayName() : Component.translatable("zoneraids.raid.kia.unknown");
        title(player, Component.translatable("zoneraids.raid.kia.title").withStyle(ChatFormatting.DARK_RED),
                Component.translatable("zoneraids.raid.kia.subtitle", killerName));
    }

    public void onRespawn(ServerPlayer player) {
        if (PlayerRaidData.consumeReturnToHub(player)) {
            sendToHub(player);
        } else {
            sendToHubIfOutside(player);
        }
        RaidSession rosterSession = rosterSessions.get(player.getUUID());
        if (rosterSession != null) {
            sendIntermissionHud(player, rosterSession);
        }
    }

    // ------------------------------------------------------------------ hub

    public ResourceKey<Level> hubDimension() {
        HubPoint hub = data().getHub();
        return hub != null && server.getLevel(hub.dimension()) != null ? hub.dimension() : Level.OVERWORLD;
    }

    /** With {@code forceHubOnLogin}, moves a player who is not in a raid into the hub dimension. */
    public void sendToHubIfOutside(ServerPlayer player) {
        if (!RaidConfig.FORCE_HUB_ON_LOGIN.get() || isInRaid(player.getUUID()) || player.isCreative() || player.isSpectator()) {
            return;
        }
        HubPoint hub = data().getHub();
        if (hub == null || server.getLevel(hub.dimension()) == null) {
            return;
        }
        if (player.level().dimension().equals(hub.dimension())) {
            giveMap(player);
            return;
        }
        sendToHub(player);
    }

    /** Players in the hub always have a Cordon map; it can be lost in a raid like any other item. */
    private static void giveMap(ServerPlayer player) {
        if (!RaidConfig.GIVE_MAP_IN_HUB.get() || player.isCreative() || player.isSpectator()) {
            return;
        }
        ItemStack map = new ItemStack(ModRegistry.CORDON_MAP.get());
        if (!player.getInventory().contains(map)) {
            player.getInventory().add(map);
        }
    }

    public void sendToHub(ServerPlayer player) {
        spectators.release(player);
        HubPoint hub = data().getHub();
        ServerLevel level = hub == null ? null : server.getLevel(hub.dimension());
        if (hub != null && level != null) {
            Teleports.teleport(player, level, hub.x(), hub.y(), hub.z(), hub.yaw(), hub.pitch());
            giveMap(player);
            return;
        }
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
        Teleports.teleport(player, overworld, spawn.getX() + 0.5, y, spawn.getZ() + 0.5, 0.0F, 0.0F);
    }

    // ------------------------------------------------------------------ terminal (GUI)

    public void openTerminal(ServerPlayer player) {
        RaidNetwork.sendTo(player, buildTerminal(player, true));
    }

    public void handleAction(ServerPlayer player, TerminalActionPacket.Action action, String arg) {
        long last = lastAction.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (tickCounter - last < ACTION_COOLDOWN_TICKS && action != TerminalActionPacket.Action.OPEN) {
            return;
        }
        lastAction.put(player.getUUID(), tickCounter);
        switch (action) {
            case OPEN -> {
                openTerminal(player);
                return;
            }
            case REFRESH -> {
            }
            case JOIN -> joinQueue(player, arg);
            case LEAVE_QUEUE -> leaveQueue(player);
            case STASH -> {
                if (RaidConfig.REMOTE_STASH.get()) {
                    StashService.open(player);
                } else {
                    player.displayClientMessage(Component.translatable("zoneraids.stash.remote_disabled"), true);
                }
                return;
            }
            case LEAVE_PARTY -> leaveParty(player);
            case BUY_PERK -> {
                Perk perk = Perk.byId(arg);
                if (perk != null) {
                    PerkService.buy(player, perk);
                }
            }
            case ACCEPT_INVITE -> acceptInvite(player);
            case WATCH -> spectators.watch(player, arg);
            case STOP_WATCH -> spectators.stop(player);
            case SHOP -> {
                ShopService.open(player, false);
                return;
            }
            case SHOP_BUY -> {
                ShopService.buy(player, arg);
                return;
            }
            case SHOP_SELL -> {
                ShopService.sell(player, arg, false);
                return;
            }
            case SHOP_SELL_ALL -> {
                ShopService.sell(player, arg, true);
                return;
            }
            case CONTAINER -> {
                ArtifactContainerMenu.open(player);
                return;
            }
            case HEAL -> {
                String[] parts = arg.split(" ");
                if (parts.length == 2) {
                    try {
                        FirstAidCompat.heal(player, Integer.parseInt(parts[0]), FirstAidCompat.Part.valueOf(parts[1]));
                    } catch (IllegalArgumentException ignored) {
                        // Malformed request: nothing to do.
                    }
                }
                return;
            }
        }
        RaidNetwork.sendTo(player, buildTerminal(player, false));
    }

    private TerminalDataPacket buildTerminal(ServerPlayer player, boolean open) {
        UUID id = player.getUUID();
        List<TerminalDataPacket.MapEntry> entries = new ArrayList<>();
        for (RaidMap map : data().maps()) {
            RaidSession session = sessions.get(map.id);
            Integer countdown = countdowns.get(map.id);
            byte state;
            int seconds = 0;
            if (!map.isPlayable() || server.getLevel(map.dimension) == null) {
                state = TerminalDataPacket.STATE_UNAVAILABLE;
            } else if (session != null) {
                state = TerminalDataPacket.STATE_RUNNING;
                seconds = session.secondsLeft();
            } else if (countdown != null) {
                state = TerminalDataPacket.STATE_COUNTDOWN;
                seconds = countdown;
            } else {
                state = TerminalDataPacket.STATE_IDLE;
            }
            entries.add(new TerminalDataPacket.MapEntry(map.id, map.displayName, state, queueSize(map.id),
                    session == null ? 0 : session.size(), map.minPlayers, map.maxPlayers, seconds, map.durationSeconds));
        }
        int[] stats = new int[Stat.values().length];
        for (Stat stat : Stat.values()) {
            stats[stat.ordinal()] = PlayerRaidData.getStat(player, stat);
        }
        List<String> party = new ArrayList<>();
        PartyManager.Party p = parties.get(id);
        if (p != null) {
            for (UUID member : p.members()) {
                ServerPlayer online = server.getPlayerList().getPlayer(member);
                String name = online == null ? "?" : online.getGameProfile().getName();
                party.add(member.equals(p.leader()) ? "★ " + name : name);
            }
        }
        UUID inviter = parties.pendingInviter(id, tickCounter);
        ServerPlayer inviterPlayer = inviter == null ? null : server.getPlayerList().getPlayer(inviter);
        int[] perks = new int[Perk.values().length];
        for (Perk perk : Perk.values()) {
            perks[perk.ordinal()] = PerkService.level(player, perk);
        }
        String watching = spectators.targetName(id);
        return new TerminalDataPacket(open, entries, playerQueue.getOrDefault(id, ""), isInRaid(id), stats, party,
                parties.isLeaderOrSolo(id), inviterPlayer == null ? "" : inviterPlayer.getGameProfile().getName(),
                RaidConfig.REMOTE_STASH.get() && !isInRaid(id), PlayerRaidData.getPerkPoints(player), perks,
                spectators.entries(player), watching == null ? "" : watching,
                RaidConfig.SHOP.get() && RaidConfig.REMOTE_SHOP.get() && !isInRaid(id));
    }

    // ------------------------------------------------------------------ parties

    public void invite(ServerPlayer inviter, ServerPlayer target) {
        if (inviter == target) {
            return;
        }
        if (!parties.isLeaderOrSolo(inviter.getUUID())) {
            inviter.sendSystemMessage(Component.translatable("zoneraids.party.not_leader").withStyle(ChatFormatting.RED));
            return;
        }
        PartyManager.Party party = parties.get(inviter.getUUID());
        if (party != null && party.members().size() >= RaidConfig.MAX_PARTY_SIZE.get()) {
            inviter.sendSystemMessage(Component.translatable("zoneraids.party.full").withStyle(ChatFormatting.RED));
            return;
        }
        parties.invite(inviter.getUUID(), target.getUUID(), tickCounter, RaidConfig.PARTY_INVITE_SECONDS.get() * 20L);
        inviter.sendSystemMessage(Component.translatable("zoneraids.party.invite_sent", target.getDisplayName()).withStyle(ChatFormatting.GREEN));
        target.sendSystemMessage(Component.translatable("zoneraids.party.invite_received", inviter.getDisplayName()).withStyle(ChatFormatting.AQUA));
    }

    public void acceptInvite(ServerPlayer player) {
        UUID inviter = parties.pendingInviter(player.getUUID(), tickCounter);
        PartyManager.Party target = inviter == null ? null : parties.get(inviter);
        if (inviter != null && target != null && target.members().size() >= RaidConfig.MAX_PARTY_SIZE.get()) {
            player.sendSystemMessage(Component.translatable("zoneraids.party.full").withStyle(ChatFormatting.RED));
            return;
        }
        removeFromQueue(player.getUUID());
        PartyManager.Party party = parties.accept(player.getUUID(), tickCounter);
        if (party == null) {
            player.sendSystemMessage(Component.translatable("zoneraids.party.no_invite").withStyle(ChatFormatting.RED));
            return;
        }
        Component msg = Component.translatable("zoneraids.party.joined", player.getDisplayName()).withStyle(ChatFormatting.GREEN);
        party.members().forEach(id -> message(id, msg));
    }

    public void leaveParty(ServerPlayer player) {
        removeFromQueue(player.getUUID());
        PartyManager.Party party = parties.leave(player.getUUID());
        if (party == null) {
            player.sendSystemMessage(Component.translatable("zoneraids.party.not_in_party").withStyle(ChatFormatting.GRAY));
            return;
        }
        player.sendSystemMessage(Component.translatable("zoneraids.party.left").withStyle(ChatFormatting.GRAY));
        Component msg = Component.translatable("zoneraids.party.member_left", player.getDisplayName()).withStyle(ChatFormatting.GRAY);
        party.members().forEach(id -> message(id, msg));
    }

    public void kick(ServerPlayer leader, ServerPlayer target) {
        PartyManager.Party party = parties.get(leader.getUUID());
        if (party == null || !party.leader().equals(leader.getUUID()) || !party.members().contains(target.getUUID()) || leader == target) {
            leader.sendSystemMessage(Component.translatable("zoneraids.party.not_leader").withStyle(ChatFormatting.RED));
            return;
        }
        leaveParty(target);
    }

    // ------------------------------------------------------------------ HUD / messages

    public void sendExtractList(ServerPlayer player, RaidSession.Participant part) {
        player.sendSystemMessage(Component.translatable("zoneraids.raid.extracts.header").withStyle(ChatFormatting.GOLD));
        for (ExtractPoint point : part.extracts) {
            int dist = (int) Math.sqrt(point.pos().distToCenterSqr(player.position()));
            player.sendSystemMessage(RaidConfig.SHOW_EXTRACT_DISTANCE.get()
                    ? Component.translatable("zoneraids.raid.extracts.entry_dist", point.name(), dist)
                    : Component.translatable("zoneraids.raid.extracts.entry", point.name()));
        }
    }

    public void showExtracts(ServerPlayer player) {
        RaidSession session = playerSessions.get(player.getUUID());
        RaidSession.Participant part = session == null ? null : session.participants.get(player.getUUID());
        if (part == null) {
            player.sendSystemMessage(Component.translatable("zoneraids.raid.not_in_raid").withStyle(ChatFormatting.GRAY));
            return;
        }
        sendExtractList(player, part);
    }

    private void sendHud(ServerPlayer player, RaidSession session, RaidSession.Participant part) {
        sendHud(player, player, session, part);
    }

    /** The raid HUD of {@code subject}, sent to {@code to} (the raider, or someone watching them). */
    private void sendHud(ServerPlayer to, ServerPlayer player, RaidSession session, RaidSession.Participant part) {
        boolean showPos = RaidConfig.SHOW_EXTRACT_DISTANCE.get();
        List<RaidHudPacket.ExtractEntry> extracts = new ArrayList<>(part.extracts.size());
        for (ExtractPoint point : part.extracts) {
            BlockPos pos = point.pos();
            extracts.add(new RaidHudPacket.ExtractEntry(point.name(), showPos, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        }
        int needed = Math.max(20, Math.round(RaidConfig.EXTRACT_SECONDS.get() * 20 * (1.0F - 0.1F * PerkService.level(player, Perk.ORIENTATION))));
        float progress = part.extracting == null ? -1.0F : Math.min(1.0F, part.extractTicks / (float) needed);
        RoundObjectives.Objective objective = RoundObjectives.objective(session, player);
        RaidHudPacket.Round round = new RaidHudPacket.Round(session.round, session.rounds, session.type.id, false,
                objective.hasTarget(), objective.x(), objective.z(), objective.label(), objective.status(), objective.progress());
        RaidNetwork.sendTo(to, new RaidHudPacket(true, session.map.displayName, session.secondsLeft(), extracts,
                part.extracting == null ? "" : part.extracting.name(), progress, round));
    }

    /** Someone watching a raider sees the raider's HUD. */
    void mirrorHud(ServerPlayer viewer, ServerPlayer target) {
        RaidSession session = playerSessions.get(target.getUUID());
        RaidSession.Participant part = session == null ? null : session.participants.get(target.getUUID());
        if (part != null) {
            sendHud(viewer, target, session, part);
        }
    }

    /** Back from watching: the player's own HUD again, if they are waiting for the next round of a session. */
    void afterWatch(ServerPlayer player) {
        RaidSession rosterSession = rosterSessions.get(player.getUUID());
        if (isInRaid(player.getUUID())) {
            return;
        }
        if (rosterSession != null) {
            sendIntermissionHud(player, rosterSession);
        } else {
            clearHud(player);
        }
    }

    /** HUD for session members in the hub: the break before the next round, or the current round still running. */
    private void sendIntermissionHud(ServerPlayer player, RaidSession session) {
        if (session.rounds <= 1) {
            return;
        }
        RaidHudPacket.Round round = session.intermission
                ? new RaidHudPacket.Round(session.round + 1, session.rounds, session.nextType.id, true, false, 0, 0,
                Component.empty(), Component.translatable("zoneraids.hud.next_round"), -1.0F)
                : new RaidHudPacket.Round(session.round, session.rounds, session.type.id, true, false, 0, 0,
                Component.empty(), Component.translatable("zoneraids.hud.wait_round"), -1.0F);
        RaidNetwork.sendTo(player, new RaidHudPacket(true, session.map.displayName, session.secondsLeft(), List.of(), "", -1.0F, round));
    }

    private static void clearHud(ServerPlayer player) {
        RaidNetwork.sendTo(player, RaidHudPacket.inactive());
    }

    private void forEachOnline(RaidSession session, java.util.function.Consumer<ServerPlayer> action) {
        for (UUID id : session.participants.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                action.accept(player);
            }
        }
    }

    private void message(UUID id, Component msg) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) {
            player.sendSystemMessage(msg);
        }
    }

    private void actionBar(Iterable<UUID> ids, Component msg) {
        for (UUID id : ids) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                player.displayClientMessage(msg, true);
            }
        }
    }

    private static void title(ServerPlayer player, Component title, Component subtitle) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        player.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    public static String formatTime(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
