package com.zoneraids.client;

import com.zoneraids.ZoneRaids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BlastFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.gui.screens.inventory.SmokerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Minecraft's own screens in the PDA style, with fills and text only:
 * <ul>
 * <li>common containers are swapped for {@link PdaVanillaScreen};</li>
 * <li>the other vanilla containers (anvil, enchanting table, villager trades...) keep their pictures inside
 * a PDA frame;</li>
 * <li>every other vanilla screen (pause menu, settings, world list...) gets a PDA frame around the screen,
 * and its plain and cycling buttons are drawn as PDA buttons ({@link SkinnedButton}).</li>
 * </ul>
 * Other mods' screens are left alone; their layouts are their own.
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VanillaStyle {
    private static final Set<Class<?>> RESTYLED = Set.of(ContainerScreen.class, ShulkerBoxScreen.class, DispenserScreen.class,
            HopperScreen.class, CraftingScreen.class, FurnaceScreen.class, BlastFurnaceScreen.class, SmokerScreen.class,
            BrewingStandScreen.class);

    private VanillaStyle() {
    }

    private static boolean vanilla(Screen screen) {
        return screen.getClass().getName().startsWith("net.minecraft.");
    }

    /** Lowest priority: the mod's own swaps (the stash, the inventory) come first. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && event.getNewScreen() instanceof AbstractContainerScreen<?> screen
                && RESTYLED.contains(screen.getClass())) {
            event.setNewScreen(new PdaVanillaScreen(screen, mc.player.getInventory()));
        }
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!vanilla(screen)) {
            return;
        }
        List<GuiEventListener> buttons = new ArrayList<>();
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener.getClass() == Button.class || listener instanceof CycleButton<?>) {
                buttons.add(listener);
            }
        }
        for (GuiEventListener button : buttons) {
            event.removeListener(button);
            event.addListener(new SkinnedButton((AbstractButton) button));
        }
    }

    /** Drawn over the dimmed world or the menu background, under the screen's own widgets and pictures. */
    @SubscribeEvent
    public static void onBackground(ScreenEvent.BackgroundRendered event) {
        Screen screen = event.getScreen();
        if (!vanilla(screen) || screen instanceof CreativeModeInventoryScreen) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        if (screen instanceof AbstractContainerScreen<?> container) {
            // A vanilla container picture inside a PDA frame.
            int x0 = container.getGuiLeft() - 6;
            int y0 = container.getGuiTop() - 20;
            int x1 = container.getGuiLeft() + container.getXSize() + 6;
            ZoneUi.panel(g, x0, y0, x1, container.getGuiTop() + container.getYSize() + 6, ZoneUi.BG);
            ZoneUi.header(g, Minecraft.getInstance().font, x0, y0, x1, Component.translatable("zoneraids.pda.name"), ZoneUi.DIM, null, 0);
            return;
        }
        // Every other screen sits on the PDA's screen: an olive edge with amber corners around it.
        int w = screen.width;
        int h = screen.height;
        g.fill(0, 0, w, h, 0x40101109);
        ZoneUi.outline(g, 3, 3, w - 3, h - 3, ZoneUi.EDGE);
        ZoneUi.corners(g, 3, 3, w - 3, h - 3, ZoneUi.AMBER, 10);
    }
}
