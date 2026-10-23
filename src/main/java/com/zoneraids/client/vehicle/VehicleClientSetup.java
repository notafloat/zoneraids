package com.zoneraids.client.vehicle;

import com.zoneraids.ZoneRaids;
import com.zoneraids.vehicle.Vehicles;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VehicleClientSetup {
    private VehicleClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(PitBikeModel.LAYER, PitBikeModel::createLayer);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Vehicles.PIT_BIKE.get(), PitBikeRenderer::new);
    }
}
