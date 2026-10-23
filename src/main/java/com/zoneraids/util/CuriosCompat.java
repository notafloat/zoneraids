package com.zoneraids.util;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Reads and empties Curios slots (backpacks, rigs, artifact containers) without a compile-time dependency on Curios,
 * so wiping a player's gear also covers what they wear there.
 */
public final class CuriosCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean warned;

    private CuriosCompat() {
    }

    public static void clear(LivingEntity entity) {
        IItemHandlerModifiable items = equipped(entity);
        if (items != null) {
            empty(items);
        }
    }

    /** True if the entity wears the item in a Curios slot. */
    public static boolean has(LivingEntity entity, Item item) {
        IItemHandlerModifiable items = equipped(entity);
        if (items != null) {
            for (int i = 0; i < items.getSlots(); i++) {
                if (items.getStackInSlot(i).is(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Items in Anomaly Zone's artifact container slot ({@code curios:container}). Artifacts only work inside one. */
    public static final TagKey<Item> ARTIFACT_CONTAINERS = ItemTags.create(new ResourceLocation("curios", "container"));

    /** The artifact container the entity wears, or an empty stack. Works on the client too: Curios syncs worn items. */
    public static ItemStack wornContainer(LivingEntity entity) {
        IItemHandlerModifiable items = equipped(entity);
        if (items != null) {
            for (int i = 0; i < items.getSlots(); i++) {
                ItemStack stack = items.getStackInSlot(i);
                if (stack.is(ARTIFACT_CONTAINERS)) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    private static IItemHandlerModifiable equipped(LivingEntity entity) {
        if (!ModList.get().isLoaded("curios")) {
            return null;
        }
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Class<?> handlerType = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
            Method inventory = api.getMethod("getCuriosInventory", LivingEntity.class);
            Method equipped = handlerType.getMethod("getEquippedCurios");
            LazyOptional<?> handler = (LazyOptional<?>) inventory.invoke(null, entity);
            Object handlerObj = handler.orElse(null);
            return handlerObj == null ? null : (IItemHandlerModifiable) equipped.invoke(handlerObj);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            if (!warned) {
                warned = true;
                LOGGER.warn("Zone Raids: could not read Curios slots, worn curios survive a wipe", e);
            }
            return null;
        }
    }

    private static void empty(IItemHandlerModifiable items) {
        for (int i = 0; i < items.getSlots(); i++) {
            items.setStackInSlot(i, ItemStack.EMPTY);
        }
    }
}
