package com.zoneraids.client;

import com.mojang.logging.LogUtils;
import com.zoneraids.menu.ArtifactContainers;
import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.TerminalActionPacket;
import com.zoneraids.util.CuriosCompat;
import com.zoneraids.util.FirstAidCompat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.List;

/**
 * PDA inventory tab with Curios: Curios' menu (equipment slots, armor, inventory, crafting grid) with PDA
 * slot frames, the equipment column on the left and the tab row on top, and beside it the player's health
 * and the worn artifact container. The container's artifact slots work like any other slot: click to take
 * or put an artifact, shift-click to move one between the container and the inventory; the server edits
 * the worn container in place ({@link ArtifactContainers}). {@link ClientEvents} opens this screen for the
 * inventory key and swaps it in for Curios' own screen. Pages of equipment slots turn with the buttons or
 * the mouse wheel through Curios' own page packet. No compile-time dependency on Curios.
 */
public final class PdaCuriosScreen extends AbstractContainerScreen<AbstractContainerMenu> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MENU_Y = 22;
    /** How often the worn container and its tooltip are read again, in ticks. */
    private static final int CONTAINER_REFRESH = 10;
    private static boolean warned;

    /** Left edge of the leftmost slot, relative to the menu (Curios puts its slots at negative x). */
    private int minSlotX;
    private int lastPages = -1;
    private int lastPage = -1;

    private ItemStack container = ItemStack.EMPTY;
    private ItemStack[] artifacts = new ItemStack[0];
    private List<Component> containerLines = List.of();
    private int refreshTicks;
    /** Top-left corner of the first artifact slot, and slots per row; set while drawing. */
    private int artifactX;
    private int artifactY;
    private int artifactColumns = 1;

    public PdaCuriosScreen(AbstractContainerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, Component.translatable("zoneraids.pda.inventory.title"));
    }

    @Override
    protected void init() {
        super.init();
        minSlotX = 0;
        for (Slot slot : menu.slots) {
            minSlotX = Math.min(minSlotX, slot.x);
        }
        leftPos = PdaTabs.left(width) + 12 - minSlotX;
        topPos = PdaTabs.top(height) + MENU_Y;
        PdaTabs.buttons(PdaTabs.left(width), PdaTabs.top(height), PdaTabs.Tab.INVENTORY).forEach(this::addRenderableWidget);
        readContainer();
        int pages = intField("totalPages");
        lastPages = pages;
        lastPage = intField("currentPage");
        if (pages > 1) {
            int by = PdaTabs.top(height) + PdaTabs.PANEL_H - 22;
            int bx = PdaTabs.left(width) + 10;
            addRenderableWidget(new ZoneButton(bx, by, 18, 14, Component.literal("<"), b -> page(false)));
            addRenderableWidget(new ZoneButton(bx + 60, by, 18, 14, Component.literal(">"), b -> page(true)));
        }
    }

    /** A public int field of Curios' menu (page count, current page), or 0. */
    private int intField(String name) {
        try {
            Field field = menu.getClass().getField(name);
            return field.getInt(menu);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return 0;
        }
    }

    private void page(boolean next) {
        try {
            Object channel = Class.forName("top.theillusivec4.curios.common.network.NetworkHandler").getField("INSTANCE").get(null);
            Object packet = Class.forName("top.theillusivec4.curios.common.network.client.CPacketPage")
                    .getConstructor(int.class, boolean.class).newInstance(menu.containerId, next);
            ((SimpleChannel) channel).send(PacketDistributor.SERVER.noArg(), packet);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            if (!warned) {
                warned = true;
                LOGGER.warn("Zone Raids: could not turn the Curios page", e);
            }
        }
    }

    private void readContainer() {
        container = minecraft == null || minecraft.player == null ? ItemStack.EMPTY : CuriosCompat.wornContainer(minecraft.player);
        artifacts = container.isEmpty() ? new ItemStack[0] : ArtifactContainers.read(container);
        // The tooltip lists the artifacts and the total bonuses; the first line is the name.
        List<Component> lines = container.isEmpty() ? List.of() : Screen.getTooltipFromItem(minecraft, container);
        containerLines = lines.size() > 1 ? lines.subList(1, lines.size()) : List.of();
    }

    /** Index of the artifact slot under the mouse, or -1. */
    private int artifactAt(double mouseX, double mouseY) {
        for (int i = 0; i < artifacts.length; i++) {
            int x = artifactX + (i % artifactColumns) * 18;
            int y = artifactY + (i / artifactColumns) * 18;
            if (mouseX >= x - 1 && mouseX < x + 17 && mouseY >= y - 1 && mouseY < y + 17) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasFreeArtifactSlot() {
        for (ItemStack artifact : artifacts) {
            if (artifact.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int artifact = artifactAt(mouseX, mouseY);
        if (artifact >= 0 && button == 0) {
            String arg = hasShiftDown() ? artifact + " shift" : String.valueOf(artifact);
            RaidNetwork.sendToServer(new TerminalActionPacket(TerminalActionPacket.Action.ARTIFACT, arg));
            refreshTicks = CONTAINER_REFRESH - 2;
            return true;
        }
        // Shift-click on an artifact in the inventory puts it into the container, as a chest would.
        Slot slot = hoveredSlot;
        if (button == 0 && hasShiftDown() && slot != null && minecraft != null && slot.container == minecraft.player.getInventory()
                && ArtifactContainers.isArtifact(slot.getItem()) && hasFreeArtifactSlot()) {
            RaidNetwork.sendToServer(new TerminalActionPacket(TerminalActionPacket.Action.ARTIFACT_STORE, String.valueOf(slot.index)));
            refreshTicks = CONTAINER_REFRESH - 2;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (++refreshTicks >= CONTAINER_REFRESH) {
            refreshTicks = 0;
            readContainer();
        }
        // Slots are rebuilt when the page changes; the layout follows.
        if (intField("totalPages") != lastPages || intField("currentPage") != lastPage) {
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (intField("totalPages") > 1 && mouseX < leftPos && delta != 0) {
            page(delta < 0);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
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
        Slot hovered = hoveredSlot;
        String name = hovered == null || hovered.hasItem() ? null : slotName(hovered);
        int artifact = artifactAt(mouseX, mouseY);
        if (artifact >= 0 && menu.getCarried().isEmpty()) {
            if (artifacts[artifact].isEmpty()) {
                g.renderTooltip(font, Component.translatable("zoneraids.pda.container.empty_slot"), mouseX, mouseY);
            } else {
                g.renderTooltip(font, artifacts[artifact], mouseX, mouseY);
            }
        } else if (name != null) {
            g.renderTooltip(font, Component.literal(name), mouseX, mouseY);
        } else {
            renderTooltip(g, mouseX, mouseY);
        }
    }

    /** Name of an empty Curios slot ("Слот контейнера", "Рюкзак"), as Curios shows it. */
    @Nullable
    private static String slotName(Slot slot) {
        try {
            return (String) slot.getClass().getMethod("getSlotName").invoke(slot);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private static boolean isCurioSlot(Slot slot) {
        return slot.getClass().getName().startsWith("top.theillusivec4.curios.");
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int left = PdaTabs.left(width);
        int top = PdaTabs.top(height);
        int right = left + PdaTabs.PANEL_W;
        ZoneUi.panel(g, left, top, right, top + PdaTabs.PANEL_H, ZoneUi.BG);
        int worn = 0;
        int curios = 0;
        for (Slot slot : menu.slots) {
            if (isCurioSlot(slot) && slot.isActive()) {
                curios++;
                if (slot.hasItem()) {
                    worn++;
                }
            }
        }
        Inventory inventory = minecraft.player.getInventory();
        int used = 0;
        for (ItemStack stack : inventory.items) {
            if (!stack.isEmpty()) {
                used++;
            }
        }
        ZoneUi.header(g, font, left, top, right, title, ZoneUi.AMBER,
                Component.translatable("zoneraids.pda.inventory.load", used, inventory.items.size(), worn, curios),
                used >= inventory.items.size() ? ZoneUi.RED : ZoneUi.DIM);

        // Player model in the window the menu leaves for it, as in the vanilla inventory.
        g.fill(leftPos + 26, topPos + 8, leftPos + 75, topPos + 78, 0xC0000000);
        ZoneUi.outline(g, leftPos + 25, topPos + 7, leftPos + 76, topPos + 79, ZoneUi.EDGE);
        if (minecraft != null && minecraft.player != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, leftPos + 51, topPos + 75, 30,
                    leftPos + 51 - mouseX, topPos + 25 - mouseY, minecraft.player);
        }
        for (Slot slot : menu.slots) {
            if (!slot.isActive()) {
                continue;
            }
            ZoneUi.slotFrame(g, leftPos + slot.x, topPos + slot.y);
            if (isCurioSlot(slot)) {
                ZoneUi.outline(g, leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0x80D9A441);
            }
        }
        // Hotbar: the selected slot is marked like on the HUD.
        for (Slot slot : menu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == inventory.selected) {
                ZoneUi.outline(g, leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, ZoneUi.AMBER);
            }
        }
        if (lastPages > 1) {
            String page = (intField("currentPage") + 1) + " / " + lastPages;
            g.drawCenteredString(font, page, left + 10 + 39, top + PdaTabs.PANEL_H - 19, ZoneUi.DIM);
        }
        int x = leftPos + imageWidth + 12;
        int w = right - 10 - x;
        int y = drawHealth(g, x, top + 24, w);
        drawContainer(g, x, y, w, mouseX, mouseY);
    }

    /** Health and the body diagram, as on the health tab; returns the y below them. */
    private int drawHealth(GuiGraphics g, int x, int y, int w) {
        Player player = minecraft.player;
        FirstAidCompat.Snapshot limbs = FirstAidCompat.read(player);
        float hpFraction = player.getMaxHealth() <= 0 ? 0 : player.getHealth() / player.getMaxHealth();
        g.fill(x - 4, y - 2, x + w + 2, y + ZoneUi.bodyHeight(3) + 4, ZoneUi.ROW);
        ZoneUi.body(g, x + 12, y + 1, 3, limbs, hpFraction);
        g.drawString(font, Component.translatable("zoneraids.pda.hp", HealthScreen.fmt(player.getHealth()), HealthScreen.fmt(player.getMaxHealth())),
                x + 30, y + 2, ZoneUi.healthColor(hpFraction));
        ZoneUi.bar(g, x + 30, y + 13, w - 34, 4, hpFraction, ZoneUi.healthColor(hpFraction));
        int food = player.getFoodData().getFoodLevel();
        g.drawString(font, Component.translatable("zoneraids.pda.food", food), x + 30, y + 22, ZoneUi.TEXT);
        ZoneUi.bar(g, x + 100, y + 24, w - 104, 4, food / 20.0F, ZoneUi.AMBER);
        return y + ZoneUi.bodyHeight(3) + 10;
    }

    /** The worn artifact container: its artifact slots, then the bonuses from its tooltip, as far as they fit. */
    private void drawContainer(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY) {
        int bottom = PdaTabs.top(height) + PdaTabs.PANEL_H - 8;
        g.fill(x - 4, y - 2, x + w + 2, y + 9, ZoneUi.ROW);
        Component name = container.isEmpty() ? Component.translatable("zoneraids.pda.inventory.container") : container.getHoverName();
        g.drawString(font, ZoneUi.clip(font, name.getString(), w - 40), x, y, ZoneUi.AMBER);
        if (!container.isEmpty()) {
            int used = 0;
            for (ItemStack artifact : artifacts) {
                if (!artifact.isEmpty()) {
                    used++;
                }
            }
            Component count = Component.translatable("zoneraids.pda.slots", used, artifacts.length);
            g.drawString(font, count, x + w - font.width(count), y, used == 0 ? ZoneUi.RED : ZoneUi.DIM);
        }
        y += 14;
        if (container.isEmpty()) {
            artifactColumns = 1;
            g.drawWordWrap(font, Component.translatable("zoneraids.pda.inventory.no_container"), x, y, w, ZoneUi.RED);
            return;
        }
        artifactColumns = Math.max(1, Math.min(artifacts.length, (w + 2) / 18));
        artifactX = x + 1;
        artifactY = y + 1;
        int hovered = artifactAt(mouseX, mouseY);
        for (int i = 0; i < artifacts.length; i++) {
            int sx = artifactX + (i % artifactColumns) * 18;
            int sy = artifactY + (i / artifactColumns) * 18;
            ZoneUi.slotFrame(g, sx, sy);
            ZoneUi.outline(g, sx - 1, sy - 1, sx + 17, sy + 17, i == hovered ? ZoneUi.AMBER : 0x80D9A441);
            if (!artifacts[i].isEmpty()) {
                g.renderItem(artifacts[i], sx, sy);
            }
            if (i == hovered) {
                g.fill(sx, sy, sx + 16, sy + 16, 0x50FFFFFF);
            }
        }
        y += ((artifacts.length + artifactColumns - 1) / artifactColumns) * 18 + 6;
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
        g.drawString(font, Component.translatable("zoneraids.pda.inventory.craft"), 97, 7, ZoneUi.DIM, false);
        g.drawString(font, Component.translatable("zoneraids.pda.inventory.backpack"), 8, 73, ZoneUi.DIM, false);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int button) {
        int left = PdaTabs.left(width);
        int top = PdaTabs.top(height);
        return mouseX < left || mouseY < top - PdaTabs.TAB_H || mouseX >= left + PdaTabs.PANEL_W || mouseY >= top + PdaTabs.PANEL_H;
    }
}
