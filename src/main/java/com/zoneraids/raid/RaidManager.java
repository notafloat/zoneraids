package com.zoneraids.raid;

import com.zoneraids.RaidConfig;
import com.zoneraids.data.ExtractPoint;
import com.zoneraids.data.HubPoint;
import com.zoneraids.data.PlayerRaidData;
import com.zoneraids.data.PlayerRaidData.Stat;
import com.zoneraids.data.RaidMap;
import com.zoneraids.data.RaidWorldData;
import com.zoneraids.data.SpawnPoint;
import com.zoneraids.network.RaidHudPacket;
import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.TerminalActionPacket;
import com.zoneraids.network.TerminalDataPacket;
import com.zoneraids.registry.ModRegistry;
import com.zoneraids.util.Teleports;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
 * Server-side raid logic: matchmaking queues, running sessions, extraction and the hub.
 * <p>
 * One instance exists while a server is running; it is dropped on server stop so no state leaks
 * between integrated-server worlds. Every per-player map is keyed by UUID and pruned on
 * logout, extraction, death or session end.
 */
public final class RaidManager {
    private static final int[] TIME_WARNINGS = {600, 300, 120, 60, 30, 10};
    private static final int ACTION_COOLDOWN_TICKS = 4;

    @Nullable
    private static RaidManager instance;

    private final MinecraftServer server;
    private final PartyManager parties = new PartyManager();
    private final Map<String, RaidSession> sessions = new HashMap<>();
    private final Map<UUID, RaidSession> playerSessions = new HashMap<>();
    private final Map<String, LinkedHashSet<UUID>> queues = new HashMap<>();
    private final Map<UUID, String> playerQueue = new HashMap<>();
    private final Map<String, Integer> countdowns = new HashMap<>();
    private final Map<UUID, Long> lastAction = new HashMap<>();
    private final Random random = new Random();
    private long tickCounter;

    private RaidManager(MinecraftServer server) {
        this.server = server;
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
            instance.queues.clear();
            instance.playerQueue.clear();
            instance.countdowns.clear();
            instance.lastAction.clear();
            instance.parties.clear();
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
    }

    private void tickSessions(int dt) {
        List<RaidSession> finished = null;
        for (RaidSession session : sessions.values()) {
            if (tickSession(session, dt)) {
                if (finished == null) {
                    finished = new ArrayList<>();
                }
                finished.add(session);
            }
        }
        if (finished != null) {
            finished.forEach(s -> endSession(s, false));
        }
    }

