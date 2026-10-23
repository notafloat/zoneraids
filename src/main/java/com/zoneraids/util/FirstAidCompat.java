package com.zoneraids.util;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads First Aid's per-limb health without a compile-time dependency, for the PDA health tab and HUD.
 * First Aid syncs the damage model to its own player's client, so this works client-side too.
 */
public final class FirstAidCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Body parts in First Aid's field names. */
    public enum Part {
        HEAD, BODY, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG, LEFT_FOOT, RIGHT_FOOT;

        public final String id = name().toLowerCase();
    }

    /** Health of every part, indexed by {@link Part#ordinal()}, and morphine ticks left. */
    public record Snapshot(float[] health, int[] max, int morphineTicks) {
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
    private static Method maxHealth;
    private static Method morphine;

    private FirstAidCompat() {
    }

    @Nullable
    public static Snapshot read(Player player) {
        if (failed || !ModList.get().isLoaded("firstaid")) {
            return null;
        }
        try {
            if (capability == null) {
                Class<?> api = Class.forName("ichttt.mods.firstaid.api.CapabilityExtendedHealthSystem");
                capability = (Capability<?>) api.getField("INSTANCE").get(null);
            }
            Object model = player.getCapability(capability).resolve().orElse(null);
            if (model == null) {
                return null;
            }
            if (partFields == null) {
                Field[] fields = new Field[Part.values().length];
                for (Part part : Part.values()) {
                    fields[part.ordinal()] = model.getClass().getField(part.name());
                }
                Object head = fields[0].get(model);
                currentHealth = head.getClass().getField("currentHealth");
                maxHealth = head.getClass().getMethod("getMaxHealth");
                morphine = model.getClass().getMethod("getMorphineTicks");
                partFields = fields;
            }
            float[] health = new float[partFields.length];
            int[] max = new int[partFields.length];
            for (int i = 0; i < partFields.length; i++) {
                Object part = partFields[i].get(model);
                health[i] = currentHealth.getFloat(part);
                max[i] = (Integer) maxHealth.invoke(part);
            }
            return new Snapshot(health, max, (Integer) morphine.invoke(model));
        } catch (ReflectiveOperationException | ClassCastException | LinkageError | NullPointerException e) {
            failed = true;
            LOGGER.warn("Zone Raids: could not read First Aid limb health, the PDA shows vanilla health only", e);
            return null;
        }
    }
}
