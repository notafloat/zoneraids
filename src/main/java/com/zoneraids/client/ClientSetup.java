package com.zoneraids.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.zoneraids.ZoneRaids;
import com.zoneraids.client.render.MutantRenderers;
import com.zoneraids.registry.ModEntities;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    public static final KeyMapping OPEN_TERMINAL = new KeyMapping("key.zoneraids.terminal", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.zoneraids");

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_TERMINAL);
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "pda_hud", new PdaHudOverlay());
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "raid_hud", new RaidHudOverlay());
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.BLIND_DOG.get(), MutantRenderers.BlindDogRenderer::new);
        event.registerEntityRenderer(ModEntities.FLESH.get(), MutantRenderers.FleshRenderer::new);
        event.registerEntityRenderer(ModEntities.SNORK.get(), MutantRenderers::snork);
        event.registerEntityRenderer(ModEntities.BLOODSUCKER.get(), MutantRenderers::bloodsucker);
    }
}
