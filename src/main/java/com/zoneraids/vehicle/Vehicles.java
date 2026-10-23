package com.zoneraids.vehicle;

import com.zoneraids.ZoneRaids;
import com.zoneraids.registry.ModRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Vehicles: registrations and settings. Kept apart from {@link ModRegistry} and the main config so the
 * vehicle feature lives in its own files (own config file {@code zoneraids-vehicles-server.toml}).
 */
public final class Vehicles {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ZoneRaids.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ZoneRaids.MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ZoneRaids.MODID);

    public static final RegistryObject<EntityType<PitBike>> PIT_BIKE = ENTITY_TYPES.register("pit_bike",
            () -> EntityType.Builder.<PitBike>of(PitBike::new, MobCategory.MISC)
                    .sized(1.1F, 1.0F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    // The driver's client owns the motion; server velocity packets would only reset its fall speed.
                    .setShouldReceiveVelocityUpdates(false)
                    .build(ZoneRaids.MODID + ":pit_bike"));

    /** Places a pit bike that belongs to no raid (admin and creative use; it is never removed automatically). */
    public static final RegistryObject<Item> PIT_BIKE_ITEM = ITEMS.register("pit_bike", () -> new PitBikeItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<SoundEvent> PIT_BIKE_ENGINE = SOUNDS.register("pit_bike_engine",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(ZoneRaids.MODID, "pit_bike_engine")));

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue SPAWN_IN_RAIDS;
    public static final ForgeConfigSpec.DoubleValue MAX_SPEED;
    public static final ForgeConfigSpec.DoubleValue HEALTH;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("pit_bike");
        SPAWN_IN_RAIDS = b.comment("Spawn pit bikes at the vehicle points of a map when a raid starts (/raidadmin vehicle ...).",
                        "Raid bikes are removed when the raid ends.")
                .define("spawnInRaids", true);
        MAX_SPEED = b.comment("Top speed in blocks per tick (0.5 = 10 blocks/s, a sprinting player does about 0.28).")
                .defineInRange("maxSpeed", 0.5, 0.1, 1.0);
        HEALTH = b.comment("Damage a bike takes before it is wrecked. A TaCZ rifle bullet does roughly 5-10.")
                .defineInRange("health", 40.0, 1.0, 1000.0);
        b.pop();
        SPEC = b.build();
    }

    private Vehicles() {
    }

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        modBus.addListener(Vehicles::onCreativeTab);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC, ZoneRaids.MODID + "-vehicles-server.toml");
    }

    /** Server config is synced to clients on login; before that (or if it failed to load) fall back to defaults. */
    static double maxSpeed() {
        try {
            return MAX_SPEED.get();
        } catch (IllegalStateException e) {
            return MAX_SPEED.getDefault();
        }
    }

    static float health() {
        try {
            return HEALTH.get().floatValue();
        } catch (IllegalStateException e) {
            return HEALTH.getDefault().floatValue();
        }
    }

    private static void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModRegistry.TAB.getKey())) {
            event.accept(PIT_BIKE_ITEM);
        }
    }
}
