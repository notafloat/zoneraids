package com.zoneraids.menu;

import com.zoneraids.registry.ModRegistry;
import com.zoneraids.util.CuriosCompat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.NotNull;

/**
 * The artifact container worn in the Curios container slot, opened from the PDA. Anomaly Zone only
 * opens a container held in hand; this menu edits the worn one in place. Its artifacts live in the
 * container's NBT under {@code Inventory}, in the same format as Anomaly Zone's own menu, so both
 * read the same contents and the bonuses follow at once.
 */
public final class ArtifactContainerMenu extends AbstractContainerMenu {
    public static final int COLUMNS = 9;
    private static final String INVENTORY_TAG = "Inventory";

    private final Player player;
    /** The worn container on the server; empty on the client, which only mirrors the slots. */
    private final ItemStack container;
    private final ItemStackHandler artifacts;
    public final int size;

    /** Client side, from the size the server sent. */
    public ArtifactContainerMenu(int id, Inventory inventory, FriendlyByteBuf buf) {
        this(id, inventory, ItemStack.EMPTY, buf.readVarInt());
    }

    private ArtifactContainerMenu(int id, Inventory inventory, ItemStack container, int size) {
        super(ModRegistry.ARTIFACT_CONTAINER_MENU.get(), id);
        this.player = inventory.player;
        this.container = container;
        this.size = size;
        this.artifacts = new ItemStackHandler(size) {
            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }

            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                return ArtifactContainers.isArtifact(stack);
            }

            @Override
            protected void onContentsChanged(int slot) {
                save();
            }
        };
        if (!container.isEmpty() && container.hasTag() && container.getTag().contains(INVENTORY_TAG)) {
            artifacts.deserializeNBT(container.getTag().getCompound(INVENTORY_TAG));
            if (artifacts.getSlots() != size) {
                // A container saved with another slot count: keep what fits in this one's slots.
                ItemStackHandler stored = artifacts;
                ItemStack[] kept = new ItemStack[size];
                for (int i = 0; i < size; i++) {
                    kept[i] = i < stored.getSlots() ? stored.getStackInSlot(i) : ItemStack.EMPTY;
                }
                artifacts.setSize(size);
                for (int i = 0; i < size; i++) {
                    artifacts.setStackInSlot(i, kept[i]);
                }
            }
        }

        int rows = rows();
        int offset = (COLUMNS - Math.min(size, COLUMNS)) * 9;
        for (int i = 0; i < size; i++) {
            addSlot(new SlotItemHandler(artifacts, i, 8 + offset + (i % COLUMNS) * 18, 18 + (i / COLUMNS) * 18));
        }
        int invY = 31 + rows * 18;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, invY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, invY + 58));
        }
    }

    public int rows() {
        return Math.max(1, (size + COLUMNS - 1) / COLUMNS);
    }

    private void save() {
        if (!container.isEmpty()) {
            container.getOrCreateTag().put(INVENTORY_TAG, artifacts.serializeNBT());
        }
    }

    /** Opens the player's worn container, or tells them they wear none. */
    public static void open(ServerPlayer player) {
        ItemStack container = CuriosCompat.wornContainer(player);
        if (container.isEmpty()) {
            player.displayClientMessage(Component.translatable("zoneraids.container.none"), true);
            return;
        }
        int size = ArtifactContainers.slots(container);
        NetworkHooks.openScreen(player, new SimpleMenuProvider(
                (id, inventory, p) -> new ArtifactContainerMenu(id, inventory, container, size), container.getHoverName()),
                buf -> buf.writeVarInt(size));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < size) {
            if (!moveItemStackTo(stack, size, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!ArtifactContainers.isArtifact(stack) || !moveItemStackTo(stack, 0, size, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        // Taken off or lost (death drops it): the menu closes. Identity, since Curios keeps the stack in place.
        return container.isEmpty() ? player.level().isClientSide : CuriosCompat.wornContainer(this.player) == container;
    }
}
