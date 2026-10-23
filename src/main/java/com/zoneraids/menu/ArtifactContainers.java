package com.zoneraids.menu;

import com.zoneraids.util.CuriosCompat;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/** What goes into an Anomaly Zone artifact container, and how many slots one has (no compile-time dependency). */
public final class ArtifactContainers {
    /** Slots when the container class cannot be asked (KSM, the smallest, has 4). */
    private static final int DEFAULT_SLOTS = 4;

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
}
