package com.zoneraids.client;

import com.zoneraids.ZoneRaids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BlastFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.gui.screens.inventory.SmokerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;

/**
 * Minecraft's own screens in the PDA style, with fills and text only:
 * <ul>
 * <li>common containers are swapped for {@link PdaVanillaScreen};</li>
 * <li>the anvil, grindstone, enchanting table and villager trading for subclasses that keep vanilla's logic
 * and draw only the picture ({@link PdaAnvilScreens});</li>
 * <li>the rest of the vanilla containers (smithing table, stonecutter, loom, beacon...) keep their pictures
 * inside a PDA frame;</li>
 * <li>every other vanilla screen (pause menu, settings, world list...) gets a PDA frame around the screen;</li>
 * <li>on all of these, Minecraft's buttons and sliders are drawn as PDA ones (two client mixins call
 * {@link #button} and {@link #slider}; if they cannot apply, the vanilla look stays).</li>
 * </ul>
 * Other mods' screens are left alone; their layouts are their own.
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VanillaStyle {
    /** A vanilla screen redrawn by the mod: its buttons are PDA buttons too. */
    interface Restyled {
    }

    private static final Set<Class<?>> RESTYLED = Set.of(ContainerScreen.class, ShulkerBoxScreen.class, DispenserScreen.class,
            HopperScreen.class, CraftingScreen.class, FurnaceScreen.class, BlastFurnaceScreen.class, SmokerScreen.class,
            BrewingStandScreen.class);

    private VanillaStyle() {
    }

    private static boolean vanilla(Screen screen) {
        return screen.getClass().getName().startsWith("net.minecraft.");
    }

    /** True while the open screen is one of Minecraft's (or the mod's redraw of one): its widgets get the PDA look. */
    public static boolean restyles() {
        Screen screen = Minecraft.getInstance().screen;
        return screen != null && (vanilla(screen) || screen instanceof Restyled);
    }

    /** A vanilla button as a PDA button. */
    public static void button(GuiGraphics g, AbstractWidget widget) {
        ZoneButton.draw(g, widget, false);
    }

    /** A vanilla slider: PDA trough, an amber handle at {@code value} (0..1), the text on top. */
    public static void slider(GuiGraphics g, AbstractWidget widget, double value) {
        Font font = Minecraft.getInstance().font;
        int x0 = widget.getX();
        int y0 = widget.getY();
        int x1 = x0 + widget.getWidth();
        int y1 = y0 + widget.getHeight();
        boolean hot = widget.active && widget.isHoveredOrFocused();
        g.fill(x0, y0, x1, y1, widget.active ? 0xE0262619 : 0xC0181812);
        int filled = x0 + (int) (Mth.clamp(value, 0.0, 1.0) * (widget.getWidth() - 8));
        g.fill(x0 + 1, y1 - 3, filled + 4, y1 - 1, 0x80D9A441);
        ZoneUi.outline(g, x0, y0, x1, y1, hot ? ZoneUi.AMBER : ZoneUi.EDGE);
        g.fill(filled, y0, filled + 8, y1, hot ? ZoneUi.AMBER : 0xFF5A5A3E);
        int color = !widget.active ? ZoneUi.GREY : hot ? ZoneUi.AMBER : ZoneUi.TEXT;
        String text = ZoneUi.clip(font, widget.getMessage().getString(), widget.getWidth() - 6);
        g.drawCenteredString(font, text, (x0 + x1) / 2, y0 + (widget.getHeight() - 8) / 2, color);
    }

    /** Lowest priority: the mod's own swaps (the stash, the inventory) come first. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(event.getNewScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        Inventory inventory = mc.player.getInventory();
        Screen replacement = null;
        if (RESTYLED.contains(screen.getClass())) {
            replacement = new PdaVanillaScreen(screen, inventory);
        } else if (screen.getClass() == AnvilScreen.class && screen.getMenu() instanceof AnvilMenu menu) {
            replacement = new PdaAnvilScreens.Anvil(menu, inventory, screen.getTitle());
        } else if (screen.getClass() == GrindstoneScreen.class && screen.getMenu() instanceof GrindstoneMenu menu) {
            replacement = new PdaAnvilScreens.Grindstone(menu, inventory, screen.getTitle());
        } else if (screen.getClass() == EnchantmentScreen.class && screen.getMenu() instanceof EnchantmentMenu menu) {
            replacement = new PdaAnvilScreens.Enchanting(menu, inventory, screen.getTitle());
        } else if (screen.getClass() == MerchantScreen.class && screen.getMenu() instanceof MerchantMenu menu) {
            replacement = new PdaAnvilScreens.Trading(menu, inventory, screen.getTitle());
        }
        if (replacement != null) {
            event.setNewScreen(replacement);
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
