package com.zoneraids.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentNames;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Vanilla work screens with their logic untouched and only the picture replaced by a PDA panel: the
 * anvil, the grindstone, the enchanting table and villager trading. Each subclass draws the background
 * (panel, slot frames, the screen's own marks from its menu's public state) and light labels; clicks,
 * tooltips, the anvil's name field and the trade list stay vanilla's. {@link VanillaStyle} swaps them in.
 */
final class PdaAnvilScreens {
    private static final int HEADER = 14;

    private PdaAnvilScreens() {
    }

    /** Panel behind a container screen's picture, with its slots framed. */
    static void panel(GuiGraphics g, Font font, AbstractContainerScreen<?> screen, AbstractContainerMenu menu, Component title) {
        int x0 = screen.getGuiLeft() - 4;
        int y0 = screen.getGuiTop() - HEADER;
        int x1 = screen.getGuiLeft() + screen.getXSize() + 4;
        ZoneUi.panel(g, x0, y0, x1, screen.getGuiTop() + screen.getYSize() + 4, ZoneUi.BG);
        ZoneUi.header(g, font, x0, y0, x1, title, ZoneUi.AMBER, null, 0);
        for (Slot slot : menu.slots) {
            ZoneUi.slotFrame(g, screen.getGuiLeft() + slot.x, screen.getGuiTop() + slot.y);
        }
    }

    /** A red cross where a recipe has no result. */
    static void cross(GuiGraphics g, Font font, int x, int y) {
        g.drawString(font, "✕", x, y, ZoneUi.RED, false);
    }

    static final class Anvil extends AnvilScreen implements VanillaStyle.Restyled {
        private static final int RESULT = 2;

        Anvil(AnvilMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
        }

        @Override
        protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
            panel(g, font, this, menu, title);
            // The name field (the edit box draws only its text here).
            g.fill(leftPos + 59, topPos + 20, leftPos + 169, topPos + 36, ZoneUi.TROUGH);
            ZoneUi.outline(g, leftPos + 59, topPos + 20, leftPos + 169, topPos + 36, menu.getSlot(0).hasItem() ? ZoneUi.AMBER : ZoneUi.EDGE);
            g.drawString(font, "+", leftPos + 40, topPos + 51, ZoneUi.DIM, false);
            if (menu.getSlot(0).hasItem() && menu.getSlot(1).hasItem() && !menu.getSlot(RESULT).hasItem()) {
                cross(g, font, leftPos + 104, topPos + 51);
            } else {
                g.drawString(font, "→", leftPos + 104, topPos + 51, ZoneUi.AMBER, false);
            }
        }

        @Override
        protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
            g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, ZoneUi.DIM, false);
            int cost = menu.getCost();
            Player player = minecraft.player;
            if (cost <= 0 || player == null) {
                return;
            }
            Component text = null;
            int color = ZoneUi.GREEN;
            if (cost >= 40 && !player.getAbilities().instabuild) {
                text = Component.translatable("container.repair.expensive");
                color = ZoneUi.RED;
            } else if (menu.getSlot(RESULT).hasItem()) {
                text = Component.translatable("container.repair.cost", cost);
                if (!menu.getSlot(RESULT).mayPickup(player)) {
                    color = ZoneUi.RED;
                }
            }
            if (text != null) {
                int x = imageWidth - 8 - font.width(text) - 2;
                g.fill(x - 2, 67, imageWidth - 8, 79, 0xB0000000);
                g.drawString(font, text, x, 69, color, false);
            }
        }
    }

    static final class Grindstone extends GrindstoneScreen implements VanillaStyle.Restyled {
        Grindstone(GrindstoneMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
        }

        @Override
        protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
            panel(g, font, this, menu, title);
            if ((menu.getSlot(0).hasItem() || menu.getSlot(1).hasItem()) && !menu.getSlot(2).hasItem()) {
                cross(g, font, leftPos + 96, topPos + 35);
            } else {
                g.drawString(font, "→", leftPos + 96, topPos + 35, ZoneUi.AMBER, false);
            }
        }

        @Override
        protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
            g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, ZoneUi.DIM, false);
        }
    }

    /** The enchanting table: the three offers as PDA rows (level, the table's runes, the level cost); no book. */
    static final class Enchanting extends EnchantmentScreen implements VanillaStyle.Restyled {
        private static final String[] LEVELS = {"I", "II", "III"};

        Enchanting(EnchantmentMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
        }

        @Override
        protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
            panel(g, font, this, menu, title);
            g.drawCenteredString(font, "✦", leftPos + 33, topPos + 22, ZoneUi.AMBER);
            Player player = minecraft.player;
            int lapis = menu.getGoldCount();
            EnchantmentNames.getInstance().initSeed(menu.getEnchantmentSeed());
            for (int i = 0; i < 3; i++) {
                int x = leftPos + 60;
                int y = topPos + 14 + 19 * i;
                int cost = menu.costs[i];
                g.fill(x, y, x + 108, y + 19, ZoneUi.TROUGH);
                ZoneUi.outline(g, x, y, x + 108, y + 19, ZoneUi.EDGE);
                if (cost == 0) {
                    continue;
                }
                boolean can = player != null && (player.getAbilities().instabuild || lapis >= i + 1 && player.experienceLevel >= cost);
                boolean hot = can && mouseX >= x && mouseX < x + 108 && mouseY >= y && mouseY < y + 19;
                if (hot) {
                    g.fill(x + 1, y + 1, x + 107, y + 18, ZoneUi.ROW_HOVER);
                    ZoneUi.outline(g, x, y, x + 108, y + 19, ZoneUi.AMBER);
                }
                g.drawCenteredString(font, LEVELS[i], x + 10, y + 6, can ? ZoneUi.AMBER : ZoneUi.GREY);
                String level = String.valueOf(cost);
                int w = 86 - font.width(level);
                FormattedText runes = EnchantmentNames.getInstance().getRandomName(font, w);
                g.drawWordWrap(font, runes, x + 20, y + 2, w, can ? (hot ? ZoneUi.TEXT : ZoneUi.DIM) : ZoneUi.GREY);
                g.drawString(font, level, x + 20 + 86 - font.width(level), y + 10, can ? ZoneUi.GREEN : ZoneUi.GREY);
            }
        }

        @Override
        protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
            g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, ZoneUi.DIM, false);
        }
    }

    /** Villager trading: the trade list and its buttons stay vanilla's (drawn as PDA buttons); the picture behind is the PDA's. */
    static final class Trading extends MerchantScreen implements VanillaStyle.Restyled {
        Trading(MerchantMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
        }

        /** The villager's name with its level, in the header. */
        private Component name() {
            int level = menu.getTraderLevel();
            return level > 0 && level <= 5 && menu.showProgressBar()
                    ? title.copy().append(" - ").append(Component.translatable("merchant.level." + level)) : title;
        }

        @Override
        protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
            panel(g, font, this, menu, name());
            // The trade list column, and the arrow between the payment and the goods.
            g.fill(leftPos + 4, topPos + 16, leftPos + 97, topPos + 162, ZoneUi.TROUGH);
            g.drawString(font, "→", leftPos + 189, topPos + 39, ZoneUi.AMBER, false);
        }

        @Override
        protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
            g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, ZoneUi.DIM, false);
            Component trades = Component.translatable("merchant.trades");
            g.drawString(font, trades, 5 - font.width(trades) / 2 + 48, 6, ZoneUi.AMBER, false);
        }
    }
}
