package com.zoneraids.client;

import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.TerminalActionPacket;
import com.zoneraids.network.TerminalActionPacket.Action;
import com.zoneraids.network.TerminalDataPacket;
import com.zoneraids.raid.RaidManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Raid terminal: map list with matchmaking state, join/leave, stash shortcut, party and stats, the
 * way to watching players in raids and to the trader.
 * Widgets are rebuilt whenever new data arrives; while open it asks for a refresh every 2 seconds.
 * The terminal key closes it again, the mouse wheel turns map pages.
 */
public final class RaidTerminalScreen extends Screen {
    private static final int PANEL_W = 380;
    private static final int PANEL_H = 236;
    private static final int ROWS_PER_PAGE = 5;
    private static final int ROW_H = 24;
    private static final int LIST_Y = 22;
    /** Ticks after opening during which the terminal key does not close the screen (key repeat from opening it). */
    private static final int KEY_GRACE = 6;

    private int page;
    private int refreshTicks;
    private int ticksOpen;

    public RaidTerminalScreen() {
        super(Component.translatable("zoneraids.terminal.title"));
    }

    void onDataUpdated() {
        rebuildWidgets();
    }

    private int left() {
        return (width - PANEL_W) / 2;
    }

    private int top() {
        return (height - PANEL_H) / 2;
    }