    /** @return true when the session should end. */
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
        int needed = RaidConfig.EXTRACT_SECONDS.get() * 20;
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
                player.connection.teleport(x, player.getY(), z, player.getYRot(), player.getXRot());
                player.displayClientMessage(Component.translatable("zoneraids.raid.out_of_bounds").withStyle(ChatFormatting.RED), true);
            }
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
        if (level != null && tickCounter % 20 == 0) {
            List<ServerPlayer> raiders = new ArrayList<>();
            forEachOnline(session, p -> {
                if (p.isAlive() && p.level() == level) {
                    raiders.add(p);
                }
            });
            EnemySpawner.tick(level, session, raiders, level.getRandom());
        }
        return session.ticksLeft <= 0 || session.participants.isEmpty();
    }

    private void tickQueues() {
        Iterator<Map.Entry<String, LinkedHashSet<UUID>>> it = queues.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, LinkedHashSet<UUID>> entry = it.next();
            String mapId = entry.getKey();
            LinkedHashSet<UUID> queue = entry.getValue();

            queue.removeIf(id -> {
                boolean stale = server.getPlayerList().getPlayer(id) == null || playerSessions.containsKey(id);
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
        if (!parties.isLeaderOrSolo(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("zoneraids.queue.not_leader").withStyle(ChatFormatting.RED));
            return;
        }
        List<ServerPlayer> group = new ArrayList<>();
        for (UUID id : parties.groupOf(player.getUUID())) {
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            if (member != null && !isInRaid(id)) {
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
        RaidSession session = new RaidSession(map);
        sessions.put(map.id, session);
        // Bumping the generation makes every loot crate in this dimension reroll on next open.
        data().nextGeneration(map.dimension);
        clearDroppedItems(level);
        EnemySpawner.sweep(level);

        List<ExtractPoint> open = new ArrayList<>();
        for (ExtractPoint point : map.extracts) {
            if (random.nextDouble() < point.chance()) {
                open.add(point);
            }
        }
        if (open.isEmpty()) {
            open.add(map.extracts.get(random.nextInt(map.extracts.size())));
        }
        List<SpawnPoint> spawns = new ArrayList<>(map.spawns);
        Collections.shuffle(spawns, random);

        int spawnIndex = 0;
        int protection = RaidConfig.SPAWN_PROTECTION_SECONDS.get() * 20;
        for (List<UUID> group : groups) {
            SpawnPoint spawn = spawns.get(spawnIndex++ % spawns.size());
            List<ExtractPoint> shuffled = new ArrayList<>(open);
            Collections.shuffle(shuffled, random);
            List<ExtractPoint> assigned = List.copyOf(shuffled.subList(0, Math.max(1, Math.min(map.extractsPerPlayer, shuffled.size()))));
            for (UUID id : group) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null || !player.isAlive()) {
                    continue;
                }
                RaidSession.Participant part = new RaidSession.Participant(id, player.getGameProfile().getName(), assigned);
                session.participants.put(id, part);
                playerSessions.put(id, session);
                PlayerRaidData.setSession(player, session.id);
                PlayerRaidData.incrementStat(player, Stat.RAIDS);

                BlockPos pos = spawn.pos();
                Teleports.teleport(player, level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, spawn.yaw(), 0.0F);
                if (protection > 0) {
                    player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, protection, 4, false, false));
                }
                title(player, Component.translatable("zoneraids.raid.start.title").withStyle(ChatFormatting.RED),
                        Component.translatable("zoneraids.raid.start.subtitle", map.displayName, formatTime(map.durationSeconds)));
                sendExtractList(player, part);
                sendHud(player, session, part);
            }
        }
        return true;
    }

    /**
     * Ends a session. Normal end: everyone still inside is missing in action and loses their gear.
     * Abort (admin): everyone is returned to the hub with their gear.
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
        ServerLevel level = server.getLevel(session.map.dimension);
        if (level != null) {
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

    private void extract(ServerPlayer player, RaidSession session, RaidSession.Participant part) {
        session.participants.remove(part.id);
        playerSessions.remove(part.id);
        PlayerRaidData.setSession(player, null);
        PlayerRaidData.incrementStat(player, Stat.SURVIVED);
        clearHud(player);
        sendToHub(player);
        int spent = (session.totalTicks - session.ticksLeft) / 20;
        title(player, Component.translatable("zoneraids.raid.extracted.title").withStyle(ChatFormatting.GREEN),
                Component.translatable("zoneraids.raid.extracted.subtitle", session.map.displayName, formatTime(spent)));
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
            case ACCEPT_INVITE -> acceptInvite(player);
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
        return new TerminalDataPacket(open, entries, playerQueue.getOrDefault(id, ""), isInRaid(id), stats, party,
                parties.isLeaderOrSolo(id), inviterPlayer == null ? "" : inviterPlayer.getGameProfile().getName(),
                RaidConfig.REMOTE_STASH.get() && !isInRaid(id));
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
        boolean showPos = RaidConfig.SHOW_EXTRACT_DISTANCE.get();
        List<RaidHudPacket.ExtractEntry> extracts = new ArrayList<>(part.extracts.size());
        for (ExtractPoint point : part.extracts) {
            BlockPos pos = point.pos();
            extracts.add(new RaidHudPacket.ExtractEntry(point.name(), showPos, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        }
        float progress = part.extracting == null ? -1.0F
                : Math.min(1.0F, part.extractTicks / (float) (RaidConfig.EXTRACT_SECONDS.get() * 20));
        RaidNetwork.sendTo(player, new RaidHudPacket(true, session.map.displayName, session.secondsLeft(), extracts,
                part.extracting == null ? "" : part.extracting.name(), progress));
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
