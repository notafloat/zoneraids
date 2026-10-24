package com.zoneraids.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.Slot;

/**
 * A vanilla container (chest, barrel, shulker box, dispenser, hopper, crafting table, furnaces, brewing
 * stand) drawn as a PDA window: the same menu and slots in a dark panel with a header, PDA slot frames and
 * the furnace and brewing progress as PDA bars. {@link VanillaStyle} swaps it in for the vanilla screen.
 * The recipe book of the crafting table and furnaces is left out.
 */
public final class PdaVanillaScreen extends AbstractContainerScreen<AbstractContainerMenu> {
    private static final int HEADER = 18;

    public PdaVanillaScreen(AbstractContainerScreen<?> vanilla, Inventory inventory) {
        super(vanilla.getMenu(), inventory, vanilla.getTitle());
        imageWidth = vanilla.getXSize();
        imageHeight = vanilla.getYSize();
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        topPos += HEADER / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x0 = leftPos - 6;
        int y0 = topPos - HEADER;
        int x1 = leftPos + imageWidth + 6;
        ZoneUi.panel(g, x0, y0, x1, topPos + imageHeight + 4, ZoneUi.BG);
        ZoneUi.header(g, font, x0, y0, x1, title, ZoneUi.AMBER, null, 0);
        for (Slot slot : menu.slots) {
            ZoneUi.slotFrame(g, leftPos + slot.x, topPos + slot.y);
        }
        if (menu instanceof AbstractFurnaceMenu furnace) {
            // Flame between input and fuel, arrow towards the result, as in the vanilla screen.
            int lit = furnace.isLit() ? furnace.getLitProgress() + 1 : 0;
            g.fill(leftPos + 57, topPos + 37, leftPos + 70, topPos + 50, ZoneUi.TROUGH);
            g.fill(leftPos + 57, topPos + 50 - lit, leftPos + 70, topPos + 50, ZoneUi.RED);
            ZoneUi.bar(g, leftPos + 79, topPos + 40, 24, 5, furnace.getBurnProgress() / 24.0F, ZoneUi.AMBER);
        } else if (menu instanceof BrewingStandMenu brewing) {
            ZoneUi.bar(g, leftPos + 60, topPos + 44, 18, 4, brewing.getFuel() / 20.0F, ZoneUi.AMBER);
            int ticks = brewing.getBrewingTicks();
            int filled = ticks > 0 ? (int) (28 * (1.0F - ticks / 400.0F)) : 0;
            g.fill(leftPos + 97, topPos + 16, leftPos + 106, topPos + 44, ZoneUi.TROUGH);
            g.fill(leftPos + 97, topPos + 16, leftPos + 106, topPos + 16 + filled, ZoneUi.GREEN);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("zoneraids.pda.inventory.backpack"), 8, inventoryLabelY, ZoneUi.DIM, false);
    }
}
