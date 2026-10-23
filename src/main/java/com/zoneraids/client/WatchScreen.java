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
 * PDA watch tab: raiders that can be watched. Picking one closes the terminal and
 * puts the camera on the raider; Shift (vanilla spectator) or the stop button here goes back to the hub.
 * The terminal key closes the PDA, the mouse wheel turns pages.
 */
public final class WatchScreen extends Screen {
    private static final int PANEL_W = PdaTabs.PANEL_W;
    private static final int PANEL_H = PdaTabs.PANEL_H;
    private static final int ROWS_PER_PAGE = 7;
    private static final int ROW_H = 24;
    private static final int LIST_Y = 22;

    private int page;
    private int refreshTicks;

    public WatchScreen() {
        super(Component.translatable("zoneraids.watch.title"));
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

    private static List<TerminalDataPacket.WatchEntry> entries() {
        TerminalDataPacket data = ClientRaidState.terminal;
        return data == null ? List.of() : data.watchable();
    }

    private int pages() {
        return Math.max(1, (entries().size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
    }

    @Override
    protected void init() {
        TerminalDataPacket data = ClientRaidState.terminal;
        int left = left();
        int top = top();
        PdaTabs.buttons(left, top, PdaTabs.Tab.WATCH).forEach(this::addRenderableWidget);
        List<TerminalDataPacket.WatchEntry> entries = entries();
        int pages = pages();
        page = Math.min(page, pages - 1);
        String watching = data == null ? "" : data.watching();

        int start = page * ROWS_PER_PAGE;
        for (int i = start; i < Math.min(entries.size(), start + ROWS_PER_PAGE); i++) {
            TerminalDataPacket.WatchEntry entry = entries.get(i);
            int rowY = top + LIST_Y + (i - start) * ROW_H;
            ZoneButton button = new ZoneButton(left + PANEL_W - 84, rowY + 3, 74, 16, Component.translatable("zoneraids.watch.go"), b -> {
                RaidNetwork.sendToServer(new TerminalActionPacket(Action.WATCH, entry.id()));
                minecraft.setScreen(null);
            });
            button.active = !entry.name().equals(watching);
            addRenderableWidget(button);
        }

        int by = top + PANEL_H - 25;
        if (pages > 1) {
            ZoneButton prev = new ZoneButton(left + 10, by, 20, 17, Component.literal("<"), b -> turn(-1));
            prev.active = page > 0;
            addRenderableWidget(prev);
            ZoneButton next = new ZoneButton(left + 64, by, 20, 17, Component.literal(">"), b -> turn(1));
            next.active = page < pages - 1;
            addRenderableWidget(next);
        }
        if (!watching.isEmpty()) {
            addRenderableWidget(new ZoneButton(left + PANEL_W - 218, by, 130, 17, Component.translatable("zoneraids.watch.stop"), b -> {
                RaidNetwork.sendToServer(new TerminalActionPacket(Action.STOP_WATCH, ""));
                minecraft.setScreen(null);
            }));
        }
    }

    private void turn(int delta) {
        int next = Math.max(0, Math.min(pages() - 1, page + delta));
        if (next != page) {
            page = next;
            rebuildWidgets();
        }
    }

    @Override
    public void tick() {
        if (++refreshTicks >= 40) {
            refreshTicks = 0;
            RaidNetwork.sendToServer(new TerminalActionPacket(Action.REFRESH, ""));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ClientSetup.OPEN_TERMINAL.matches(keyCode, scanCode)) {
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
        TerminalDataPacket data = ClientRaidState.terminal;
        String watching = data == null ? "" : data.watching();
        Component status = watching.isEmpty() ? null : Component.translatable("zoneraids.watch.current", watching);
        ZoneUi.header(g, font, left, top, right, title, ZoneUi.AMBER, status, ZoneUi.GREEN);

        List<TerminalDataPacket.WatchEntry> entries = entries();
        if (entries.isEmpty()) {
            g.drawString(font, Component.translatable("zoneraids.watch.none"), left + 10, top + LIST_Y + 6, ZoneUi.DIM);
        }
        int start = page * ROWS_PER_PAGE;
        for (int i = start; i < Math.min(entries.size(), start + ROWS_PER_PAGE); i++) {
            TerminalDataPacket.WatchEntry entry = entries.get(i);
            int rowY = top + LIST_Y + (i - start) * ROW_H;
            boolean hovered = mouseX >= left + 6 && mouseX < right - 6 && mouseY >= rowY && mouseY < rowY + ROW_H - 2;
            boolean current = entry.name().equals(watching);
            int color = entry.teammate() ? ZoneUi.GREEN : ZoneUi.RED;
            g.fill(left + 6, rowY, right - 6, rowY + ROW_H - 2, hovered ? ZoneUi.ROW_HOVER : ZoneUi.ROW);
            g.fill(left + 6, rowY, left + 9, rowY + ROW_H - 2, color);
            if (current) {
                ZoneUi.outline(g, left + 6, rowY, right - 6, rowY + ROW_H - 2, ZoneUi.AMBER);
            }
            Component name = entry.teammate() ? Component.translatable("zoneraids.watch.teammate", entry.name()) : Component.literal(entry.name());
            g.drawString(font, name, left + 14, rowY + 3, ZoneUi.TEXT);
            Component line = entry.rounds() > 1
                    ? Component.translatable("zoneraids.watch.line_round", entry.map(), entry.round(), entry.rounds(), RaidManager.formatTime(entry.seconds()))
                    : Component.translatable("zoneraids.watch.line", entry.map(), RaidManager.formatTime(entry.seconds()));
            g.drawString(font, ZoneUi.clip(font, line.getString(), PANEL_W - 110), left + 14, rowY + 13, ZoneUi.DIM);
        }
        if (pages() > 1) {
            Component pageText = Component.literal((page + 1) + " / " + pages());
            g.drawCenteredString(font, pageText, left + 47, top + PANEL_H - 20, ZoneUi.DIM);
        }

        super.render(g, mouseX, mouseY, partialTick);

        Component hint = Component.translatable("zoneraids.watch.hint", minecraft.options.keyShift.getTranslatedKeyMessage());
        g.drawCenteredString(font, hint, width / 2, Math.min(height - 10, top + PANEL_H + 5), ZoneUi.DIM);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
