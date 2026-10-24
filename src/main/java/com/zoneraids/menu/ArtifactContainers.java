package com.zoneraids.menu;

import com.zoneraids.util.CuriosCompat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.lang.reflect.Method;

/**
 * Anomaly Zone artifact containers (no compile-time dependency): what goes in, how many slots one has,
 * and the artifacts of the worn one. Anomaly Zone only opens a container held in hand; the PDA inventory
 * edits the worn one in place. Artifacts live in the container's NBT under {@code Inventory}, in the format
 * of Anomaly Zone's own menu, so both read the same contents and the bonuses follow at once.
 */
public final class ArtifactContainers {
    /** Slots when the container class cannot be asked (KSM, the smallest, has 4). */
    private static final int DEFAULT_SLOTS = 4;
    private static final String INVENTORY_TAG = "Inventory";

    private ArtifactContainers() {
    }

    /** Anomaly Zone's artifacts, unknown and proto artifacts; never a container. */
    public static boolean isArtifact(ItemStack stack) {
        if (stack.isEmpty() || stack.is(CuriosCompat.ARTIFACT_CONTAINERS)) {
            return false;
        }
        for (Class<?> c = stack.getItem().getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.startsWith("com.anomalyzone.") && name.endsWith("ArtifactItem")) {
                return true;
            }
        }
        return false;
    }

    /** Slot count from Anomaly Zone's {@code ModContainerItem.getSlots()}. */
    public static int slots(ItemStack container) {
        try {
            Method slots = container.getItem().getClass().getMethod("getSlots");
            return Math.max(1, Math.min(27, (Integer) slots.invoke(container.getItem())));
        } catch (ReflectiveOperationException | ClassCastException e) {
            return DEFAULT_SLOTS;
        }
    }

    /** The container's artifacts, one per slot (a container saved with another slot count keeps what fits). */
    public static ItemStack[] read(ItemStack container) {
        ItemStack[] items = new ItemStack[slots(container)];
        ItemStackHandler stored = new ItemStackHandler(items.length);
        if (container.hasTag() && container.getTag().contains(INVENTORY_TAG)) {
            stored.deserializeNBT(container.getTag().getCompound(INVENTORY_TAG));
        }
        for (int i = 0; i < items.length; i++) {
            items[i] = i < stored.getSlots() ? stored.getStackInSlot(i) : ItemStack.EMPTY;
        }
        return items;
    }

    private static void write(ItemStack container, ItemStack[] items) {
        ItemStackHandler stored = new ItemStackHandler(items.length);
        for (int i = 0; i < items.length; i++) {
            stored.setStackInSlot(i, items[i]);
        }
        container.getOrCreateTag().put(INVENTORY_TAG, stored.serializeNBT());
    }

    /**
     * A click on artifact slot {@code slot} of the worn container, with whatever the player holds on the
     * cursor of the open menu: take the artifact, put one in, or swap. With {@code toInventory} (shift) the
     * artifact goes straight to the inventory.
     */
    public static void click(ServerPlayer player, int slot, boolean toInventory) {
        ItemStack container = CuriosCompat.wornContainer(player);
        if (container.isEmpty()) {
            return;
        }
        ItemStack[] items = read(container);
        if (slot < 0 || slot >= items.length) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        ItemStack stored = items[slot];
        ItemStack carried = menu.getCarried();
        if (toInventory) {
            if (stored.isEmpty() || !player.getInventory().add(stored.copy())) {
                return;
            }
            items[slot] = ItemStack.EMPTY;
        } else if (carried.isEmpty()) {
            if (stored.isEmpty()) {
                return;
            }
            menu.setCarried(stored);
            items[slot] = ItemStack.EMPTY;
        } else if (isArtifact(carried)) {
            if (stored.isEmpty()) {
                items[slot] = carried.split(1);
            } else if (carried.getCount() == 1) {
                items[slot] = carried;
                menu.setCarried(stored);
            } else {
                return;
            }
        } else {
            return;
        }
        write(container, items);
        menu.broadcastChanges();
    }

    /** Shift-click on an artifact in the player's inventory: into the first free slot of the worn container. */
    public static void store(ServerPlayer player, int menuSlot) {
        AbstractContainerMenu menu = player.containerMenu;
        ItemStack container = CuriosCompat.wornContainer(player);
        if (container.isEmpty() || menuSlot < 0 || menuSlot >= menu.slots.size()) {
            return;
        }
        Slot slot = menu.getSlot(menuSlot);
        if (slot.container != player.getInventory() || !isArtifact(slot.getItem()) || !slot.mayPickup(player)) {
            return;
        }
        ItemStack[] items = read(container);
        for (int i = 0; i < items.length; i++) {
            if (items[i].isEmpty()) {
                items[i] = slot.getItem().split(1);
                slot.setChanged();
                write(container, items);
                menu.broadcastChanges();
                return;
            }
        }
    }
}
