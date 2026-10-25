package com.zoneraids.client;

import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.ShopDataPacket;
import com.zoneraids.network.TerminalActionPacket;
import com.zoneraids.network.TerminalActionPacket.Action;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The hub trader: a "sell" tab with the artifacts the trader buys (and how many the player carries),
 * then one tab per category of goods. Same PDA look as the terminal; only fills, text and item icons.
 * Opened only by a trader (the bunker counter or a camp trader), not a PDA tab; the terminal key closes it, the wheel turns pages.
 */
public final class ShopScreen extends Screen {
    private static final int PANEL_W = PdaTabs.PANEL_W;
    private static final int PANEL_H = PdaTabs.PANEL_H;
    private static final int TABS_Y = 20;
    private static final int LIST_Y = 40;
    private static final int ROW_H = 22;
    private static final int ROWS_PER_PAGE = 7;
    private static final int KEY_GRACE = 6;
    /** Tab id of the selling tab; the others are goods categories. */
    private static final String SELL = "";

    private String tab = SELL;
    private int page;
    private int ticksOpen;

    public ShopScreen() {
        super(Component.translatable("zoneraids.shop.title"));
    }

    void onDataUpdated() {
        rebuildWidgets();
    }

    private int left() {
        return PdaTabs.left(width);
    }

    private int top() {
        return PdaTabs.top(height);
    }

    private static List<String> tabs(ShopDataPacket data) {
        List<String> tabs = new ArrayList<>();
        tabs.add(SELL);
        for (ShopDataPacket.Offer offer : data.offers()) {
            if (!tabs.contains(offer.category())) {
                tabs.add(offer.category());
            }
        }
        return tabs;
    }

    private List<ShopDataPacket.Offer> offers(ShopDataPacket data) {
        List<ShopDataPacket.Offer> list = new ArrayList<>();
        for (ShopDataPacket.Offer offer : data.offers()) {
            if (offer.category().equals(tab)) {
                list.add(offer);
            }
        }
        return list;
    }

    private int rows(ShopDataPacket data) {
        return tab.equals(SELL) ? data.sell().size() : offers(data).size();
    }

