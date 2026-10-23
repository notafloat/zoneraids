package com.zoneraids.raid;

import com.zoneraids.RaidConfig;
import com.zoneraids.data.PlayerRaidData;
import com.zoneraids.network.TerminalDataPacket;
import com.zoneraids.util.Teleports;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Watching raiders from the hub terminal. The viewer is put in spectator mode on the raid map with the
 * camera locked to the raider (the client only gets chunks around its own position, so the viewer has
 * to be there), and goes back to the exact spot and game mode they left from. That spot is kept in the
 * player's save file, so a crash or logout while watching still brings them back on the next login.
 *
 * Fairness: a member of a running session may watch only their own group in it, and nobody of it in
 * the deathmatch. Voice of spectators is not heard by players (voice chat {@code spectator_interaction=false}).
 */
final class Spectators {
    private static final String WATCH = "Watch";
    private static final int MAX_ENTRIES = 64;

    private final RaidManager manager;
    private final MinecraftServer server;
    /** Viewer -> what they are watching. */
    private final Map<UUID, Watch> watching = new HashMap<>();

    private static final class Watch {
        UUID target;
        /** The raider's session, to move on to a teammate of theirs once they are gone. */
        final UUID session;

        Watch(UUID target, UUID session) {
            this.target = target;
            this.session = session;
        }
    }

    Spectators(RaidManager manager, MinecraftServer server) {
        this.manager = manager;
        this.server = server;
    }

    boolean isWatching(UUID viewer) {
        return watching.containsKey(viewer);
    }

    void clear() {
        watching.clear();
    }

    @Nullable
    String targetName(UUID viewer) {
        Watch watch = watching.get(viewer);
        ServerPlayer player = watch == null ? null : server.getPlayerList().getPlayer(watch.target);
        return player == null ? null : player.getGameProfile().getName();
    }

    /** Raiders the viewer may watch right now, for the terminal list. */
    List<TerminalDataPacket.WatchEntry> entries(ServerPlayer viewer) {
        List<TerminalDataPacket.WatchEntry> list = new ArrayList<>();
        if (!RaidConfig.SPECTATING.get() || manager.isInRaid(viewer.getUUID())) {
            return list;
        }
        RaidSession own = manager.rosterSessionOf(viewer.getUUID());
        for (RaidSession session : manager.runningSessions()) {
            for (RaidSession.Participant part : session.participants.values()) {
                ServerPlayer target = server.getPlayerList().getPlayer(part.id);
                if (target == null || !canWatch(viewer, target, session)) {
                    continue;
                }
                list.add(new TerminalDataPacket.WatchEntry(part.id.toString(), part.name, session.map.displayName,
                        session.round, session.rounds, session.secondsLeft(), session == own));
                if (list.size() >= MAX_ENTRIES) {
                    return list;
                }
            }
        }
        return list;
    }

    private boolean canWatch(ServerPlayer viewer, ServerPlayer target, RaidSession session) {
        if (viewer == target || !target.isAlive() || session.intermission || !session.participants.containsKey(target.getUUID())
                || target.level() != server.getLevel(session.map.dimension)) {
            return false;
        }
        RaidSession own = manager.rosterSessionOf(viewer.getUUID());
        if (own != session) {
            return true;
        }
        if (session.type == RoundType.DEATHMATCH) {
            return false;
        }
        Integer group = session.groupOf.get(viewer.getUUID());
        if (group != null && group.equals(session.groupOf.get(target.getUUID()))) {
            return true;
        }
        // Nobody of the viewer's group is left on the map (or they play alone): anyone in the round, rather than
        // twenty minutes of staring at the timer.
        return !groupOnMap(session, group);
    }

    private boolean groupOnMap(RaidSession session, @Nullable Integer group) {
        if (group == null) {
            return false;
        }
        for (UUID id : session.participants.keySet()) {
            if (group.equals(session.groupOf.get(id)) && server.getPlayerList().getPlayer(id) != null) {
                return true;
            }
        }
        return false;
    }

    /** Terminal "watch" button. Starts watching, or switches to another raider while already watching. */
    void watch(ServerPlayer viewer, String targetId) {
        if (!RaidConfig.SPECTATING.get()) {
            deny(viewer, "zoneraids.watch.disabled");
            return;
        }
        if (manager.isInRaid(viewer.getUUID()) || !viewer.isAlive()) {
            deny(viewer, "zoneraids.watch.not_now");
            return;
        }
        boolean already = isWatching(viewer.getUUID());
        if (!already && !viewer.level().dimension().equals(manager.hubDimension())) {
            deny(viewer, "zoneraids.watch.hub_only");
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(targetId);
        } catch (IllegalArgumentException e) {
            return;
        }
        ServerPlayer target = server.getPlayerList().getPlayer(id);
        RaidSession session = target == null ? null : manager.sessionOf(id);
        if (target == null || session == null || !canWatch(viewer, target, session)) {
            deny(viewer, "zoneraids.watch.unavailable");
            return;
        }
        if (!already) {
            saveOrigin(viewer);
            viewer.setGameMode(GameType.SPECTATOR);
        }
        attach(viewer, target);
        watching.put(viewer.getUUID(), new Watch(id, session.id));
        viewer.sendSystemMessage(Component.translatable("zoneraids.watch.started", target.getDisplayName(), session.map.displayName)
                .withStyle(ChatFormatting.GRAY));
    }

