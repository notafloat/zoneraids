package com.zoneraids.world;

import com.zoneraids.ZoneRaids;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Dimensions shipped with the mod's datapack (data/zoneraids/dimension). */
public final class ZoneDimensions {
    public static final ResourceKey<Level> HUB = key("hub");
    public static final ResourceKey<Level> CORDON = key("cordon");

    private ZoneDimensions() {
    }

    private static ResourceKey<Level> key(String name) {
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(ZoneRaids.MODID, name));
    }
}