    private int pages(ShopDataPacket data) {
        return Math.max(1, (rows(data) + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
    }

    private static Component tabName(String tab) {
        return tab.equals(SELL) ? Component.translatable("zoneraids.shop.sell_tab")
                : Component.translatableWithFallback("zoneraids.shop.cat." + tab, tab);
    }

    @Override
    protected void init() {
        ShopDataPacket data = ClientRaidState.shop;
        int left = left();
        int top = top();
        int by = top + PANEL_H - 25;
        addRenderableWidget(new ZoneButton(left + PANEL_W - 84, by, 74, 17, Component.translatable("gui.done"), b -> onClose()));
        if (data == null) {
            return;
        }
        List<String> tabs = tabs(data);
        if (!tabs.contains(tab)) {
            tab = SELL;
        }
        int[] x = tabEdges(tabs, left);
        for (int i = 0; i < tabs.size(); i++) {
            String id = tabs.get(i);
            addRenderableWidget(new ZoneButton(x[i], top + TABS_Y, x[i + 1] - 3 - x[i], 15, tabName(id), b -> select(id)));
        }
        int pages = pages(data);
        page = Math.min(page, pages - 1);
        int start = page * ROWS_PER_PAGE;
        int buttonX = left + PANEL_W - 70;
        if (tab.equals(SELL)) {
            List<ShopDataPacket.SellEntry> sell = data.sell();
            for (int i = start; i < Math.min(sell.size(), start + ROWS_PER_PAGE); i++) {
                ShopDataPacket.SellEntry entry = sell.get(i);
                int rowY = top + LIST_Y + (i - start) * ROW_H;
                ZoneButton one = new ZoneButton(buttonX - 4, rowY + 3, 34, 14, Component.translatable("zoneraids.shop.sell_one"),
                        b -> send(Action.SHOP_SELL, entry.itemId()));
                ZoneButton all = new ZoneButton(buttonX + 32, rowY + 3, 32, 14, Component.translatable("zoneraids.shop.sell_all"),
                        b -> send(Action.SHOP_SELL_ALL, entry.itemId()));
                one.active = all.active = data.canTrade() && entry.owned() > 0;
                addRenderableWidget(one);
                addRenderableWidget(all);
            }
        } else {
            List<ShopDataPacket.Offer> offers = offers(data);
            for (int i = start; i < Math.min(offers.size(), start + ROWS_PER_PAGE); i++) {
                ShopDataPacket.Offer offer = offers.get(i);
                int rowY = top + LIST_Y + (i - start) * ROW_H;
                ZoneButton buy = new ZoneButton(buttonX - 4, rowY + 3, 68, 14, Component.translatable("zoneraids.shop.buy"),
                        b -> send(Action.SHOP_BUY, offer.index() + ":" + offer.price()));
                buy.active = data.canTrade() && data.money() >= offer.price();
                addRenderableWidget(buy);
            }
        }
        if (pages > 1) {
            ZoneButton prev = new ZoneButton(left + PANEL_W - 160, by, 20, 17, Component.literal("<"), b -> turn(-1));
            prev.active = page > 0;
            addRenderableWidget(prev);
            ZoneButton next = new ZoneButton(left + PANEL_W - 110, by, 20, 17, Component.literal(">"), b -> turn(1));
            next.active = page < pages - 1;
            addRenderableWidget(next);
        }
    }

    private void select(String id) {
        if (!id.equals(tab)) {
            tab = id;
            page = 0;
            rebuildWidgets();
        }
    }

    private void turn(int delta) {
        ShopDataPacket data = ClientRaidState.shop;
        if (data == null) {
            return;
        }
        int next = Math.max(0, Math.min(pages(data) - 1, page + delta));
        if (next != page) {
            page = next;
            rebuildWidgets();
        }
    }

    private static void send(Action action, String arg) {
        RaidNetwork.sendToServer(new TerminalActionPacket(action, arg));
    }

    @Override
    public void tick() {
        ticksOpen++;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ticksOpen > KEY_GRACE && ClientSetup.OPEN_TERMINAL.matches(keyCode, scanCode)) {
            minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta != 0) {
            turn(delta > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int left = left();
        int top = top();
        int right = left + PANEL_W;
        ZoneUi.panel(g, left, top, right, top + PANEL_H, ZoneUi.BG);
        ShopDataPacket data = ClientRaidState.shop;
        Component money = data == null ? null : Component.translatable("zoneraids.shop.money", money(data.money()));
        ZoneUi.header(g, font, left, top, right, title, ZoneUi.AMBER, money, ZoneUi.AMBER);
        if (data == null) {
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        ItemStack hovered = ItemStack.EMPTY;
        int start = page * ROWS_PER_PAGE;
        int textW = PANEL_W - 175;
        if (tab.equals(SELL)) {
            List<ShopDataPacket.SellEntry> sell = data.sell();
            if (sell.isEmpty()) {
                g.drawString(font, Component.translatable("zoneraids.shop.nothing"), left + 10, top + LIST_Y + 6, ZoneUi.DIM);
            }
            for (int i = start; i < Math.min(sell.size(), start + ROWS_PER_PAGE); i++) {
                ShopDataPacket.SellEntry entry = sell.get(i);
                int rowY = top + LIST_Y + (i - start) * ROW_H;
                int mark = entry.owned() > 0 ? ZoneUi.GREEN : ZoneUi.GREY;
                if (row(g, left, rowY, entry.icon(), mark, mouseX, mouseY)) {
                    hovered = entry.icon();
                }
                g.drawString(font, ZoneUi.clip(font, entry.icon().getHoverName().getString(), textW), left + 32, rowY + 2,
                        entry.owned() > 0 ? ZoneUi.TEXT : ZoneUi.DIM);
                g.drawString(font, Component.translatable("zoneraids.shop.owned", entry.owned()), left + 32, rowY + 12, ZoneUi.DIM);
                price(g, right - 80, rowY, entry.price(), ZoneUi.AMBER);
            }
        } else {
            List<ShopDataPacket.Offer> offers = offers(data);
            for (int i = start; i < Math.min(offers.size(), start + ROWS_PER_PAGE); i++) {
                ShopDataPacket.Offer offer = offers.get(i);
                int rowY = top + LIST_Y + (i - start) * ROW_H;
                boolean affordable = data.money() >= offer.price();
                if (row(g, left, rowY, offer.stack(), affordable ? ZoneUi.GREEN : ZoneUi.GREY, mouseX, mouseY)) {
                    hovered = offer.stack();
                }
                g.drawString(font, ZoneUi.clip(font, offer.stack().getHoverName().getString(), textW), left + 32, rowY + 2, ZoneUi.TEXT);
                if (offer.stack().getCount() > 1) {
                    g.drawString(font, Component.translatable("zoneraids.shop.count", offer.stack().getCount()), left + 32, rowY + 12, ZoneUi.DIM);
                }
                price(g, right - 80, rowY, offer.price(), affordable ? ZoneUi.AMBER : ZoneUi.RED);
            }
        }
        if (pages(data) > 1) {
            Component pageText = Component.literal((page + 1) + " / " + pages(data));
            g.drawString(font, pageText, right - 125 - font.width(pageText) / 2, top + PANEL_H - 20, ZoneUi.DIM);
        }
        Component hint = !data.canTrade() ? Component.translatable("zoneraids.shop.in_raid")
                : tab.equals(SELL) ? Component.translatable("zoneraids.shop.hint_sell")
                : Component.translatable("zoneraids.shop.hint_buy");
        g.drawString(font, ZoneUi.clip(font, hint.getString(), PANEL_W - 190), left + 10, top + PANEL_H - 20,
                data.canTrade() ? ZoneUi.DIM : ZoneUi.RED);

        super.render(g, mouseX, mouseY, partialTick);
        underlineTab(g, data, left, top);
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mouseX, mouseY);
        }
    }

    /** Row plate with a status strip and the item icon. @return true if the mouse is on the icon */
    private boolean row(GuiGraphics g, int left, int rowY, ItemStack icon, int mark, int mouseX, int mouseY) {
        boolean hoveredRow = mouseX >= left + 6 && mouseX < left + PANEL_W - 6 && mouseY >= rowY && mouseY < rowY + ROW_H - 2;
        g.fill(left + 6, rowY, left + PANEL_W - 6, rowY + ROW_H - 2, hoveredRow ? ZoneUi.ROW_HOVER : ZoneUi.ROW);
        g.fill(left + 6, rowY, left + 9, rowY + ROW_H - 2, mark);
        g.renderItem(icon, left + 12, rowY + 2);
        return mouseX >= left + 12 && mouseX < left + 28 && mouseY >= rowY + 2 && mouseY < rowY + 18;
    }

    private void price(GuiGraphics g, int rightEdge, int rowY, int price, int color) {
        Component text = Component.translatable("zoneraids.shop.money", money(price));
        g.drawString(font, text, rightEdge - font.width(text), rowY + 7, color);
    }

    private void underlineTab(GuiGraphics g, ShopDataPacket data, int left, int top) {
        List<String> tabs = tabs(data);
        int index = tabs.indexOf(tab);
        if (index < 0) {
            return;
        }
        int[] x = tabEdges(tabs, left);
        ZoneUi.outline(g, x[index], top + TABS_Y, x[index + 1] - 3, top + TABS_Y + 15, ZoneUi.AMBER);
    }

    /** Left edges of the tabs plus the end: each tab is as wide as its name, the spare width is shared out evenly. */
    private int[] tabEdges(List<String> tabs, int left) {
        int[] widths = new int[tabs.size()];
        int used = 0;
        for (int i = 0; i < tabs.size(); i++) {
            widths[i] = font.width(tabName(tabs.get(i))) + 8;
            used += widths[i] + 3;
        }
        int spare = Math.max(0, PANEL_W - 9 - used) / tabs.size();
        int[] x = new int[tabs.size() + 1];
        x[0] = left + 6;
        for (int i = 0; i < tabs.size(); i++) {
            x[i + 1] = x[i] + widths[i] + spare + 3;
        }
        return x;
    }

    /** 12345 -> "12 345" */
    static String money(long amount) {
        String digits = Long.toString(amount);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(' ');
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
