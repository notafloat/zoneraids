package com.zoneraids.client;

import com.zoneraids.menu.ArtifactContainerMenu;
import com.zoneraids.util.CuriosCompat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The worn artifact container, opened from the PDA inventory tab: its artifact slots above the
 * player's inventory, and beside them the container's protection, efficiency and total bonuses (the
 * lines Anomaly Zone puts in its tooltip), updated as artifacts go in and out.
 */
public final class PdaContainerScreen extends AbstractContainerScreen<ArtifactContainerMenu> {
    private static final int MENU_X = 10;
    private static final int MENU_Y = 14;
    private static final int REFRESH_TICKS = 10;

    private List<Component> lines = List.of();
    private int refreshTicks;

    public PdaContainerScreen(ArtifactContainerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageHeight = 114 + menu.rows() * 18;
    }

    @Override
    protected void init() {
        super.init();
        leftPos = PdaTabs.left(width) + MENU_X;
        topPos = PdaTabs.top(height) + MENU_Y;
        PdaTabs.subview(PdaTabs.left(width), PdaTabs.top(height), PdaTabs.PANEL_W, PdaTabs.Tab.INVENTORY).forEach(this::addRenderableWidget);
        readStats();
    }

    private void readStats() {
        ItemStack container = minecraft == null || minecraft.player == null ? ItemStack.EMPTY : CuriosCompat.wornContainer(minecraft.player);
        List<Component> tooltip = container.isEmpty() ? List.of() : Screen.getTooltipFromItem(minecraft, container);
        lines = tooltip.size() > 1 ? tooltip.subList(1, tooltip.size()) : List.of();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (++refreshTicks >= REFRESH_TICKS) {
            refreshTicks = 0;
            readStats();
        }
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
        int used = 0;
        for (int i = 0; i < menu.size; i++) {
            if (menu.slots.get(i).hasItem()) {
                used++;
            }
        }
        ZoneUi.header(g, font, left, top, right, title, ZoneUi.AMBER,
                Component.translatable("zoneraids.pda.slots", used, menu.size), used == 0 ? ZoneUi.RED : ZoneUi.DIM);
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            ZoneUi.slotFrame(g, leftPos + slot.x, topPos + slot.y);
            if (i < menu.size) {
                // Artifact slots get the amber edge, so they read apart from the backpack.
                ZoneUi.outline(g, leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0x80D9A441);
            }
        }

        int x = leftPos + imageWidth + 12;
        int w = right - 10 - x;
        int y = top + 24;
        int bottom = top + PdaTabs.PANEL_H - 40;
        for (Component line : lines) {
            if (y + 9 > bottom) {
                break;
            }
            g.drawString(font, ZoneUi.clip(font, line.getString(), w), x, y, ZoneUi.TEXT);
            y += 10;
        }
        g.drawWordWrap(font, Component.translatable("zoneraids.pda.container.hint"), x, top + PdaTabs.PANEL_H - 36, w, ZoneUi.DIM);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("zoneraids.pda.container.artifacts"), 8, 6, ZoneUi.DIM, false);
        g.drawString(font, Component.translatable("zoneraids.pda.inventory.backpack"), 8, imageHeight - 94, ZoneUi.DIM, false);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int button) {
        int left = PdaTabs.left(width);
        int top = PdaTabs.top(height);
        return mouseX < left || mouseY < top - PdaTabs.TAB_H || mouseX >= left + PdaTabs.PANEL_W || mouseY >= top + PdaTabs.PANEL_H;
    }
}
