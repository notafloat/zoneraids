package com.zoneraids.util;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads Anomaly Zone's player state for the PDA without a compile-time dependency: the bonuses and
 * contamination of the worn artifact container (computed the way Anomaly Zone does it, from the
 * container's NBT) and food buffs (a client-side copy Anomaly Zone keeps synced), and drives its stamina.
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
    private static boolean staminaFailed;
    private static Map<UUID, Float> staminaMap;
    private static Set<UUID> staminaLocks;
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

    /**
     * Makes Anomaly Zone's server-side stamina follow ours: its value set to ours and its sprint lock lifted,
     * so its own drain, lock and client sprint block never act on their own. Its handler keeps the values in
     * private static maps ({@code StaminaHandler.stamina}, {@code sprintLocked}).
     */
    @SuppressWarnings("unchecked")
    public static void driveStamina(ServerPlayer player, float stamina) {
        if (staminaFailed || !loaded()) {
            return;
        }
        try {
            if (staminaMap == null) {
                Class<?> handler = Class.forName("com.anomalyzone.StaminaHandler");
                Field map = handler.getDeclaredField("stamina");
                Field locked = handler.getDeclaredField("sprintLocked");
                map.setAccessible(true);
                locked.setAccessible(true);
                staminaLocks = (Set<UUID>) locked.get(null);
                staminaMap = (Map<UUID, Float>) map.get(null);
            }
            staminaMap.put(player.getUUID(), stamina);
            staminaLocks.remove(player.getUUID());
        } catch (ReflectiveOperationException | ClassCastException | LinkageError | RuntimeException e) {
            staminaFailed = true;
            LOGGER.warn("Zone Raids: could not reach Anomaly Zone's stamina, it runs on its own", e);
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
