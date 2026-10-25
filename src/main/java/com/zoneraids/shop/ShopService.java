package com.zoneraids.shop;

import com.zoneraids.RaidConfig;
import com.zoneraids.data.PlayerRaidData;
import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.ShopDataPacket;
import com.zoneraids.raid.RaidManager;
import com.zoneraids.registry.ModRegistry;
import com.zoneraids.survival.Places;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The hub trader: artifacts, valuables and found gear are sold for money, guns, armor, ammo and medicine are bought with it.
 * Trading works only outside raids and only at a trader: the counter in the bunker or a stalker camp trader, never from the PDA. Money lives in the player's data ({@link PlayerRaidData#getMoney}),
 * so nothing is kept in memory and nothing about it can be dropped or looted.
 */
public final class ShopService {
    /** Deals need a trader counter or a camp trader this close. */
    private static final int TRADER_REACH = 6;

    private ShopService() {
    }

    /** Opened by the trader counter or a camp trader. */
    public static void open(ServerPlayer player) {
        if (!RaidConfig.SHOP.get()) {
            player.displayClientMessage(Component.translatable("zoneraids.shop.disabled").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!canTrade(player)) {
            player.displayClientMessage(Component.translatable("zoneraids.shop.in_raid").withStyle(ChatFormatting.RED), true);
            return;
        }
        RaidNetwork.sendTo(player, build(player, true));
    }

    /** @param arg {@code index:price} of the offer as the client saw it; a changed price cancels the deal */
    public static void buy(ServerPlayer player, String arg) {
        if (!allowed(player)) {
            return;
        }
        String[] parts = arg.split(":");
        ShopCatalog.Offer offer = null;
        if (parts.length == 2) {
            try {
                offer = ShopCatalog.offer(Integer.parseInt(parts[0]));
                if (offer != null && offer.price() != Integer.parseInt(parts[1])) {
                    offer = null;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (offer == null) {
            RaidNetwork.sendTo(player, build(player, false));
            return;
        }
        long money = PlayerRaidData.getMoney(player);
        if (money < offer.price()) {
            player.displayClientMessage(Component.translatable("zoneraids.shop.no_money", offer.price()).withStyle(ChatFormatting.RED), true);
            RaidNetwork.sendTo(player, build(player, false));
            return;
        }
        PlayerRaidData.setMoney(player, money - offer.price());
        ItemStack goods = offer.stack();
        int left = goods.getCount();
        while (left > 0) {
            ItemStack stack = goods.copyWithCount(Math.min(left, goods.getMaxStackSize()));
            left -= stack.getCount();
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
        player.inventoryMenu.broadcastChanges();
        player.displayClientMessage(Component.translatable("zoneraids.shop.bought", goods.getHoverName(), goods.getCount(), offer.price())
                .withStyle(ChatFormatting.GREEN), true);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.VILLAGER_YES, SoundSource.PLAYERS, 0.5F, 0.8F);
        RaidNetwork.sendTo(player, build(player, false));
    }

    /** Sells one (or every) item with the given id from the player's inventory. Equipped Curios items are not touched. */
    public static void sell(ServerPlayer player, String itemId, boolean all) {
        if (!allowed(player)) {
            return;
        }
        ShopCatalog.SellPrice price = ShopCatalog.sellPrice(itemId);
        if (price == null) {
            RaidNetwork.sendTo(player, build(player, false));
            return;
        }
        Inventory inventory = player.getInventory();
        int sold = 0;
        ItemStack name = ItemStack.EMPTY;
        for (int i = 0; i < inventory.getContainerSize() && (all || sold == 0); i++) {
            ItemStack stack = inventory.getItem(i);
            if (price.matches(stack)) {
                if (name.isEmpty()) {
                    name = stack.copyWithCount(1);
                }
                int n = all ? stack.getCount() : 1;
                stack.shrink(n);
                sold += n;
                inventory.setChanged();
            }
        }
        if (sold > 0) {
            long earned = (long) sold * price.price();
            PlayerRaidData.setMoney(player, PlayerRaidData.getMoney(player) + earned);
            player.inventoryMenu.broadcastChanges();
            player.displayClientMessage(Component.translatable("zoneraids.shop.sold", name.getHoverName(), sold, earned)
                    .withStyle(ChatFormatting.GREEN), true);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.VILLAGER_TRADE, SoundSource.PLAYERS, 0.5F, 0.8F);
        }
        RaidNetwork.sendTo(player, build(player, false));
    }

    private static boolean canTrade(ServerPlayer player) {
        RaidManager manager = RaidManager.get();
        return manager != null && player.isAlive() && !player.isSpectator()
                && !manager.isInRaid(player.getUUID()) && !manager.isWatching(player.getUUID());
    }

    /** Checks done again on every deal, since the screen may have been opened before a raid started. */
    private static boolean allowed(ServerPlayer player) {
        if (!RaidConfig.SHOP.get() || !canTrade(player)) {
            player.displayClientMessage(Component.translatable("zoneraids.shop.in_raid").withStyle(ChatFormatting.RED), true);
            RaidNetwork.sendTo(player, build(player, false));
            return false;
        }
        if (!nearTrader(player)) {
            player.displayClientMessage(Component.translatable("zoneraids.shop.go_to_trader").withStyle(ChatFormatting.YELLOW), true);
            return false;
        }
        return true;
    }

    private static boolean nearTrader(ServerPlayer player) {
        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-TRADER_REACH, -2, -TRADER_REACH), center.offset(TRADER_REACH, 3, TRADER_REACH))) {
            if (player.level().getBlockState(pos).is(ModRegistry.TRADER.get())) {
                return true;
            }
        }
        return !player.level().getEntities(player, player.getBoundingBox().inflate(TRADER_REACH), Places::isTrader).isEmpty();
    }

    private static ShopDataPacket build(ServerPlayer player, boolean open) {
        List<ShopDataPacket.Offer> offers = new ArrayList<>();
        for (ShopCatalog.Offer offer : ShopCatalog.offers()) {
            offers.add(new ShopDataPacket.Offer(offer.index(), offer.category(), offer.price(), offer.stack()));
        }
        Inventory inventory = player.getInventory();
        List<ShopDataPacket.SellEntry> sell = new ArrayList<>();
        for (ShopCatalog.SellPrice price : ShopCatalog.sellPrices()) {
            int owned = 0;
            ItemStack icon = price.icon();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (price.matches(stack)) {
                    if (owned == 0) {
                        icon = stack.copyWithCount(1);
                    }
                    owned += stack.getCount();
                }
            }
            // Buy-backs would bury the artifacts under dozens of rows: only what the player actually carries.
            if (price.always() || owned > 0) {
                sell.add(new ShopDataPacket.SellEntry(price.id(), price.price(), owned, icon));
            }
        }
        return new ShopDataPacket(open, PlayerRaidData.getMoney(player), RaidConfig.SHOP.get() && canTrade(player), offers, sell);
    }
}
