package com.zoneraids.client;

import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.TerminalActionPacket;
import com.zoneraids.network.TerminalActionPacket.Action;
import com.zoneraids.network.TerminalDataPacket;
import com.zoneraids.perk.Perk;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Perk list opened from the raid terminal. Uses the terminal data, which carries perk levels and points. */
public final class PerksScreen extends Screen {
    private static final int PANEL_W = 360;
    private static final int PANEL_H = 230;
    private static final int ROW_H = 34;

    private static final int COLOR_BG = 0xE0101410;
    private static final int COLOR_FRAME = 0xFF3A4A30;
    private static final int COLOR_ROW = 0x60283020;
    private static final int COLOR_TITLE = 0xFFD8C060;
    private static final int COLOR_TEXT = 0xFFE0E0D0;
    private static final int COLOR_DIM = 0xFF909080;

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
                int rowY = top + 24 + perk.ordinal() * ROW_H;
                Component label = level >= Perk.MAX_LEVEL ? Component.translatable("zoneraids.perks.max")
                        : Component.translatable("zoneraids.perks.buy", Perk.cost(level + 1));
                Button button = Button.builder(label, b -> RaidNetwork.sendToServer(new TerminalActionPacket(Action.BUY_PERK, perk.id)))
                        .bounds(left + PANEL_W - 78, rowY + 7, 70, 18).build();
                button.active = level < Perk.MAX_LEVEL && data.perkPoints() >= Perk.cost(level + 1);
                addRenderableWidget(button);
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(left + PANEL_W - 78, top + PANEL_H - 22, 70, 18).build());
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
        if (data != null) {
            Component points = Component.translatable("zoneraids.perks.points", data.perkPoints());
            g.drawString(font, points, left + PANEL_W - 8 - font.width(points), top + 8, COLOR_TITLE);
            for (Perk perk : Perk.values()) {
                int rowY = top + 24 + perk.ordinal() * ROW_H;
                g.fill(left + 6, rowY, left + PANEL_W - 6, rowY + ROW_H - 2, COLOR_ROW);
                g.drawString(font, Component.translatable("zoneraids.perks.name", Component.translatable("zoneraids.perk." + perk.id),
                        level(data, perk), Perk.MAX_LEVEL), left + 10, rowY + 4, COLOR_TEXT);
                g.drawString(font, Component.translatable("zoneraids.perk." + perk.id + ".desc"), left + 10, rowY + 18, COLOR_DIM);
            }
        }
        g.drawString(font, Component.translatable("zoneraids.perks.hint"), left + 8, top + PANEL_H - 17, COLOR_DIM);
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
