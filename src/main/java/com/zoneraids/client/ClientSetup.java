package com.zoneraids.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.zoneraids.ZoneRaids;
import com.zoneraids.client.render.MutantRenderers;
import com.zoneraids.registry.ModEntities;
import com.zoneraids.registry.ModRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    public static final KeyMapping OPEN_TERMINAL = new KeyMapping("key.zoneraids.terminal", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.zoneraids");
    public static final KeyMapping LEAN_LEFT = new KeyMapping("key.zoneraids.lean_left", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Q, "key.categories.zoneraids");
    public static final KeyMapping LEAN_RIGHT = new KeyMapping("key.zoneraids.lean_right", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_E, "key.categories.zoneraids");
    public static final KeyMapping ROLL = new KeyMapping("key.zoneraids.roll", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, "key.categories.zoneraids");

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(ModRegistry.ARTIFACT_CONTAINER_MENU.get(), PdaContainerScreen::new));
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_TERMINAL);
        event.register(LEAN_LEFT);
        event.register(LEAN_RIGHT);
        event.register(ROLL);
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
