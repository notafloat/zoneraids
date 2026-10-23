package com.zoneraids.client;

import com.zoneraids.util.CuriosCompat;
import com.zoneraids.util.FirstAidCompat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * PDA inventory tab: the player's own inventory menu (always open on the client, like vanilla's
 * inventory screen) drawn in the PDA style, with the player model, health and gear stats beside it.
 * Items move exactly as in the vanilla screen. It replaces the vanilla inventory screen (see
 * {@link ClientEvents}). The worn artifact container is shown with its artifacts and total bonuses (the
 * lines Anomaly Zone puts in its tooltip); its button opens Curios' slots, where it is put on.
 */
public final class PdaInventoryScreen extends AbstractContainerScreen<InventoryMenu> {
    private static final int MENU_X = 10;
    private static final int MENU_Y = 24;

    /** How often the container and its tooltip are read again, in ticks. */
    private static final int CONTAINER_REFRESH = 10;

    private final Player player;
    private ItemStack container = ItemStack.EMPTY;
    private List<Component> containerLines = List.of();
    private int refreshTicks;
    private int containerX;
    private int containerY;

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
        int x = sideX();
        int right = PdaTabs.left(width) + PdaTabs.PANEL_W - 10;
        ZoneButton gear = new ZoneButton(x - 4, PdaTabs.top(height) + PdaTabs.PANEL_H - 24, right + 2 - x, 16,
                Component.translatable("zoneraids.pda.inventory.gear", curiosKey()), b -> PdaTabs.openCurios());
        gear.active = ModList.get().isLoaded("curios");
        addRenderableWidget(gear);
        readContainer();
    }

    private int sideX() {
        return PdaTabs.left(width) + MENU_X + imageWidth + 12;
    }

    /** Name of the key bound to Curios' screen, for the button label. */
    private Component curiosKey() {
        for (KeyMapping key : minecraft.options.keyMappings) {
            if (key.getName().equals("key.curios.open.desc")) {
                return key.getTranslatedKeyMessage();
            }
        }
        return Component.literal("-");
    }

    private void readContainer() {
        container = CuriosCompat.wornContainer(player);
        // The tooltip has the artifacts inside and the total bonuses; the first line is the name.
        List<Component> lines = container.isEmpty() ? List.of() : Screen.getTooltipFromItem(minecraft, container);
        containerLines = lines.size() > 1 ? lines.subList(1, lines.size()) : List.of();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (++refreshTicks >= CONTAINER_REFRESH) {
            refreshTicks = 0;
            readContainer();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (overContainer(mouseX, mouseY) && menu.getCarried().isEmpty()) {
            PdaTabs.openCurios();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean overContainer(double mouseX, double mouseY) {
        return mouseX >= containerX - 1 && mouseX < containerX + 17 && mouseY >= containerY - 1 && mouseY < containerY + 17;
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
        if (!container.isEmpty() && overContainer(mouseX, mouseY) && menu.getCarried().isEmpty()) {
            g.renderTooltip(font, container, mouseX, mouseY);
        }
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

        int x = sideX();
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
        drawContainer(g, x, y, w);
    }

    /** The worn artifact container: icon, name, and the artifacts and bonuses from its tooltip, as far as they fit. */
    private void drawContainer(GuiGraphics g, int x, int y, int w) {
        int bottom = PdaTabs.top(height) + PdaTabs.PANEL_H - 28;
        g.fill(x - 4, y - 2, x + w + 2, y + 9, ZoneUi.ROW);
        g.drawString(font, Component.translatable("zoneraids.pda.inventory.container"), x, y, ZoneUi.AMBER);
        y += 13;
        containerX = x;
        containerY = y;
        ZoneUi.slotFrame(g, x, y);
        if (container.isEmpty()) {
            g.drawWordWrap(font, Component.translatable("zoneraids.pda.inventory.no_container"), x + 22, y, w - 22, ZoneUi.RED);
            return;
        }
        g.renderItem(container, x, y);
        g.drawString(font, ZoneUi.clip(font, container.getHoverName().getString(), w - 22), x + 22, y + 4, ZoneUi.TEXT);
        y += 21;
        for (Component line : containerLines) {
            if (y + 9 > bottom) {
                break;
            }
            // Clipped as a plain string keeps the colour codes Anomaly Zone writes into its lines.
            g.drawString(font, ZoneUi.clip(font, line.getString(), w), x, y, ZoneUi.TEXT);
            y += 10;
        }
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