    private int pages() {
        TerminalDataPacket data = ClientRaidState.terminal;
        return data == null ? 1 : Math.max(1, (data.maps().size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
    }

    @Override
    protected void init() {
        TerminalDataPacket data = ClientRaidState.terminal;
        if (data == null) {
            return;
        }
        int left = left();
        int top = top();
        List<TerminalDataPacket.MapEntry> maps = data.maps();
        int pages = pages();
        page = Math.min(page, pages - 1);

        int start = page * ROWS_PER_PAGE;
        for (int i = start; i < Math.min(maps.size(), start + ROWS_PER_PAGE); i++) {
            TerminalDataPacket.MapEntry map = maps.get(i);
            int rowY = top + LIST_Y + (i - start) * ROW_H;
            boolean queuedHere = map.id().equals(data.queuedMap());
            ZoneButton button;
            if (queuedHere) {
                button = new ZoneButton(left + PANEL_W - 84, rowY + 3, 74, 16,
                        Component.translatable("zoneraids.terminal.leave"), b -> send(Action.LEAVE_QUEUE, ""));
            } else {
                button = new ZoneButton(left + PANEL_W - 84, rowY + 3, 74, 16,
                        Component.translatable("zoneraids.terminal.join"), b -> send(Action.JOIN, map.id()));
                button.active = !data.inRaid() && data.partyLeader() && map.state() != TerminalDataPacket.STATE_UNAVAILABLE;
            }
            addRenderableWidget(button);
        }

        int py = top + LIST_Y + ROWS_PER_PAGE * ROW_H + 1;
        ZoneButton watch = new ZoneButton(left + 10, py, 120, 13,
                Component.translatable("zoneraids.terminal.watch", data.watchable().size()), b -> minecraft.setScreen(new WatchScreen(this)));
        watch.active = !data.inRaid() && (!data.watchable().isEmpty() || !data.watching().isEmpty());
        addRenderableWidget(watch);
        ZoneButton shop = new ZoneButton(left + 134, py, 90, 13, Component.translatable("zoneraids.terminal.shop"), b -> send(Action.SHOP, ""));
        shop.active = data.canShop();
        addRenderableWidget(shop);
        if (pages > 1) {
            ZoneButton prev = new ZoneButton(left + PANEL_W - 84, py, 20, 13, Component.literal("<"), b -> turn(-1));
            prev.active = page > 0;
            addRenderableWidget(prev);
            ZoneButton next = new ZoneButton(left + PANEL_W - 30, py, 20, 13, Component.literal(">"), b -> turn(1));
            next.active = page < pages - 1;
            addRenderableWidget(next);
        }

        int by = top + PANEL_H - 25;
        ZoneButton stash = new ZoneButton(left + 10, by, 80, 17, Component.translatable("zoneraids.terminal.stash"), b -> send(Action.STASH, ""));
        stash.active = data.canStash();
        addRenderableWidget(stash);
        if (!data.pendingInvite().isEmpty()) {
            addRenderableWidget(new ZoneButton(left + 94, by, 130, 17,
                    Component.translatable("zoneraids.terminal.accept_invite", data.pendingInvite()), b -> send(Action.ACCEPT_INVITE, "")));
        } else if (!data.party().isEmpty()) {
            addRenderableWidget(new ZoneButton(left + 94, by, 130, 17,
                    Component.translatable("zoneraids.terminal.leave_party"), b -> send(Action.LEAVE_PARTY, "")));
        }
        addRenderableWidget(new ZoneButton(left + 228, by, 64, 17, Component.translatable("zoneraids.terminal.perks", data.perkPoints()),
                b -> minecraft.setScreen(new PerksScreen(this))));
        addRenderableWidget(new ZoneButton(left + PANEL_W - 84, by, 74, 17, Component.translatable("gui.done"), b -> onClose()));
    }

    private void turn(int delta) {
        int next = Math.max(0, Math.min(pages() - 1, page + delta));
        if (next != page) {
            page = next;
            rebuildWidgets();
        }
    }

    private void send(Action action, String arg) {
        RaidNetwork.sendToServer(new TerminalActionPacket(action, arg));
    }

    @Override
    public void tick() {
        ticksOpen++;
        if (++refreshTicks >= 40) {
            refreshTicks = 0;
            send(Action.REFRESH, "");
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ticksOpen > KEY_GRACE && ClientSetup.OPEN_TERMINAL.matches(keyCode, scanCode)) {
            onClose();
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

        TerminalDataPacket data = ClientRaidState.terminal;
        if (data == null) {
            ZoneUi.header(g, font, left, top, right, title, ZoneUi.AMBER, null, 0);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        Component status = data.inRaid() ? Component.translatable("zoneraids.terminal.status_raid")
                : !data.watching().isEmpty() ? Component.translatable("zoneraids.watch.current", data.watching())
                : data.queuedMap().isEmpty() ? Component.translatable("zoneraids.terminal.status_hub")
                : Component.translatable("zoneraids.terminal.status_queue", data.queuedMap());
        int statusColor = data.inRaid() ? ZoneUi.RED : !data.watching().isEmpty() ? ZoneUi.GREEN
                : data.queuedMap().isEmpty() ? ZoneUi.DIM : ZoneUi.AMBER;
        ZoneUi.header(g, font, left, top, right, title, ZoneUi.AMBER, status, statusColor);

        List<TerminalDataPacket.MapEntry> maps = data.maps();
        if (maps.isEmpty()) {
            g.drawString(font, Component.translatable("zoneraids.terminal.no_maps"), left + 10, top + LIST_Y + 6, ZoneUi.DIM);
        }
        int start = page * ROWS_PER_PAGE;
        for (int i = start; i < Math.min(maps.size(), start + ROWS_PER_PAGE); i++) {
            TerminalDataPacket.MapEntry map = maps.get(i);
            int rowY = top + LIST_Y + (i - start) * ROW_H;
            boolean hovered = mouseX >= left + 6 && mouseX < right - 6 && mouseY >= rowY && mouseY < rowY + ROW_H - 2;
            int color = stateColor(map.state());
            g.fill(left + 6, rowY, right - 6, rowY + ROW_H - 2, hovered ? ZoneUi.ROW_HOVER : ZoneUi.ROW);
            g.fill(left + 6, rowY, left + 9, rowY + ROW_H - 2, color);
            if (map.id().equals(data.queuedMap())) {
                ZoneUi.outline(g, left + 6, rowY, right - 6, rowY + ROW_H - 2, ZoneUi.AMBER);
            }
            g.drawString(font, map.name(), left + 14, rowY + 3, ZoneUi.TEXT);
            g.drawString(font, ZoneUi.clip(font, stateLine(map).getString(), PANEL_W - 110), left + 14, rowY + 13, color);
        }
        if (pages() > 1) {
            Component pageText = Component.literal((page + 1) + " / " + pages());
            g.drawString(font, pageText, right - 57 - font.width(pageText) / 2, top + LIST_Y + ROWS_PER_PAGE * ROW_H + 4, ZoneUi.DIM);
        }

        int infoY = top + LIST_Y + ROWS_PER_PAGE * ROW_H + 18;
        g.fill(left + 6, infoY - 3, right - 6, infoY - 2, 0x40D9A441);
        int[] s = data.stats();
        if (s.length >= 5) {
            int survival = s[0] == 0 ? 0 : Math.round(100.0F * s[1] / s[0]);
            g.drawString(font, Component.translatable("zoneraids.terminal.stats", s[0], s[1], survival, s[2], s[3], s[4]),
                    left + 10, infoY + 2, ZoneUi.TEXT);
        }
        Component party = data.party().isEmpty() ? Component.translatable("zoneraids.terminal.solo")
                : Component.translatable("zoneraids.terminal.party", String.join(", ", data.party()));
        g.drawString(font, party, left + 10, infoY + 14, ZoneUi.DIM);

        super.render(g, mouseX, mouseY, partialTick);

        Component hint = Component.translatable("zoneraids.terminal.hint", ClientSetup.OPEN_TERMINAL.getTranslatedKeyMessage());
        g.drawCenteredString(font, hint, width / 2, Math.min(height - 10, top + PANEL_H + 5), ZoneUi.DIM);
    }

    private static Component stateLine(TerminalDataPacket.MapEntry map) {
        return switch (map.state()) {
            case TerminalDataPacket.STATE_RUNNING -> Component.translatable("zoneraids.terminal.state.running",
                    RaidManager.formatTime(map.seconds()), map.inRaid(), map.queued());
            case TerminalDataPacket.STATE_COUNTDOWN -> Component.translatable("zoneraids.terminal.state.countdown",
                    map.seconds(), map.queued(), map.max());
            case TerminalDataPacket.STATE_UNAVAILABLE -> Component.translatable("zoneraids.terminal.state.unavailable");
            default -> Component.translatable("zoneraids.terminal.state.idle", map.queued(), map.min(), map.max(),
                    RaidManager.formatTime(map.duration()));
        };
    }

    private static int stateColor(byte state) {
        return switch (state) {
            case TerminalDataPacket.STATE_RUNNING -> ZoneUi.RED;
            case TerminalDataPacket.STATE_COUNTDOWN -> ZoneUi.AMBER;
            case TerminalDataPacket.STATE_UNAVAILABLE -> ZoneUi.GREY;
            default -> ZoneUi.GREEN;
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
