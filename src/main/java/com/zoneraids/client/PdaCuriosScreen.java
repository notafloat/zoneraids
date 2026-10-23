package com.zoneraids.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;

/**
 * Curios' screen drawn inside the PDA: the same menu (equipment slots, armor, inventory, crafting grid)
 * with PDA slot frames, the slot column on the left and the tab row on top. {@link ClientEvents} swaps it
 * in for Curios' own screen. Items move exactly as there; pages of slots turn with the buttons or the
 * mouse wheel through Curios' own page packet. No compile-time dependency on Curios.
 */
public final class PdaCuriosScreen extends AbstractContainerScreen<AbstractContainerMenu> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MENU_Y = 22;
    private static boolean warned;

    /** Left edge of the leftmost slot, relative to the menu (Curios puts its slots at negative x). */
    private int minSlotX;
    private int lastPages = -1;
    private int lastPage = -1;

    public PdaCuriosScreen(AbstractContainerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, Component.translatable("zoneraids.pda.gear.title"));
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
        PdaTabs.subview(PdaTabs.left(width), PdaTabs.top(height), PdaTabs.PANEL_W, PdaTabs.Tab.INVENTORY).forEach(this::addRenderableWidget);
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

    @Override
    protected void containerTick() {
        super.containerTick();
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
        if (name != null) {
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
        ZoneUi.header(g, font, left, top, right, title, ZoneUi.AMBER, Component.translatable("zoneraids.pda.slots", worn, curios), ZoneUi.DIM);

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
        if (lastPages > 1) {
            String page = (intField("currentPage") + 1) + " / " + lastPages;
            g.drawCenteredString(font, page, left + 10 + 39, top + PdaTabs.PANEL_H - 19, ZoneUi.DIM);
        }
        int x = leftPos + imageWidth + 12;
        g.drawWordWrap(font, Component.translatable("zoneraids.pda.gear.hint"), x, top + 26, right - 10 - x, ZoneUi.DIM);
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
