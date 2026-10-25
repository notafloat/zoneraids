package com.zoneraids.world.gen;

import com.zoneraids.ZoneRaids;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * World generation features that make the overworld look like the Zone. Only the feature types live
 * here; where and how often they generate (and which flowers are removed) is pack data: the configured
 * and placed features and the Forge biome modifiers in modpack/glue/data/zoneraids.
 */
public final class ZoneFeatures {
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, ZoneRaids.MODID);

    /** Rusted cars, bus stops, broken walls, sewer pipes, power poles, barrels and fences. */
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> ZONE_DEBRIS = FEATURES.register("zone_debris",
            () -> new ZoneDebrisFeature(NoneFeatureConfiguration.CODEC));
    /** A bare dead tree, or a fallen trunk. */
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> DEAD_TREE = FEATURES.register("dead_tree",
            () -> new DeadTreeFeature(NoneFeatureConfiguration.CODEC));
    /** A patch of trampled grass: coarse dirt, podzol, rooted dirt, a little gravel. */
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> GROUND_WEAR = FEATURES.register("ground_wear",
            () -> new GroundWearFeature(NoneFeatureConfiguration.CODEC));

    private ZoneFeatures() {
    }

    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
    }
}
