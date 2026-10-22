package com.zoneraids.block;

import com.zoneraids.data.RaidWorldData;
import com.zoneraids.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Container that rerolls its loot once per raid. The refill is lazy: the crate compares the
 * dimension's raid generation with the one it last rolled for when someone opens it, so maps with
 * thousands of crates cost nothing until a crate is actually looted.
 */
public class LootCrateBlockEntity extends RandomizableContainerBlockEntity {
    private static final int SIZE = 27;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private String crateLoot;
    private long refillGeneration = Long.MIN_VALUE;

    public LootCrateBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.LOOT_CRATE_BE.get(), pos, state);
        this.crateLoot = state.getBlock() instanceof LootCrateBlock crate ? crate.getDefaultLootTable().toString() : "";
    }

    public void setCrateLoot(ResourceLocation lootTable) {
        this.crateLoot = lootTable.toString();
        this.refillGeneration = Long.MIN_VALUE;
        setChanged();
    }

    public String getCrateLoot() {
        return crateLoot;
    }

    public void refillIfNeeded(ServerPlayer player) {
        if (level == null) {
            return;
        }
        long generation = RaidWorldData.get(player.server).getGeneration(level.dimension());
        if (generation == refillGeneration) {
            return;
        }
        refillGeneration = generation;
        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
        ResourceLocation table = ResourceLocation.tryParse(crateLoot);
        if (table != null) {
            setLootTable(table, level.random.nextLong());
        }
        setChanged();
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
        tag.putString("CrateLoot", crateLoot);
        tag.putLong("RefillGeneration", refillGeneration);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        if (!tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, items);
        }
        if (tag.contains("CrateLoot")) {
            crateLoot = tag.getString("CrateLoot");
        }
        if (tag.contains("RefillGeneration")) {
            refillGeneration = tag.getLong("RefillGeneration");
        }
    }
}
