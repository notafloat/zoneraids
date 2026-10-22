package com.zoneraids.raid;

import com.zoneraids.data.PlayerRaidData;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Personal stash: 54 slots stored in the player's own save data. The container only exists while
 * the menu is open and is written back on every change, so nothing is cached per player.
 */
public final class StashService {
    public static final int SIZE = 54;

    private StashService() {
    }

    public static void open(ServerPlayer player) {
        RaidManager manager = RaidManager.get();
        if (manager != null && manager.isInRaid(player.getUUID())) {
            player.displayClientMessage(Component.translatable("zoneraids.stash.in_raid"), true);
            return;
        }
        StashContainer container = new StashContainer(player);
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> ChestMenu.sixRows(id, inventory, container),
                Component.translatable("container.zoneraids.stash")));
    }

    private static final class StashContainer extends SimpleContainer {
        private final ServerPlayer owner;
        private boolean loading;

        StashContainer(ServerPlayer owner) {
            super(SIZE);
            this.owner = owner;
            NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(PlayerRaidData.root(owner).getCompound(PlayerRaidData.STASH), items);
            loading = true;
            for (int i = 0; i < SIZE; i++) {
                setItem(i, items.get(i));
            }
            loading = false;
        }

        @Override
        public boolean stillValid(Player player) {
            return player == owner && owner.isAlive() && !owner.hasDisconnected();
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (loading) {
                return;
            }
            // Written on every change so the stash and the player inventory are always saved together.
            save();
        }

        @Override
        public void stopOpen(Player player) {
            super.stopOpen(player);
            save();
        }

        private void save() {
            NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
            for (int i = 0; i < SIZE; i++) {
                items.set(i, getItem(i));
            }
            CompoundTag tag = new CompoundTag();
            ContainerHelper.saveAllItems(tag, items, true);
            PlayerRaidData.root(owner).put(PlayerRaidData.STASH, tag);
        }
    }
}
