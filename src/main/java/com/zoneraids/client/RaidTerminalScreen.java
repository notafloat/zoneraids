package com.zoneraids.client;

import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.TerminalActionPacket;
import com.zoneraids.network.TerminalActionPacket.Action;
import com.zoneraids.network.TerminalDataPacket;
import com.zoneraids.raid.RaidManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Raid terminal: map list with matchmaking state, join/leave, stash shortcut, party and stats.
 * Widgets are rebuilt whenever new data arrives; while open it asks for a refresh every 2 seconds.
 */
public final class RaidTerminalScreen extends Screen {
    private static final int PANEL_W = 360;
    private static final int PANEL_H = 230;
    private static final int ROWS_PER_PAGE = 5;
    private static final int ROW_H = 24;

    private static final int COLOR_BG = 0xE0101410;
    private static final int COLOR_FRAME = 0xFF3A4A30;
    private static final int COLOR_ROW = 0x60283020;
    private static final int COLOR_TITLE = 0xFFD8C060;
    private static final int COLOR_TEXT = 0xFFE0E0D0;
    private static final int COLOR_DIM = 0xFF909080;

    private int page;
    private int refreshTicks;

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

    @Override
    protected void init() {
        TerminalDataPacket data = ClientRaidState.terminal;
        if (data == null) {
            return;
        }
        int left = left();
        int top = top();
        List<TerminalDataPacket.MapEntry> maps = data.maps();
        int pages = Math.max(1, (maps.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        page = Math.min(page, pages - 1);

        int start = page * ROWS_PER_PAGE;
        for (int i = start; i < Math.min(maps.size(), start + ROWS_PER_PAGE); i++) {
            TerminalDataPacket.MapEntry map = maps.get(i);
            int rowY = top + 28 + (i - start) * ROW_H;
            boolean queuedHere = map.id().equals(data.queuedMap());
            Button button;
            if (queuedHere) {
                button = Button.builder(Component.translatable("zoneraids.terminal.leave"), b -> send(Action.LEAVE_QUEUE, ""))
                        .bounds(left + PANEL_W - 78, rowY + 2, 70, 18).build();
            } else {
                button = Button.builder(Component.translatable("zoneraids.terminal.join"), b -> send(Action.JOIN, map.id()))
                        .bounds(left + PANEL_W - 78, rowY + 2, 70, 18).build();
                button.active = !data.inRaid() && data.partyLeader() && map.state() != TerminalDataPacket.STATE_UNAVAILABLE;
            }
            addRenderableWidget(button);
        }

        if (pages > 1) {
            Button prev = Button.builder(Component.literal("<"), b -> {
                page--;
                rebuildWidgets();
            }).bounds(left + PANEL_W - 78, top + 28 + ROWS_PER_PAGE * ROW_H, 20, 14).build();
            prev.active = page > 0;
            addRenderableWidget(prev);
            Button next = Button.builder(Component.literal(">"), b -> {
                page++;
                rebuildWidgets();
            }).bounds(left + PANEL_W - 28, top + 28 + ROWS_PER_PAGE * ROW_H, 20, 14).build();
            next.active = page < pages - 1;
            addRenderableWidget(next);
        }

        int by = top + PANEL_H - 26;
        Button stash = Button.builder(Component.translatable("zoneraids.terminal.stash"), b -> send(Action.STASH, ""))
                .bounds(left + 8, by, 80, 18).build();
        stash.active = data.canStash();
        addRenderableWidget(stash);
        if (!data.pendingInvite().isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("zoneraids.terminal.accept_invite", data.pendingInvite()),
                    b -> send(Action.ACCEPT_INVITE, "")).bounds(left + 92, by, 130, 18).build());
        } else if (!data.party().isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("zoneraids.terminal.leave_party"),
                    b -> send(Action.LEAVE_PARTY, "")).bounds(left + 92, by, 130, 18).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + PANEL_W - 78, by, 70, 18).build());
    }

    private void send(Action action, String arg) {
        RaidNetwork.sendToServer(new TerminalActionPacket(action, arg));
    }

    @Override
    public void tick() {
        if (++refreshTicks >= 40) {
            refreshTicks = 0;
            send(Action.REFRESH, "");
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int left = left();
        int top = top();
        g.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, COLOR_FRAME);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, COLOR_BG);
        g.drawString(font, title, left + 8, top + 8, COLOR_TITLE);

        TerminalDataPacket data = ClientRaidState.terminal;
        if (data == null) {
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        Component status = data.inRaid() ? Component.translatable("zoneraids.terminal.status_raid")
                : data.queuedMap().isEmpty() ? Component.translatable("zoneraids.terminal.status_hub")
                : Component.translatable("zoneraids.terminal.status_queue", data.queuedMap());
        g.drawString(font, status, left + PANEL_W - 8 - font.width(status), top + 8, COLOR_DIM);

        List<TerminalDataPacket.MapEntry> maps = data.maps();
        if (maps.isEmpty()) {
            g.drawString(font, Component.translatable("zoneraids.terminal.no_maps"), left + 10, top + 34, COLOR_DIM);
        }
        int start = page * ROWS_PER_PAGE;
        for (int i = start; i < Math.min(maps.size(), start + ROWS_PER_PAGE); i++) {
            TerminalDataPacket.MapEntry map = maps.get(i);
            int rowY = top + 28 + (i - start) * ROW_H;
            g.fill(left + 6, rowY, left + PANEL_W - 6, rowY + ROW_H - 2, COLOR_ROW);
            g.drawString(font, map.name(), left + 10, rowY + 3, COLOR_TEXT);
            g.drawString(font, stateLine(map), left + 10, rowY + 13, stateColor(map.state()));
        }

        int infoY = top + 28 + ROWS_PER_PAGE * ROW_H + 18;
        int[] s = data.stats();
        if (s.length >= 5) {
            int survival = s[0] == 0 ? 0 : Math.round(100.0F * s[1] / s[0]);
            g.drawString(font, Component.translatable("zoneraids.terminal.stats", s[0], s[1], survival, s[2], s[3], s[4]),
                    left + 10, infoY, COLOR_TEXT);
        }
        Component party = data.party().isEmpty() ? Component.translatable("zoneraids.terminal.solo")
                : Component.translatable("zoneraids.terminal.party", String.join(", ", data.party()));
        g.drawString(font, party, left + 10, infoY + 12, COLOR_DIM);

        super.render(g, mouseX, mouseY, partialTick);
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
            case TerminalDataPacket.STATE_RUNNING -> 0xFFE07050;
            case TerminalDataPacket.STATE_COUNTDOWN -> 0xFFE0D050;
            case TerminalDataPacket.STATE_UNAVAILABLE -> 0xFF707070;
            default -> 0xFF80C080;
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
