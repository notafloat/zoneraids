package com.zoneraids.block;

import com.zoneraids.raid.RaidManager;
import com.zoneraids.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A stash revealed by a flash drive: its loot is rolled the first time it is opened, and the stash is gone
 * once someone closes it empty. One hidden on a raid map belongs to that raid and is gone when the raid is.
 */
public class HiddenStashBlockEntity extends RandomizableContainerBlockEntity {
    private static final int SIZE = 27;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    @Nullable
    private UUID raid;

    public HiddenStashBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.HIDDEN_STASH_BE.get(), pos, state);
    }

    public void setRaid(@Nullable UUID raid) {
        this.raid = raid;
        setChanged();
    }

    /** True for a raid stash whose raid has ended. */
    public boolean isStale() {
        if (raid == null) {
            return false;
        }
        RaidManager manager = RaidManager.get();
        return manager == null || manager.sessionById(raid) == null;
    }

    @Override
    public void stopOpen(Player player) {
        super.stopOpen(player);
        if (level != null && !level.isClientSide && isEmpty()) {
            level.removeBlock(worldPosition, false);
        }
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return ChestMenu.threeRows(id, inventory, this);
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, items);
        }
        if (raid != null) {
            tag.putUUID("Raid", raid);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        if (!tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, items);
        }
        raid = tag.hasUUID("Raid") ? tag.getUUID("Raid") : null;
    }
}
