package com.zoneraids.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;

/**
 * PDA stash tab. The server opens the stash as a plain six-row chest menu; {@link ClientEvents} swaps
 * the vanilla chest screen for this one when the menu's title is the stash's. Items move as in a chest.
 */
public final class PdaStashScreen extends AbstractContainerScreen<ChestMenu> {
    private static final int MENU_X = 10;
    private static final int MENU_Y = 14;

    public PdaStashScreen(ChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageHeight = 114 + menu.getRowCount() * 18;
    }

    @Override
    protected void init() {
        super.init();
        leftPos = PdaTabs.left(width) + MENU_X;
        topPos = PdaTabs.top(height) + MENU_Y;
        PdaTabs.buttons(PdaTabs.left(width), PdaTabs.top(height), PdaTabs.Tab.STASH).forEach(this::addRenderableWidget);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ClientSetup.OPEN_TERMINAL.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int left = PdaTabs.left(width);
        int top = PdaTabs.top(height);
        int right = left + PdaTabs.PANEL_W;
        ZoneUi.panel(g, left, top, right, top + PdaTabs.PANEL_H, ZoneUi.BG);
        Container stash = menu.getContainer();
        int used = 0;
        for (int i = 0; i < stash.getContainerSize(); i++) {
            if (!stash.getItem(i).isEmpty()) {
                used++;
            }
        }
        ZoneUi.header(g, font, left, top, right, Component.translatable("zoneraids.pda.stash.title"), ZoneUi.AMBER,
                Component.translatable("zoneraids.pda.slots", used, stash.getContainerSize()), used >= stash.getContainerSize() ? ZoneUi.RED : ZoneUi.DIM);
        for (Slot slot : menu.slots) {
            ZoneUi.slotFrame(g, leftPos + slot.x, topPos + slot.y);
        }
        int x = leftPos + imageWidth + 12;
        g.drawWordWrap(font, Component.translatable("zoneraids.pda.stash.hint"), x, top + 26, right - 10 - x, ZoneUi.DIM);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("zoneraids.pda.inventory.backpack"), 8, imageHeight - 94, ZoneUi.DIM, false);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int button) {
        int left = PdaTabs.left(width);
        int top = PdaTabs.top(height);
        return mouseX < left || mouseY < top - PdaTabs.TAB_H || mouseX >= left + PdaTabs.PANEL_W || mouseY >= top + PdaTabs.PANEL_H;
    }
}
