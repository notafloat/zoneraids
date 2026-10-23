package com.zoneraids.util;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads First Aid's per-limb health without a compile-time dependency, for the PDA health tab and HUD,
 * and applies its healing items from the PDA. First Aid syncs the damage model to its own player's
 * client, so reading works client-side too.
 */
public final class FirstAidCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Body parts in First Aid's field names. */
    public enum Part {
        HEAD, BODY, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG, LEFT_FOOT, RIGHT_FOOT;

        public final String id = name().toLowerCase();
    }

    /** Health of every part, indexed by {@link Part#ordinal()}, whether a healing item is already working on it, and morphine ticks left. */
    public record Snapshot(float[] health, int[] max, boolean[] healing, int morphineTicks) {
        public float fraction(Part part) {
            int m = max[part.ordinal()];
            return m <= 0 ? 0.0F : Math.max(0.0F, Math.min(1.0F, health[part.ordinal()] / m));
        }
    }

    private static boolean failed;
    @Nullable
    private static Capability<?> capability;
    private static Field[] partFields;
    private static Field currentHealth;
    private static Field activeHealer;
    private static Method maxHealth;
    private static Method morphine;
    @Nullable
    private static Class<?> healingItem;
    private static boolean healFailed;

    private FirstAidCompat() {
    }

    /** First Aid's damage model of the player, with the reflection set up on first use; null without First Aid. */
    @Nullable
    private static Object model(Player player) throws ReflectiveOperationException {
        if (capability == null) {
            Class<?> api = Class.forName("ichttt.mods.firstaid.api.CapabilityExtendedHealthSystem");
            capability = (Capability<?>) api.getField("INSTANCE").get(null);
        }
        Object model = player.getCapability(capability).resolve().orElse(null);
        if (model != null && partFields == null) {
            Field[] fields = new Field[Part.values().length];
            for (Part part : Part.values()) {
                fields[part.ordinal()] = model.getClass().getField(part.name());
            }
            Object head = fields[0].get(model);
            currentHealth = head.getClass().getField("currentHealth");
            activeHealer = head.getClass().getField("activeHealer");
            maxHealth = head.getClass().getMethod("getMaxHealth");
            morphine = model.getClass().getMethod("getMorphineTicks");
            partFields = fields;
        }
        return model;
    }

    @Nullable
    public static Snapshot read(Player player) {
        if (failed || !ModList.get().isLoaded("firstaid")) {
            return null;
        }
        try {
            Object model = model(player);
            if (model == null) {
                return null;
            }
            float[] health = new float[partFields.length];
            int[] max = new int[partFields.length];
            boolean[] healing = new boolean[partFields.length];
            for (int i = 0; i < partFields.length; i++) {
                Object part = partFields[i].get(model);
                health[i] = currentHealth.getFloat(part);
                max[i] = (Integer) maxHealth.invoke(part);
                healing[i] = activeHealer.get(part) != null;
            }
            return new Snapshot(health, max, healing, (Integer) morphine.invoke(model));
        } catch (ReflectiveOperationException | ClassCastException | LinkageError | NullPointerException e) {
            failed = true;
            LOGGER.warn("Zone Raids: could not read First Aid limb health, the PDA shows vanilla health only", e);
            return null;
        }
    }

    /** True for First Aid's healing items (bandage, plaster and the like), which go on one body part. */
    public static boolean isHealingItem(ItemStack stack) {
        Class<?> type = healingItemClass();
        return type != null && type.isInstance(stack.getItem());
    }

    /** How long First Aid makes you hold the item on a part, in milliseconds. */
    public static int applyTimeMillis(ItemStack stack) {
        Class<?> type = healingItemClass();
        try {
            // Looked up on ItemHealing: its items are anonymous subclasses, whose methods cannot be invoked reflectively.
            return type == null ? 2500 : (Integer) type.getMethod("getApplyTime", ItemStack.class).invoke(stack.getItem(), stack);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return 2500;
        }
    }

    @Nullable
    private static Class<?> healingItemClass() {
        if (healingItem == null && !healFailed && ModList.get().isLoaded("firstaid")) {
            try {
                healingItem = Class.forName("ichttt.mods.firstaid.api.healing.ItemHealing");
            } catch (ClassNotFoundException | LinkageError e) {
                healFailed = true;
            }
        }
        return healingItem;
    }

    /**
     * Server side: puts the healing item from inventory slot {@code slot} on {@code part}, as First Aid's
     * own window does with the item in hand: one item is used up and the part heals over time. Refused
     * when the part is at full health or already healing.
     */
    public static void heal(ServerPlayer player, int slot, Part part) {
        Inventory inventory = player.getInventory();
        if (failed || slot < 0 || slot >= inventory.items.size()) {
            return;
        }
        ItemStack stack = inventory.items.get(slot);
        if (!isHealingItem(stack)) {
            return;
        }
        try {
            Object model = model(player);
            if (model == null) {
                return;
            }
            Object damageable = partFields[part.ordinal()].get(model);
            if (activeHealer.get(damageable) != null || currentHealth.getFloat(damageable) >= (Integer) maxHealth.invoke(damageable)) {
                return;
            }
            Object healer = healingItemClass().getMethod("createNewHealer", ItemStack.class).invoke(stack.getItem(), stack);
            if (healer == null) {
                return;
            }
            stack.shrink(1);
            activeHealer.set(damageable, healer);
            sync(player, model);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError | NullPointerException e) {
            if (!healFailed) {
                healFailed = true;
                LOGGER.warn("Zone Raids: could not apply a First Aid healing item from the PDA", e);
            }
        }
    }

    /** Sends the damage model to the player's client, so the PDA shows the part healing at once. */
    private static void sync(ServerPlayer player, Object model) throws ReflectiveOperationException {
        Class<?> message = Class.forName("ichttt.mods.firstaid.common.network.MessageSyncDamageModel");
        Class<?> modelType = Class.forName("ichttt.mods.firstaid.api.damagesystem.AbstractPlayerDamageModel");
        Object packet = message.getConstructor(modelType, boolean.class).newInstance(model, false);
        SimpleChannel channel = (SimpleChannel) Class.forName("ichttt.mods.firstaid.FirstAid").getField("NETWORKING").get(null);
        channel.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
