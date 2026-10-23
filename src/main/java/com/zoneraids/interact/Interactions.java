package com.zoneraids.interact;

import com.zoneraids.block.LootCrateBlock;
import com.zoneraids.block.RaidTerminalBlock;
import com.zoneraids.block.StashBlock;
import com.zoneraids.block.TraderBlock;
import com.zoneraids.raid.PartyManager;
import com.zoneraids.raid.RaidManager;
import com.zoneraids.util.FirstAidCompat;
import com.zoneraids.vehicle.PitBike;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Arma 3's action menu: what the player can do with the block or entity under the crosshair. The client lists
 * the options (com.zoneraids.client.InteractionMenu) and sends the chosen one; the server lists them again for
 * the same target and runs the action only if it is still there, so a stale or forged choice does nothing.
 * Every action works whatever is in the hand: with a TaCZ gun the right button aims, so doors and crates
 * could not be opened without putting the gun away.
 */
public final class Interactions {
    public enum Action {
        /** The block's own right click: doors, gates, levers, crates, stash, trader, terminal, chests. */
        USE,
        RIDE,
        INSPECT_BIKE,
        INSPECT_PLAYER,
        INVITE,
        ACCEPT_INVITE,
        GIVE_ITEM,
        /** No target: get off the bike. */
        DISMOUNT
    }

    public record Option(Action action, Component label) {
    }

    /** Reach past the vanilla one, for a target that moved a little while the packet travelled. */
    private static final double PADDING = 1.5;

    private Interactions() {
    }

    /** The block's options, the default (Arma's middle-of-the-screen action) first. Empty: nothing to do. */
    public static List<Option> forBlock(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        String key = useKey(level, pos, state);
        if (key == null) {
            return List.of();
        }
        return List.of(new Option(Action.USE, Component.translatable(key, state.getBlock().getName())));
    }

