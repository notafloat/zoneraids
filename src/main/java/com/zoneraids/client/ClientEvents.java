package com.zoneraids.client;

import com.zoneraids.ZoneRaids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getInstance().player == null) {
            return;
        }
        while (ClientSetup.OPEN_TERMINAL.consumeClick()) {
            PdaTabs.open(PdaTabs.Tab.RAIDS, null);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.tutorialStep != TutorialSteps.NONE) {
            // The vanilla tutorial ("punch a tree", "craft planks") makes no sense on a raid server.
            mc.getTutorial().setStep(TutorialSteps.NONE);
        }
    }

    /**
     * No toasts in the corner: advancement, recipe and tutorial pop-ups cover the PDA HUD and mean
     * nothing in raids. Cleared before every frame, so they never show.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            Minecraft.getInstance().getToasts().clear();
        }
    }

    /** The vanilla inventory, the stash's chest screen and Curios' screen open as PDA tabs instead. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || event.getNewScreen() == null) {
            return;
        }
        if (event.getNewScreen().getClass() == InventoryScreen.class && mc.gameMode != null && !mc.gameMode.hasInfiniteItems()) {
            event.setNewScreen(new PdaInventoryScreen(mc.player));
        } else if (event.getNewScreen() instanceof AbstractContainerScreen<?> curios && PdaTabs.isCuriosScreen(curios)) {
            event.setNewScreen(new PdaCuriosScreen(curios.getMenu(), mc.player.getInventory(), curios.getTitle()));
        } else if (event.getNewScreen() instanceof ContainerScreen chest
                && chest.getTitle().getContents() instanceof TranslatableContents title
                && title.getKey().equals("container.zoneraids.stash")) {
            event.setNewScreen(new PdaStashScreen(chest.getMenu(), mc.player.getInventory(), chest.getTitle()));
        }
    }

    @SubscribeEvent
    public static void onOverlayPre(RenderGuiOverlayEvent.Pre event) {
        PdaHudOverlay.onOverlayPre(event);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientRaidState.reset();
    }
}
