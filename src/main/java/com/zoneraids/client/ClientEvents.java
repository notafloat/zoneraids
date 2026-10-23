package com.zoneraids.client;

import com.zoneraids.ZoneRaids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
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
    }

    /** The vanilla inventory and the stash's chest screen open as PDA tabs instead. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || event.getNewScreen() == null) {
            return;
        }
        if (event.getNewScreen().getClass() == InventoryScreen.class && mc.gameMode != null && !mc.gameMode.hasInfiniteItems()) {
            event.setNewScreen(new PdaInventoryScreen(mc.player));
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
