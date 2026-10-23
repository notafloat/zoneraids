package com.zoneraids.network;

import com.mojang.logging.LogUtils;
import com.zoneraids.RaidConfig;
import com.zoneraids.ZoneRaids;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Sends player, NPC and bullet positions to clients more often (config {@code [network] entityUpdateIntervals}).
 * The server tick rate stays at 20; only the tracker interval of the listed entity types changes. Forge reads
 * the interval through {@code EntityType.updateIntervalSupplier} when an entity starts being tracked, so
 * swapping that supplier before the worlds load is enough.
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EntitySync {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Suppliers as the owning mod registered them, so a second server start in the same game begins clean. */
    private static final Map<EntityType<?>, ToIntFunction<EntityType<?>>> ORIGINAL = new HashMap<>();
    private static Field supplierField;

    private EntitySync() {
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        try {
            apply();
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Zone Raids: could not change entity update intervals, vanilla sync rates stay", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void apply() throws ReflectiveOperationException {
        if (supplierField == null) {
            supplierField = ObfuscationReflectionHelper.findField(EntityType.class, "updateIntervalSupplier");
        }
        for (Map.Entry<EntityType<?>, ToIntFunction<EntityType<?>>> e : ORIGINAL.entrySet()) {
            supplierField.set(e.getKey(), e.getValue());
        }
        ORIGINAL.clear();

        for (String line : RaidConfig.ENTITY_UPDATE_INTERVALS.get()) {
            int eq = line.indexOf('=');
            ResourceLocation id = ResourceLocation.tryParse(line.substring(0, eq).trim());
            int ticks;
            try {
                ticks = Integer.parseInt(line.substring(eq + 1).trim());
            } catch (NumberFormatException ex) {
                ticks = 0;
            }
            if (id == null || ticks < 1) {
                LOGGER.warn("Zone Raids: bad entityUpdateIntervals entry '{}', expected entity_id=ticks", line);
                continue;
            }
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            if (type == null || !id.equals(ForgeRegistries.ENTITY_TYPES.getKey(type))) {
                continue; // mod not installed
            }
            int current = type.updateInterval();
            if (ticks >= current) {
                continue;
            }
            ORIGINAL.put(type, (ToIntFunction<EntityType<?>>) supplierField.get(type));
            int interval = ticks;
            supplierField.set(type, (ToIntFunction<EntityType<?>>) t -> interval);
            LOGGER.info("Zone Raids: {} position updates every {} tick(s) instead of {}", id, interval, current);
        }
    }
}
