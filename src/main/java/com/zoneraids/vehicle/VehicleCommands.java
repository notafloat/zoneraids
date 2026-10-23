package com.zoneraids.vehicle;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zoneraids.data.RaidMap;
import com.zoneraids.data.RaidWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * {@code /raidadmin vehicle ...}: vehicle points of raid maps. Registered on its own; Brigadier merges it
 * into the {@code /raidadmin} tree.
 */
final class VehicleCommands {
    private static final SuggestionProvider<CommandSourceStack> MAPS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(RaidWorldData.get(ctx.getSource().getServer()).maps().stream().map(m -> m.id), builder);

    private VehicleCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("raidadmin")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("vehicle")
                        .then(Commands.literal("add").then(Commands.argument("map", StringArgumentType.word()).suggests(MAPS)
                                .executes(ctx -> add(ctx, 1.0))
                                .then(Commands.argument("chance", DoubleArgumentType.doubleArg(0, 1))
                                        .executes(ctx -> add(ctx, DoubleArgumentType.getDouble(ctx, "chance"))))))
                        .then(Commands.literal("list").then(Commands.argument("map", StringArgumentType.word()).suggests(MAPS)
                                .executes(VehicleCommands::list)))
                        .then(Commands.literal("clear").then(Commands.argument("map", StringArgumentType.word()).suggests(MAPS)
                                .executes(VehicleCommands::clear)))
                        .then(Commands.literal("resetcordon").executes(ctx -> {
                            VehicleData.get(ctx.getSource().getServer()).resetCordon();
                            ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.vehicle.admin.reset"), true);
                            return 1;
                        }))));
    }

    private static int add(CommandContext<CommandSourceStack> ctx, double chance) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(ctx, "map");
        RaidMap map = RaidWorldData.get(ctx.getSource().getServer()).getMap(id);
        if (map == null) {
            ctx.getSource().sendFailure(Component.translatable("zoneraids.admin.not_found", id));
            return 0;
        }
        if (!player.level().dimension().equals(map.dimension)) {
            ctx.getSource().sendFailure(Component.translatable("zoneraids.admin.wrong_dimension", map.dimension.location().toString()));
            return 0;
        }
        VehicleData data = VehicleData.get(ctx.getSource().getServer());
        data.add(id, new VehiclePoint(player.getBlockX(), player.getBlockY(), player.getBlockZ(), player.getYRot(), chance));
        int count = data.points(id).size();
        ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.vehicle.admin.added", id, count), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "map");
        List<VehiclePoint> points = VehicleData.get(ctx.getSource().getServer()).points(id);
        ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.vehicle.admin.list", id, points.size()), false);
        for (VehiclePoint p : points) {
            String y = p.y() == VehiclePoint.GROUND ? "~" : Integer.toString(p.y());
            ctx.getSource().sendSuccess(() -> Component.literal(" " + p.x() + " " + y + " " + p.z() + " · " + Math.round(p.chance() * 100) + "%")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return points.size();
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "map");
        int removed = VehicleData.get(ctx.getSource().getServer()).clear(id);
        ctx.getSource().sendSuccess(() -> Component.translatable("zoneraids.vehicle.admin.cleared", id, removed), true);
        return removed;
    }
}
