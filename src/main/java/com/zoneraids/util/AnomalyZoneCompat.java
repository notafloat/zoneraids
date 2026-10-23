package com.zoneraids.util;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Anomaly Zone's player state for the PDA without a compile-time dependency: the bonuses and
 * contamination of the worn artifact container (computed the way Anomaly Zone does it, from the
 * container's NBT), stamina and food buffs (client-side copies Anomaly Zone keeps synced).
 */
public final class AnomalyZoneCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Fields of Anomaly Zone's {@code AnomalyStats}, in this order in {@link #stats}. */
    public enum Stat {
        SPEED, HEALTH, REGEN, SATURATION, DAMAGE, ARMOR, PSI, BIO, THERMO, RADIATION;

        public final String id = name().toLowerCase();
    }

    /** Contamination above this hurts the player every two seconds (Anomaly Zone's {@code applyContamination}). */
    public static final double HARMFUL = 0.5;

    /** A food buff: the item eaten and ticks left. */
    public record FoodBuff(Item item, int ticks) {
    }

    private static boolean failed;
    private static Field[] statFields;

    private AnomalyZoneCompat() {
    }

    public static boolean loaded() {
        return ModList.get().isLoaded("anomalyzone");
    }

    /**
     * Bonuses and contamination of the worn container, indexed by {@link Stat#ordinal()}; all zero without
     * a container, null without Anomaly Zone.
     */
    @Nullable
    public static double[] stats(Player player) {
        if (failed || !loaded()) {
            return null;
        }
        double[] values = new double[Stat.values().length];
        ItemStack container = CuriosCompat.wornContainer(player);
        if (container.isEmpty()) {
            return values;
        }
        try {
            Class<?> itemType = Class.forName("com.anomalyzone.ModContainerItem");
            if (!itemType.isInstance(container.getItem())) {
                return values;
            }
            Class<?> statsType = Class.forName("com.anomalyzone.AnomalyStats");
            Object stats = statsType.getMethod("fromContainer", ItemStack.class, itemType).invoke(null, container, container.getItem());
            if (statFields == null) {
                Field[] fields = new Field[Stat.values().length];
                for (Stat stat : Stat.values()) {
                    fields[stat.ordinal()] = statsType.getField(stat.id);
                }
                statFields = fields;
            }
            for (int i = 0; i < values.length; i++) {
                values[i] = statFields[i].getDouble(stats);
            }
            return values;
        } catch (ReflectiveOperationException | ClassCastException | LinkageError | NullPointerException e) {
            failed = true;
            LOGGER.warn("Zone Raids: could not read Anomaly Zone artifact stats, the PDA leaves them out", e);
            return null;
        }
    }

    /** Stamina from 0 to 1, or -1 without Anomaly Zone. Client side. */
    public static float stamina() {
        if (!loaded()) {
            return -1.0F;
        }
        try {
            float value = (Float) Class.forName("com.anomalyzone.ClientStaminaData").getMethod("get").invoke(null);
            return Math.max(0.0F, Math.min(1.0F, value / 100.0F));
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            return -1.0F;
        }
    }

    /** True while sprinting is locked after running out of stamina. Client side. */
    public static boolean staminaLocked() {
        try {
            return loaded() && (Boolean) Class.forName("com.anomalyzone.ClientStaminaData").getMethod("isLocked").invoke(null);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            return false;
        }
    }

    /** Active food buffs (Anomaly Zone food gives timed bonuses). Client side. */
    public static List<FoodBuff> foodBuffs() {
        List<FoodBuff> list = new ArrayList<>();
        if (!loaded()) {
            return list;
        }
        try {
            Class<?> entry = Class.forName("com.anomalyzone.ClientFoodBuffData$DisplayEntry");
            Field item = entry.getField("item");
            Field ticks = entry.getField("remainingTicks");
            for (Object e : (List<?>) Class.forName("com.anomalyzone.ClientFoodBuffData").getMethod("getDisplayEntries").invoke(null)) {
                list.add(new FoodBuff((Item) item.get(e), ticks.getInt(e)));
            }
        } catch (ReflectiveOperationException | ClassCastException | LinkageError | NullPointerException e) {
            // No buffs to show.
        }
        return list;
    }
}
