package com.zoneraids.client;

import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.TerminalActionPacket;
import com.zoneraids.network.TerminalActionPacket.Action;
import com.zoneraids.network.TerminalDataPacket;
import com.zoneraids.perk.Perk;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Perk list opened from the raid terminal. Uses the terminal data, which carries perk levels and points.
 * Levels are drawn as PDA pips; the terminal key closes the whole terminal.
 */
public final class PerksScreen extends Screen {
    private static final int PANEL_W = 380;
    private static final int PANEL_H = 236;
    private static final int ROW_H = 34;
    private static final int LIST_Y = 22;

    private final Screen parent;

    public PerksScreen(Screen parent) {
        super(Component.translatable("zoneraids.perks.title"));
        this.parent = parent;
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

    private static int level(TerminalDataPacket data, Perk perk) {
        return perk.ordinal() < data.perks().length ? data.perks()[perk.ordinal()] : 0;
    }

    @Override
    protected void init() {
        TerminalDataPacket data = ClientRaidState.terminal;
        int left = left();
        int top = top();
        if (data != null) {
            for (Perk perk : Perk.values()) {
                int level = level(data, perk);
                int rowY = top + LIST_Y + perk.ordinal() * ROW_H;
                Component label = level >= Perk.MAX_LEVEL ? Component.translatable("zoneraids.perks.max")
                        : Component.translatable("zoneraids.perks.buy", Perk.cost(level + 1));
                ZoneButton button = new ZoneButton(left + PANEL_W - 84, rowY + 8, 74, 16, label,
                        b -> RaidNetwork.sendToServer(new TerminalActionPacket(Action.BUY_PERK, perk.id)));
                button.active = level < Perk.MAX_LEVEL && data.perkPoints() >= Perk.cost(level + 1);
                addRenderableWidget(button);
            }
        }
        addRenderableWidget(new ZoneButton(left + PANEL_W - 84, top + PANEL_H - 25, 74, 17, Component.translatable("gui.back"), b -> onClose()));
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int left = left();
        int top = top();
        int right = left + PANEL_W;
        ZoneUi.panel(g, left, top, right, top + PANEL_H, ZoneUi.BG);
        TerminalDataPacket data = ClientRaidState.terminal;
        Component points = data == null ? null : Component.translatable("zoneraids.perks.points", data.perkPoints());
        ZoneUi.header(g, font, left, top, right, title, ZoneUi.AMBER, points, ZoneUi.AMBER);
        if (data != null) {
            for (Perk perk : Perk.values()) {
                int level = level(data, perk);
                int rowY = top + LIST_Y + perk.ordinal() * ROW_H;
                g.fill(left + 6, rowY, right - 6, rowY + ROW_H - 2, ZoneUi.ROW);
                g.fill(left + 6, rowY, left + 9, rowY + ROW_H - 2, level >= Perk.MAX_LEVEL ? ZoneUi.AMBER : level > 0 ? ZoneUi.GREEN : ZoneUi.GREY);
                Component name = Component.translatable("zoneraids.perk." + perk.id);
                g.drawString(font, name, left + 14, rowY + 5, ZoneUi.TEXT);
                int px = left + 14 + font.width(name) + 8;
                for (int i = 0; i < Perk.MAX_LEVEL; i++) {
                    int x = px + i * 9;
                    if (i < level) {
                        g.fill(x, rowY + 5, x + 7, rowY + 12, ZoneUi.AMBER);
                    } else {
                        ZoneUi.outline(g, x, rowY + 5, x + 7, rowY + 12, ZoneUi.EDGE);
                    }
                }
                Component desc = Component.translatable("zoneraids.perk." + perk.id + ".desc");
                g.drawString(font, ZoneUi.clip(font, desc.getString(), PANEL_W - 110), left + 14, rowY + 19, ZoneUi.DIM);
            }
        }
        g.drawString(font, Component.translatable("zoneraids.perks.hint"), left + 10, top + PANEL_H - 20, ZoneUi.DIM);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