    private void attach(ServerPlayer viewer, ServerPlayer target) {
        if (viewer.level() != target.level() || viewer.distanceToSqr(target) > 64 * 64) {
            Teleports.teleport(viewer, target.serverLevel(), target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot());
        }
        viewer.setCamera(target);
    }

    /** Terminal "stop" button. */
    void stop(ServerPlayer viewer) {
        if (watching.remove(viewer.getUUID()) != null || PlayerRaidData.root(viewer).contains(WATCH)) {
            returnToOrigin(viewer);
        }
    }

    /**
     * Ends watching without moving the player, for callers that teleport them right after
     * (round start, sending to the hub).
     */
    void release(ServerPlayer viewer) {
        if (watching.remove(viewer.getUUID()) == null && !PlayerRaidData.root(viewer).contains(WATCH)) {
            return;
        }
        CompoundTag origin = PlayerRaidData.root(viewer).getCompound(WATCH);
        PlayerRaidData.root(viewer).remove(WATCH);
        viewer.setCamera(viewer);
        viewer.setGameMode(GameType.byId(origin.getInt("Mode")));
        manager.afterWatch(viewer);
    }

    void onLogin(ServerPlayer player) {
        if (PlayerRaidData.root(player).contains(WATCH)) {
            returnToOrigin(player);
        }
    }

    /** The spot is kept in the save file; {@link #onLogin} brings the player back. */
    void onLogout(ServerPlayer player) {
        watching.remove(player.getUUID());
    }

    /** Every tick: follow the raider, move on to a teammate when they are gone, Shift (vanilla) leaves. */
    void tick() {
        if (watching.isEmpty()) {
            return;
        }
        List<ServerPlayer> done = null;
        Iterator<Map.Entry<UUID, Watch>> it = watching.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Watch> entry = it.next();
            Watch watch = entry.getValue();
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            if (viewer == null) {
                it.remove();
                continue;
            }
            ServerPlayer target = server.getPlayerList().getPlayer(watch.target);
            RaidSession session = target == null ? null : manager.sessionOf(target.getUUID());
            boolean valid = viewer.isSpectator() && target != null && session != null && canWatch(viewer, target, session);
            ServerPlayer next = null;
            if (valid && viewer.getCamera() == viewer) {
                // Shift, or a teleport from the vanilla spectator menu: back to the hub.
                valid = false;
            } else if (!valid && viewer.isSpectator()) {
                // The raider died, extracted or logged out.
                next = next(viewer, watch.session);
            }
            if (valid) {
                if (viewer.getCamera() != target) {
                    // Clicked some other entity with the spectator camera.
                    attach(viewer, target);
                }
                continue;
            }
            if (next != null) {
                watch.target = next.getUUID();
                attach(viewer, next);
                viewer.displayClientMessage(Component.translatable("zoneraids.watch.switched", next.getDisplayName()), true);
                continue;
            }
            if (done == null) {
                done = new ArrayList<>();
            }
            done.add(viewer);
        }
        if (done != null) {
            for (ServerPlayer viewer : done) {
                stop(viewer);
                viewer.sendSystemMessage(Component.translatable("zoneraids.watch.ended").withStyle(ChatFormatting.GRAY));
            }
        }
        if (manager.now() % 20 == 0) {
            for (Map.Entry<UUID, Watch> entry : watching.entrySet()) {
                ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
                ServerPlayer target = server.getPlayerList().getPlayer(entry.getValue().target);
                if (viewer != null && target != null) {
                    manager.mirrorHud(viewer, target);
                    viewer.displayClientMessage(Component.translatable("zoneraids.watch.bar", target.getDisplayName()), true);
                }
            }
        }
    }

    /** Someone else of the same session to watch once the raider is gone. */
    @Nullable
    private ServerPlayer next(ServerPlayer viewer, UUID sessionId) {
        RaidSession session = manager.sessionById(sessionId);
        if (session == null) {
            return null;
        }
        for (RaidSession.Participant part : session.participants.values()) {
            ServerPlayer target = server.getPlayerList().getPlayer(part.id);
            if (target != null && canWatch(viewer, target, session)) {
                return target;
            }
        }
        return null;
    }

    private static void saveOrigin(ServerPlayer viewer) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dim", viewer.level().dimension().location().toString());
        tag.putDouble("X", viewer.getX());
        tag.putDouble("Y", viewer.getY());
        tag.putDouble("Z", viewer.getZ());
        tag.putFloat("Yaw", viewer.getYRot());
        tag.putFloat("Pitch", viewer.getXRot());
        tag.putInt("Mode", viewer.gameMode.getGameModeForPlayer().getId());
        PlayerRaidData.root(viewer).put(WATCH, tag);
    }

    private void returnToOrigin(ServerPlayer viewer) {
        CompoundTag origin = PlayerRaidData.root(viewer).getCompound(WATCH);
        PlayerRaidData.root(viewer).remove(WATCH);
        watching.remove(viewer.getUUID());
        ResourceLocation dim = ResourceLocation.tryParse(origin.getString("Dim"));
        ServerLevel level = dim == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        viewer.setCamera(viewer);
        if (level != null) {
            Teleports.teleport(viewer, level, origin.getDouble("X"), origin.getDouble("Y"), origin.getDouble("Z"),
                    origin.getFloat("Yaw"), origin.getFloat("Pitch"));
        } else {
            manager.sendToHub(viewer);
        }
        viewer.setGameMode(GameType.byId(origin.getInt("Mode")));
        manager.afterWatch(viewer);
    }

    private static void deny(ServerPlayer viewer, String key) {
        viewer.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
    }
}
