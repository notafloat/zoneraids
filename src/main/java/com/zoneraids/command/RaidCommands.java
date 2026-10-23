package com.zoneraids.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import com.zoneraids.block.LootCrateBlockEntity;
import com.zoneraids.data.EnemyPoint;
import com.zoneraids.data.ExtractPoint;
import com.zoneraids.data.HubPoint;
import com.zoneraids.data.PlayerRaidData;
import com.zoneraids.data.RaidMap;
import com.zoneraids.data.RaidWorldData;
import com.zoneraids.data.SpawnPoint;
import com.zoneraids.perk.Perk;
import com.zoneraids.perk.PerkService;
import com.zoneraids.raid.EnemySpawner;
import com.zoneraids.raid.RaidManager;
import com.zoneraids.raid.RaidSession;
import com.zoneraids.shop.ShopCatalog;
import com.zoneraids.shop.ShopService;
import com.zoneraids.world.WorldBuilder;
import com.zoneraids.world.WorldSetup;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import org.slf4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public final class RaidCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SuggestionProvider<CommandSourceStack> MAPS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(RaidWorldData.get(ctx.getSource().getServer()).maps().stream().map(m -> m.id), builder);
    private static final SuggestionProvider<CommandSourceStack> ENEMY_KINDS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(EnemySpawner.types().keySet(), builder);

    private static final SuggestionProvider<CommandSourceStack> PERKS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(Perk.values()).map(p -> p.id), builder);

    private RaidCommands() {
    }

    private static int setMoney(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        long money = Math.max(0, (add ? PlayerRaidData.getMoney(target) : 0) + amount);
        PlayerRaidData.setMoney(target, money);
        ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.admin.money", target.getDisplayName(), money), true);
        return 1;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("raid")
                .executes(ctx -> withManager(ctx, m -> m.openTerminal(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("menu")
                        .executes(ctx -> withManager(ctx, m -> m.openTerminal(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("join").then(Commands.argument("map", StringArgumentType.word()).suggests(MAPS)
                        .executes(ctx -> withManager(ctx, m -> m.joinQueue(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "map"))))))
                .then(Commands.literal("leave")
                        .executes(ctx -> withManager(ctx, m -> m.leaveQueue(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("extracts")
                        .executes(ctx -> withManager(ctx, m -> m.showExtracts(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("stats").executes(RaidCommands::stats))
                .then(Commands.literal("shop").executes(ctx -> {
                    ShopService.open(ctx.getSource().getPlayerOrException(), false);
                    return 1;
                }))
                .then(Commands.literal("perks").executes(ctx -> {
                    PerkService.showList(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("perk").then(Commands.argument("perk", StringArgumentType.word()).suggests(PERKS)
                        .executes(ctx -> {
                            Perk perk = Perk.byId(StringArgumentType.getString(ctx, "perk"));
                            if (perk == null) {
                                ctx.getSource().sendFailure(Component.translatable("zoneraids.admin.not_found", StringArgumentType.getString(ctx, "perk")));
                                return 0;
                            }
                            return PerkService.buy(ctx.getSource().getPlayerOrException(), perk) ? 1 : 0;
                        })))
                .then(Commands.literal("party")
                        .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> withManager(ctx, m -> m.invite(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"))))))
                        .then(Commands.literal("accept")
                                .executes(ctx -> withManager(ctx, m -> m.acceptInvite(ctx.getSource().getPlayerOrException()))))
                        .then(Commands.literal("leave")
                                .executes(ctx -> withManager(ctx, m -> m.leaveParty(ctx.getSource().getPlayerOrException()))))
                        .then(Commands.literal("kick").then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> withManager(ctx, m -> m.kick(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player")))))))
        );

        dispatcher.register(Commands.literal("raidadmin")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("sethub").executes(RaidCommands::setHub))
                .then(Commands.literal("hub").executes(ctx -> withManager(ctx, m -> m.sendToHub(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("start").then(Commands.argument("map", StringArgumentType.word()).suggests(MAPS)
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "map");
                            RaidManager m = manager(ctx);
                            boolean ok = m != null && m.forceStart(id);
                            ctx.getSource().sendSuccess(() -> Component.translatable(ok ? "zoneraids.admin.started" : "zoneraids.admin.start_failed", id), true);
                            return ok ? 1 : 0;
                        })))
                .then(Commands.literal("stop").then(Commands.argument("map", StringArgumentType.word()).suggests(MAPS)
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "map");
                            RaidManager m = manager(ctx);
                            boolean ok = m != null && m.stopSession(id);
                            ctx.getSource().sendSuccess(() -> Component.translatable(ok ? "zoneraids.admin.stopped" : "zoneraids.admin.no_session", id), true);
                            return ok ? 1 : 0;
                        })))
                .then(Commands.literal("build")
                        .then(Commands.literal("status").executes(ctx -> {
                            String status = WorldBuilder.status();
                            ctx.getSource().sendSuccess(() -> status == null ? Component.translatable("zoneraids.build.idle")
                                    : Component.translatable("zoneraids.build.status", status), false);
                            return 1;
                        }))
                        .then(Commands.literal("hub").executes(ctx -> build(ctx, WorldSetup.buildHub(), WorldSetup.HUB)))
                        .then(Commands.literal("cordon").executes(ctx -> build(ctx, WorldSetup.buildCordon(ctx.getSource().getServer()), WorldSetup.CORDON))))
                .then(Commands.literal("perks").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.literal("points").then(Commands.argument("points", IntegerArgumentType.integer(0, 10000))
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    int points = IntegerArgumentType.getInteger(ctx, "points");
                                    PlayerRaidData.setPerkPoints(target, points);
                                    ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.admin.perk_points", target.getDisplayName(), points), true);
                                    return 1;
                                })))
                        .then(Commands.literal("reset").executes(ctx -> {
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                            for (Perk perk : Perk.values()) {
                                PlayerRaidData.setPerk(target, perk, 0);
                            }
                            PerkService.apply(target);
                            ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.admin.perks_reset", target.getDisplayName()), true);
                            return 1;
                        }))))
                .then(Commands.literal("shop").then(Commands.literal("check").executes(ctx -> {
                    List<String> unknown = ShopCatalog.unknownIds();
                    int offers = ShopCatalog.offers().size();
                    int prices = ShopCatalog.sellPrices().size();
                    if (unknown.isEmpty()) {
                        ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.admin.shop_ok", offers, prices), false);
                    } else {
                        ctx.getSource().sendFailure(Component.translatable("zoneraids.admin.shop_unknown", String.join(", ", unknown)));
                    }
                    return unknown.isEmpty() ? 1 : 0;
                })))
                .then(Commands.literal("money").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.literal("set").then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                .executes(ctx -> setMoney(ctx, false))))
                        .then(Commands.literal("add").then(Commands.argument("amount", IntegerArgumentType.integer())
                                .executes(ctx -> setMoney(ctx, true))))))
                .then(Commands.literal("enemy")
                        .then(Commands.literal("types").executes(RaidCommands::enemyTypes))
                        .then(Commands.literal("spawn").then(Commands.argument("kind", StringArgumentType.word()).suggests(ENEMY_KINDS)
                                .executes(ctx -> spawnEnemies(ctx, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
                                        .executes(ctx -> spawnEnemies(ctx, IntegerArgumentType.getInteger(ctx, "count")))))))
                .then(Commands.literal("crate").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("lootTable", ResourceLocationArgument.id()).executes(RaidCommands::setCrate))))
                .then(Commands.literal("map")
                        .then(Commands.literal("list").executes(RaidCommands::listMaps))
                        .then(Commands.literal("create").then(Commands.argument("id", StringArgumentType.word())
                                .executes(RaidCommands::createMap)))
                        .then(Commands.literal("remove").then(mapArg().executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "map");
                            RaidManager m = manager(ctx);
                            if (m != null) {
                                m.stopSession(id);
                            }
                            boolean ok = data(ctx).removeMap(id);
                            ctx.getSource().sendSuccess(() -> Component.translatable(ok ? "zoneraids.admin.map_removed" : "zoneraids.queue.unknown_map", id), true);
                            return ok ? 1 : 0;
                        })))
                        .then(Commands.literal("info").then(mapArg().executes(RaidCommands::mapInfo)))
                        .then(Commands.literal("name").then(mapArg().then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> editMap(ctx, map -> map.displayName = StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("duration").then(mapArg().then(Commands.argument("minutes", IntegerArgumentType.integer(1, 180))
                                .executes(ctx -> editMap(ctx, map -> map.durationSeconds = IntegerArgumentType.getInteger(ctx, "minutes") * 60)))))
                        .then(Commands.literal("players").then(mapArg().then(Commands.argument("min", IntegerArgumentType.integer(1, 100))
                                .then(Commands.argument("max", IntegerArgumentType.integer(1, 100)).executes(ctx -> editMap(ctx, map -> {
                                    int min = IntegerArgumentType.getInteger(ctx, "min");
                                    int max = IntegerArgumentType.getInteger(ctx, "max");
                                    map.minPlayers = Math.min(min, max);
                                    map.maxPlayers = Math.max(min, max);
                                }))))))
                        .then(Commands.literal("extractsPerPlayer").then(mapArg().then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
                                .executes(ctx -> editMap(ctx, map -> map.extractsPerPlayer = IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(Commands.literal("enable").then(mapArg().executes(ctx -> editMap(ctx, map -> map.enabled = true))))
                        .then(Commands.literal("disable").then(mapArg().executes(ctx -> editMap(ctx, map -> map.enabled = false))))
                        .then(Commands.literal("addspawn").then(mapArg().executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            return editMap(ctx, map -> {
                                requireSameDimension(map, player);
                                map.spawns.add(new SpawnPoint(player.blockPosition(), player.getYRot()));
                            });
                        })))
                        .then(Commands.literal("clearspawns").then(mapArg().executes(ctx -> editMap(ctx, map -> map.spawns.clear()))))
                        .then(Commands.literal("addextract").then(mapArg()
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 64))
                                        .then(Commands.argument("chance", DoubleArgumentType.doubleArg(0, 1))
                                                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                                    return editMap(ctx, map -> {
                                                        requireSameDimension(map, player);
                                                        map.extracts.add(new ExtractPoint(StringArgumentType.getString(ctx, "name"), player.blockPosition(),
                                                                DoubleArgumentType.getDouble(ctx, "radius"), DoubleArgumentType.getDouble(ctx, "chance")));
                                                    });
                                                }))))))
                        .then(Commands.literal("removeextract").then(mapArg().then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> editMap(ctx, map -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (!map.extracts.removeIf(e -> e.name().equalsIgnoreCase(name))) {
                                        throw new IllegalArgumentException(name);
                                    }
                                })))))
                        .then(Commands.literal("addenemy").then(mapArg()
                                .then(Commands.argument("kind", StringArgumentType.word()).suggests(ENEMY_KINDS)
                                        .then(Commands.argument("min", IntegerArgumentType.integer(0, 16))
                                                .then(Commands.argument("max", IntegerArgumentType.integer(0, 16))
                                                        .then(Commands.argument("chance", DoubleArgumentType.doubleArg(0, 1))
                                                                .executes(ctx -> addEnemy(ctx, 40))
                                                                .then(Commands.argument("trigger", IntegerArgumentType.integer(4, 128))
                                                                        .executes(ctx -> addEnemy(ctx, IntegerArgumentType.getInteger(ctx, "trigger"))))))))))
                        .then(Commands.literal("clearenemies").then(mapArg().executes(ctx -> editMap(ctx, map -> map.enemies.clear()))))
                        .then(Commands.literal("clearextracts").then(mapArg().executes(ctx -> editMap(ctx, map -> map.extracts.clear()))))
                        .then(Commands.literal("bounds").then(mapArg()
                                .then(Commands.argument("x1", IntegerArgumentType.integer()).then(Commands.argument("z1", IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", IntegerArgumentType.integer()).then(Commands.argument("z2", IntegerArgumentType.integer())
                                                .executes(ctx -> editMap(ctx, map -> map.setBounds(IntegerArgumentType.getInteger(ctx, "x1"),
                                                        IntegerArgumentType.getInteger(ctx, "z1"), IntegerArgumentType.getInteger(ctx, "x2"),
                                                        IntegerArgumentType.getInteger(ctx, "z2"))))))))))
                        .then(Commands.literal("clearbounds").then(mapArg().executes(ctx -> editMap(ctx, map -> map.bounded = false)))))
        );
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> mapArg() {
        return Commands.argument("map", StringArgumentType.word()).suggests(MAPS);
    }

    private static RaidWorldData data(CommandContext<CommandSourceStack> ctx) {
        return RaidWorldData.get(ctx.getSource().getServer());
    }

    private static RaidManager manager(CommandContext<CommandSourceStack> ctx) {
        return RaidManager.get();
    }

    @FunctionalInterface
    private interface ManagerAction {
        void run(RaidManager manager) throws CommandSyntaxException;
    }

    private static int withManager(CommandContext<CommandSourceStack> ctx, ManagerAction action) throws CommandSyntaxException {
        RaidManager manager = RaidManager.get();
        if (manager == null) {
            return 0;
        }
        action.run(manager);
        return 1;
    }

    private static void requireSameDimension(RaidMap map, ServerPlayer player) {
        if (!player.level().dimension().equals(map.dimension)) {
            throw new IllegalStateException("dimension");
        }
    }

    private static int editMap(CommandContext<CommandSourceStack> ctx, Consumer<RaidMap> edit) {
        String id = StringArgumentType.getString(ctx, "map");
        RaidWorldData data = data(ctx);
        RaidMap map = data.getMap(id);
        if (map == null) {
            ctx.getSource().sendFailure(Component.translatable("zoneraids.queue.unknown_map", id));
            return 0;
        }
        try {
            edit.accept(map);
        } catch (IllegalStateException e) {
            ctx.getSource().sendFailure(Component.translatable("zoneraids.admin.wrong_dimension", map.dimension.location().toString()));
            return 0;
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.translatable("zoneraids.admin.not_found", e.getMessage()));
            return 0;
        }
        data.setDirty();
        ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.admin.map_updated", map.id, map.spawns.size(), map.extracts.size()), true);
        return 1;
    }

    private static int createMap(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(ctx, "id");
        RaidWorldData data = data(ctx);
        if (data.getMap(id) != null) {
            ctx.getSource().sendFailure(Component.translatable("zoneraids.admin.map_exists", id));
            return 0;
        }
        data.putMap(new RaidMap(id, player.level().dimension()));
        ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.admin.map_created", id, player.level().dimension().location().toString()), true);
        return 1;
    }

    private static int listMaps(CommandContext<CommandSourceStack> ctx) {
        RaidManager manager = RaidManager.get();
        RaidWorldData data = data(ctx);
        if (data.maps().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.admin.no_maps"), false);
            return 0;
        }
        for (RaidMap map : data.maps()) {
            RaidSession session = manager == null ? null : manager.sessionOnMap(map.id);
            int queued = manager == null ? 0 : manager.queueSize(map.id);
            Component line = Component.literal(String.format("%s (%s) %s  spawns=%d extracts=%d queue=%d %s",
                    map.id, map.displayName, map.dimension.location(), map.spawns.size(), map.extracts.size(), queued,
                    session == null ? "" : "RUNNING " + RaidManager.formatTime(session.secondsLeft()) + " players=" + session.size()))
                    .withStyle(map.isPlayable() ? ChatFormatting.GREEN : ChatFormatting.GRAY);
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return data.maps().size();
    }

    private static int mapInfo(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "map");
        RaidMap map = data(ctx).getMap(id);
        if (map == null) {
            ctx.getSource().sendFailure(Component.translatable("zoneraids.queue.unknown_map", id));
            return 0;
        }
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal(String.format("%s \"%s\" dim=%s enabled=%s duration=%s players=%d-%d extractsPerPlayer=%d",
                map.id, map.displayName, map.dimension.location(), map.enabled, RaidManager.formatTime(map.durationSeconds),
                map.minPlayers, map.maxPlayers, map.extractsPerPlayer)).withStyle(ChatFormatting.GOLD), false);
        if (map.bounded) {
            src.sendSuccess(() -> Component.literal(String.format("  bounds %d %d .. %d %d", map.minX, map.minZ, map.maxX, map.maxZ)), false);
        }
        for (SpawnPoint spawn : map.spawns) {
            src.sendSuccess(() -> Component.literal("  spawn " + spawn.pos().toShortString()), false);
        }
        for (ExtractPoint point : map.extracts) {
            src.sendSuccess(() -> Component.literal(String.format("  extract \"%s\" %s r=%.1f chance=%.2f",
                    point.name(), point.pos().toShortString(), point.radius(), point.chance())), false);
        }
        for (EnemyPoint point : map.enemies) {
            src.sendSuccess(() -> Component.literal(String.format("  enemy %s x%d-%d %s chance=%.2f trigger=%d",
                    point.kind(), point.min(), point.max(), point.pos().toShortString(), point.chance(), point.trigger())), false);
        }
        return 1;
    }

    private static int addEnemy(CommandContext<CommandSourceStack> ctx, int trigger) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String kind = StringArgumentType.getString(ctx, "kind");
        if (!EnemySpawner.types().containsKey(kind)) {
            ctx.getSource().sendFailure(Component.translatable("zoneraids.admin.unknown_enemy", kind));
            return 0;
        }
        int min = IntegerArgumentType.getInteger(ctx, "min");
        int max = IntegerArgumentType.getInteger(ctx, "max");
        double chance = DoubleArgumentType.getDouble(ctx, "chance");
        return editMap(ctx, map -> {
            requireSameDimension(map, player);
            map.enemies.add(new EnemyPoint(kind, player.blockPosition(), Math.min(min, max), Math.max(min, max), chance, trigger));
        });
    }

    private static int enemyTypes(CommandContext<CommandSourceStack> ctx) {
        for (EnemySpawner.EnemyType type : EnemySpawner.types().values()) {
            boolean ok = type.isInstalled();
            Component line = Component.literal(String.format("%s = %s%s %s", type.kind(), type.entity(),
                    type.nbt().isEmpty() ? "" : type.nbt().toString(), ok ? "OK" : "MISSING"))
                    .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED);
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return EnemySpawner.types().size();
    }

    /** Test spawn at the command position. These enemies belong to no raid, so raid cleanup does not touch them. */
    private static int spawnEnemies(CommandContext<CommandSourceStack> ctx, int count) {
        String kind = StringArgumentType.getString(ctx, "kind");
        EnemySpawner.EnemyType type = EnemySpawner.types().get(kind);
        if (type == null || !type.isInstalled()) {
            ctx.getSource().sendFailure(Component.translatable("zoneraids.admin.unknown_enemy", kind));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Entity entity = EnemySpawner.spawn(level, type, pos, List.of(), 0, level.getRandom(), null);
            if (entity != null) {
                spawned++;
            }
        }
        int n = spawned;
        LOGGER.info("Zone Raids: test spawn of {} x{} at {}", kind, n, pos.toShortString());
        if (n == 0) {
            ctx.getSource().sendFailure(Component.translatable("zoneraids.admin.enemy_no_room", kind));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.admin.enemy_spawned", n, kind), true);
        return n;
    }

    private static int build(CommandContext<CommandSourceStack> ctx, boolean queued, String name) {
        if (queued) {
            ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.build.queued", name), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.translatable("zoneraids.build.busy", name));
        return 0;
    }

    private static int setHub(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        data(ctx).setHub(new HubPoint(player.level().dimension(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.admin.hub_set"), true);
        return 1;
    }

    private static int setCrate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        ResourceLocation table = ResourceLocationArgument.getId(ctx, "lootTable");
        if (ctx.getSource().getLevel().getBlockEntity(pos) instanceof LootCrateBlockEntity crate) {
            crate.setCrateLoot(table);
            ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.admin.crate_set", table.toString()), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.translatable("zoneraids.admin.not_crate"));
        return 0;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        player.sendSystemMessage(Component.translatable("zoneraids.stats.line",
                PlayerRaidData.getStat(player, PlayerRaidData.Stat.RAIDS),
                PlayerRaidData.getStat(player, PlayerRaidData.Stat.SURVIVED),
                PlayerRaidData.getStat(player, PlayerRaidData.Stat.KIA),
                PlayerRaidData.getStat(player, PlayerRaidData.Stat.MIA),
                PlayerRaidData.getStat(player, PlayerRaidData.Stat.KILLS)).withStyle(ChatFormatting.AQUA));
        return 1;
    }
}