    @Nullable
    private static String useKey(Level level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof LootCrateBlock) {
            return "zoneraids.interact.search";
        } else if (block instanceof StashBlock) {
            return "zoneraids.interact.stash";
        } else if (block instanceof TraderBlock) {
            return "zoneraids.interact.trade";
        } else if (block instanceof RaidTerminalBlock) {
            return "zoneraids.interact.terminal";
        } else if (block instanceof DoorBlock door) {
            return door.type().canOpenByHand() ? openClose(state, "door") : null;
        } else if (block instanceof TrapDoorBlock) {
            return state.is(Blocks.IRON_TRAPDOOR) ? null : openClose(state, "hatch");
        } else if (block instanceof FenceGateBlock) {
            return openClose(state, "gate");
        } else if (block instanceof LeverBlock) {
            return "zoneraids.interact.lever";
        } else if (block instanceof ButtonBlock) {
            return state.getValue(BlockStateProperties.POWERED) ? null : "zoneraids.interact.button";
        }
        return state.getMenuProvider(level, pos) != null ? "zoneraids.interact.open" : null;
    }

    private static String openClose(BlockState state, String what) {
        boolean open = state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        return "zoneraids.interact." + (open ? "close_" : "open_") + what;
    }

    /**
     * The entity's options. {@code invitedByTarget} and {@code partyMate} come from the party state, which the
     * server knows and the client guesses from chat and the last terminal data.
     */
    public static List<Option> forEntity(Player player, Entity entity, boolean invitedByTarget, boolean partyMate) {
        List<Option> options = new ArrayList<>();
        if (!entity.isAlive()) {
            return options;
        }
        if (entity instanceof PitBike bike) {
            if (!player.isPassenger() && bike.getPassengers().size() < 2) {
                options.add(new Option(Action.RIDE, Component.translatable(bike.getPassengers().isEmpty()
                        ? "zoneraids.interact.ride_driver" : "zoneraids.interact.ride_passenger")));
            }
            options.add(new Option(Action.INSPECT_BIKE, Component.translatable("zoneraids.interact.inspect_bike")));
        } else if (entity instanceof Player other && other != player && !other.isSpectator()) {
            options.add(new Option(Action.INSPECT_PLAYER, Component.translatable("zoneraids.interact.inspect_player")));
            if (invitedByTarget) {
                options.add(new Option(Action.ACCEPT_INVITE, Component.translatable("zoneraids.interact.accept_invite")));
            } else if (!partyMate) {
                options.add(new Option(Action.INVITE, Component.translatable("zoneraids.interact.invite")));
            }
            ItemStack hand = player.getMainHandItem();
            if (!hand.isEmpty()) {
                options.add(new Option(Action.GIVE_ITEM, Component.translatable("zoneraids.interact.give", hand.getHoverName())));
            }
        }
        return options;
    }

    private static boolean offers(List<Option> options, Action action) {
        return options.stream().anyMatch(o -> o.action() == action);
    }

    // ------------------------------------------------------------------ server

    public static void performSelf(ServerPlayer player, Action action) {
        if (action == Action.DISMOUNT && player.isPassenger()) {
            player.stopRiding();
        }
    }

    public static void performOnBlock(ServerPlayer player, Action action, BlockPos pos, Direction face) {
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos) || !player.canReach(pos, PADDING) || !level.mayInteract(player, pos)
                || !offers(forBlock(level, pos), action)) {
            return;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
        // Same checks as a right click, so spawn protection and other mods' claims still apply.
        PlayerInteractEvent.RightClickBlock event = ForgeHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
        if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY) {
            return;
        }
        player.resetLastActionTime();
        InteractionResult result = level.getBlockState(pos).use(level, player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND, true);
        }
    }

    public static void performOnEntity(ServerPlayer player, Action action, int entityId) {
        Entity entity = player.level().getEntity(entityId);
        if (entity == null || !player.canReach(entity, PADDING)) {
            return;
        }
        RaidManager manager = RaidManager.get();
        UUID inviter = manager == null ? null : manager.parties().pendingInviter(player.getUUID(), manager.now());
        boolean invitedByTarget = inviter != null && inviter.equals(entity.getUUID());
        boolean partyMate = manager != null && sameParty(manager.parties(), player.getUUID(), entity.getUUID());
        if (!offers(forEntity(player, entity, invitedByTarget, partyMate), action)) {
            return;
        }
        player.resetLastActionTime();
        switch (action) {
            case RIDE -> player.startRiding(entity);
            case INSPECT_BIKE -> {
                int percent = Math.round(((PitBike) entity).healthFraction() * 100.0F);
                player.displayClientMessage(Component.translatable("zoneraids.interact.bike_state", percent)
                        .withStyle(percent < 30 ? ChatFormatting.RED : ChatFormatting.GRAY), true);
            }
            case INSPECT_PLAYER -> inspect(player, (Player) entity, partyMate);
            case INVITE -> {
                if (manager != null) {
                    manager.invite(player, (ServerPlayer) entity);
                }
            }
            case ACCEPT_INVITE -> {
                if (manager != null) {
                    manager.acceptInvite(player);
                }
            }
            case GIVE_ITEM -> give(player, (Player) entity);
            default -> {
            }
        }
    }

    private static boolean sameParty(PartyManager parties, UUID a, UUID b) {
        PartyManager.Party party = parties.get(a);
        return party != null && party == parties.get(b);
    }

    /** The other player's state at a glance, by First Aid's limbs when it is there. */
    private static void inspect(ServerPlayer player, Player target, boolean partyMate) {
        float fraction = target.getHealth() / Math.max(1.0F, target.getMaxHealth());
        FirstAidCompat.Snapshot limbs = FirstAidCompat.read(target);
        if (limbs != null) {
            float health = 0.0F;
            int max = 0;
            for (int i = 0; i < limbs.health().length; i++) {
                health += limbs.health()[i];
                max += limbs.max()[i];
            }
            fraction = max > 0 ? health / max : fraction;
        }
        String state = fraction >= 0.9F ? "healthy" : fraction >= 0.5F ? "wounded" : "badly_wounded";
        ChatFormatting color = fraction >= 0.9F ? ChatFormatting.GREEN : fraction >= 0.5F ? ChatFormatting.GOLD : ChatFormatting.RED;
        Component line = Component.translatable("zoneraids.interact.player_state", target.getDisplayName(),
                Component.translatable("zoneraids.interact.state." + state).withStyle(color));
        if (partyMate) {
            line = line.copy().append(Component.translatable("zoneraids.interact.party_mate").withStyle(ChatFormatting.AQUA));
        }
        player.displayClientMessage(line, true);
    }

    /** Hands the main hand stack over, as much as fits in the other player's inventory. */
    private static void give(ServerPlayer player, Player target) {
        ItemStack hand = player.getMainHandItem();
        Component name = hand.getHoverName();
        ItemStack moving = hand.copy();
        int count = moving.getCount();
        target.getInventory().add(moving);
        int moved = count - moving.getCount();
        if (moved <= 0) {
            player.displayClientMessage(Component.translatable("zoneraids.interact.give_full", target.getDisplayName())
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        hand.shrink(moved);
        player.displayClientMessage(Component.translatable("zoneraids.interact.given", name, moved, target.getDisplayName())
                .withStyle(ChatFormatting.GRAY), true);
        target.displayClientMessage(Component.translatable("zoneraids.interact.received", player.getDisplayName(), name, moved)
                .withStyle(ChatFormatting.GRAY), true);
    }
}
