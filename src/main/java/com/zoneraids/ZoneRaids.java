package com.zoneraids;

import com.zoneraids.network.RaidNetwork;
import com.zoneraids.registry.ModEntities;
import com.zoneraids.registry.ModRegistry;
import com.zoneraids.client.ClientSettings;
import com.zoneraids.vehicle.Vehicles;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ZoneRaids.MODID)
public final class ZoneRaids {
    public static final String MODID = "zoneraids";

    public ZoneRaids() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModRegistry.register(modBus);
        ModEntities.register(modBus);
        modBus.addListener(this::commonSetup);
        Vehicles.register(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, RaidConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientSettings.SPEC, MODID + "-client.toml");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(RaidNetwork::register);
    }
}
