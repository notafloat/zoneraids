package com.zoneraids.client;

import com.zoneraids.util.FirstAidCompat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

/**
 * PDA inventory tab: the player's own inventory menu (always open on the client, like vanilla's
 * inventory screen) drawn in the PDA style, with the player model, health and gear stats beside it.
 * Items move exactly as in the vanilla screen. It replaces the vanilla inventory screen (see
 * {@link ClientEvents}); Curios slots have their own key.
 */
public final class PdaInventoryScreen extends AbstractContainerScreen<InventoryMenu> {
    private static final int MENU_X = 10;
    private static final int MENU_Y = 24;

    private final Player player;

    public PdaInventoryScreen(Player player) {
        super(player.inventoryMenu, player.getInventory(), Component.translatable("zoneraids.pda.inventory.title"));
        this.player = player;
    }

    @Override
    protected void init() {
        super.init();
        leftPos = PdaTabs.left(width) + MENU_X;
        topPos = PdaTabs.top(height) + MENU_Y;
        PdaTabs.buttons(PdaTabs.left(width), PdaTabs.top(height), PdaTabs.Tab.INVENTORY).forEach(this::addRenderableWidget);
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
        Inventory inventory = player.getInventory();
        int used = 0;
        for (int i = 0; i < inventory.items.size(); i++) {
            if (!inventory.items.get(i).isEmpty()) {
                used++;
            }
        }
        Component load = Component.translatable("zoneraids.pda.slots", used, inventory.items.size());
        ZoneUi.header(g, font, left, top, right, title, ZoneUi.AMBER, load, used >= inventory.items.size() ? ZoneUi.RED : ZoneUi.DIM);

        // Player model in its window, as in the vanilla screen.
        g.fill(leftPos + 26, topPos + 8, leftPos + 75, topPos + 78, 0xC0000000);
        ZoneUi.outline(g, leftPos + 25, topPos + 7, leftPos + 76, topPos + 79, ZoneUi.EDGE);
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, leftPos + 51, topPos + 75, 30,
                leftPos + 51 - mouseX, topPos + 25 - mouseY, player);

        for (Slot slot : menu.slots) {
            ZoneUi.slotFrame(g, leftPos + slot.x, topPos + slot.y);
        }
        // Hotbar: the selected slot is marked like on the HUD.
        int selected = inventory.selected;
        for (Slot slot : menu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == selected) {
                ZoneUi.outline(g, leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, ZoneUi.AMBER);
            }
        }

        int x = leftPos + imageWidth + 12;
        int w = right - 10 - x;
        int y = top + 24;
        FirstAidCompat.Snapshot limbs = FirstAidCompat.read(player);
        float hpFraction = player.getMaxHealth() <= 0 ? 0 : player.getHealth() / player.getMaxHealth();
        g.fill(x - 4, y - 2, right - 8, y + ZoneUi.bodyHeight(3) + 4, ZoneUi.ROW);
        ZoneUi.body(g, x + 12, y + 1, 3, limbs, hpFraction);
        g.drawString(font, Component.translatable("zoneraids.pda.hp", HealthScreen.fmt(player.getHealth()), HealthScreen.fmt(player.getMaxHealth())),
                x + 30, y + 2, ZoneUi.healthColor(hpFraction));
        ZoneUi.bar(g, x + 30, y + 13, w - 34, 4, hpFraction, ZoneUi.healthColor(hpFraction));
        y += ZoneUi.bodyHeight(3) + 10;
        drawStats(g, x, y, w);
    }

    private void drawStats(GuiGraphics g, int x, int y, int w) {
        int food = player.getFoodData().getFoodLevel();
        g.drawString(font, Component.translatable("zoneraids.pda.food", food), x, y, ZoneUi.TEXT);
        ZoneUi.bar(g, x + 70, y + 2, w - 70, 4, food / 20.0F, ZoneUi.AMBER);
        y += 12;
        g.drawString(font, Component.translatable("zoneraids.pda.armor", player.getArmorValue()), x, y, ZoneUi.TEXT);
        ZoneUi.bar(g, x + 70, y + 2, w - 70, 4, Math.min(1.0F, player.getArmorValue() / 20.0F), ZoneUi.DIM);
        y += 16;
        g.drawWordWrap(font, Component.translatable("zoneraids.pda.inventory.hint"), x, y, w, ZoneUi.DIM);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("zoneraids.pda.inventory.backpack"), 8, 73, ZoneUi.DIM, false);
        g.drawString(font, Component.translatable("zoneraids.pda.inventory.craft"), 97, 7, ZoneUi.DIM, false);
        g.drawString(font, "→", 138, 32, ZoneUi.AMBER, false);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int button) {
        int left = PdaTabs.left(width);
        int top = PdaTabs.top(height);
        return mouseX < left || mouseY < top - PdaTabs.TAB_H || mouseX >= left + PdaTabs.PANEL_W || mouseY >= top + PdaTabs.PANEL_H;
    }
}
